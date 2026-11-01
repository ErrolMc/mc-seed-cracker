package com.errolmc.seedcracker.crack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Finds every 48-bit surface key that produces a set of observed bedrock blocks.
 *
 * <p>The top 36 bits are tried one by one. For each, the low 12 bits are still open, but a block's first random step
 * {@code (key ^ k) * M + 11} can then only land in a window of width {@code 4095 * M} (about 37% of the 2^48 range).
 * If that window misses every value that gives the observed outcome, no low bits can save the prefix and it is
 * dropped. Bedrock at y 4 is allowed only 20% of the range, so on its own it rules out 43% of prefixes. Prefixes that
 * survive are split one bit at a time with narrower windows, down to exact checks with all 48 bits known.
 *
 * <p>The lowest few key bits hardly move any block's roll, so a handful of the right key's near neighbours usually fit a
 * chunk's blocks too. They lead to unrelated world seeds, which the other surface or the seed hash rules out.
 */
public final class KeySearch {
	/** Low key bits left open while walking the prefixes. 12 keeps the window (4095 * M) under the 2^48 range. */
	public static final int OPEN_BITS = 12;
	public static final long PREFIXES = 1L << (48 - OPEN_BITS);

	private static final long RANGE = 1L << 48;

	/** checks[k]: the tests that can rule something out with the low k bits open, best first. */
	private final Check[][] checks = new Check[OPEN_BITS + 1][];
	// The first four tests at the top level, unrolled into fields for the hot loop.
	private final long key0, offset0, limit0, key1, offset1, limit1, key2, offset2, limit2, key3, offset3, limit3;
	private final int observations;

	/**
	 * One block's test with the low {@code openBits} of the key unknown. The block's random state after one step is
	 * {@code (key ^ k) * M + 11}; the observed outcome needs it in [lo, hi). Shifting by -hi turns that into "v at or
	 * above 2^48 - width", and the whole window [v, v + span] missing it is "v below limit".
	 */
	private record Check(long key, long offset, long limit) {
		static Check of(Observation observation, Surface surface, int openBits) {
			long lowMask = (1L << openBits) - 1;
			long span = lowMask * JavaLcg.MULTIPLIER;
			long cut = (long) surface.threshold(observation.y()) << 24;
			boolean conditionHolds = observation.bedrock() != surface.inverted;
			long lo = conditionHolds ? 0 : cut;
			long hi = conditionHolds ? cut : RANGE;
			long limit = RANGE - span - (hi - lo);
			long k = JavaLcg.scramble(NetherBedrock.positionSeed(observation.x(), observation.y(), observation.z()));
			return new Check(k & ~lowMask, JavaLcg.ADDEND - hi & JavaLcg.MASK, limit);
		}

		/** The tests that can never rule anything out are dropped, and this one stands in for a missing one. */
		static final Check NEVER = new Check(0, 0, 0);

		boolean rejects(long key) {
			return ((key ^ this.key) * JavaLcg.MULTIPLIER + offset & JavaLcg.MASK) < limit;
		}
	}

	public KeySearch(Surface surface, List<Observation> observations) {
		this.observations = observations.size();
		for (int openBits = 0; openBits <= OPEN_BITS; openBits++) {
			List<Check> level = new ArrayList<>();
			for (Observation observation : observations) {
				Check check = Check.of(observation, surface, openBits);
				if (check.limit() > 0) {
					level.add(check);
				}
			}
			level.sort(Comparator.comparingLong(Check::limit).reversed());
			checks[openBits] = level.toArray(Check[]::new);
		}
		Check[] top = checks[OPEN_BITS];
		Check c0 = top.length > 0 ? top[0] : Check.NEVER;
		Check c1 = top.length > 1 ? top[1] : Check.NEVER;
		Check c2 = top.length > 2 ? top[2] : Check.NEVER;
		Check c3 = top.length > 3 ? top[3] : Check.NEVER;
		key0 = c0.key(); offset0 = c0.offset(); limit0 = c0.limit();
		key1 = c1.key(); offset1 = c1.offset(); limit1 = c1.limit();
		key2 = c2.key(); offset2 = c2.offset(); limit2 = c2.limit();
		key3 = c3.key(); offset3 = c3.offset(); limit3 = c3.limit();
	}

	public int observations() {
		return observations;
	}

	/**
	 * Roughly how many unrelated keys would still fit the observations, treating blocks as independent (which ignores
	 * the right key's near neighbours). Well below 1 means the sample is big enough.
	 */
	public double expectedFalseKeys() {
		double log2 = 48;
		for (Check check : checks[0]) {
			log2 += Math.log((RANGE - check.limit()) / (double) RANGE) / Math.log(2);
		}
		return Math.pow(2, log2);
	}

	/** Calls {@code hit} with every key whose top 36 bits are in [fromPrefix, toPrefix) and that fits every observation. */
	public void search(long fromPrefix, long toPrefix, LongConsumer hit) {
		final long m = JavaLcg.MULTIPLIER;
		final long mask = JavaLcg.MASK;
		Check[] top = checks[OPEN_BITS];
		for (long prefix = fromPrefix; prefix < toPrefix; prefix++) {
			long key = prefix << OPEN_BITS;
			// Non-short-circuit | so the four tests run without branches; about 10% of prefixes get past them.
			if (((key ^ key0) * m + offset0 & mask) < limit0
				| ((key ^ key1) * m + offset1 & mask) < limit1
				| ((key ^ key2) * m + offset2 & mask) < limit2
				| ((key ^ key3) * m + offset3 & mask) < limit3) {
				continue;
			}
			if (!rejectedByAny(top, 4, key)) {
				split(key, OPEN_BITS - 1, hit);
			}
		}
	}

	/** Tries both values of bit {@code openBits}; the bits below it stay open. */
	private void split(long key, int openBits, LongConsumer hit) {
		visit(key, openBits, hit);
		visit(key | 1L << openBits, openBits, hit);
	}

	private void visit(long key, int openBits, LongConsumer hit) {
		if (rejectedByAny(checks[openBits], 0, key)) {
			return;
		}
		if (openBits == 0) {
			hit.accept(key);
		} else {
			split(key, openBits - 1, hit);
		}
	}

	private static boolean rejectedByAny(Check[] level, int from, long key) {
		for (int i = from; i < level.length; i++) {
			if (level[i].rejects(key)) {
				return true;
			}
		}
		return false;
	}

	/** Prefixes are numbered 0 to {@link #PREFIXES}; this is the one a key falls in. */
	public static long prefixOf(long key) {
		return (key & JavaLcg.MASK) >>> OPEN_BITS;
	}
}
