package com.errolmc.seedcracker.crack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One run of the cracker on background threads: searches one surface's key from a block sample, checks each hit
 * against the other surface, then works out the world seed.
 *
 * <p>The search is exact, so a single wrong block in the primary sample makes it miss. The client only puts bedrock that
 * is there in it: survival players can break bedrock but not place it, so what's left is always where generation put
 * it. Bedrock placed in creative or a chunk from before 1.18 still makes it miss. The secondary sample only filters
 * candidates and tolerates a few wrong blocks.
 */
public final class Crack {
	/** Prefixes per work unit: small enough for smooth progress and quick cancelling, big enough to not matter. */
	private static final long UNIT = 1L << 22;
	/** Share of secondary blocks a candidate may get wrong (a few placed in creative). */
	private static final double SECONDARY_TOLERANCE = 0.03;
	/** With at least this many secondary blocks, a candidate that fits them is certain enough to stop the search. */
	private static final int STRONG_SECONDARY = 64;

	public enum Confidence {
		/** The seed's hash is the one the server sent: this is the seed. */
		CONFIRMED,
		/** The server's hash didn't match any seed with these low 48 bits; this is the seed if it was picked at random. */
		RANDOM_SEED_GUESS,
		/** Only the low 48 bits are known (no hash to compare, no random seed fits). */
		STRUCTURE_SEED_ONLY
	}

	public record Found(long structureSeed, long seed, Confidence confidence) {
	}

	/** What a finished run found. {@code complete}: every prefix was searched (false after an early stop or a failure). */
	public record Outcome(List<Found> found, boolean complete, boolean cancelled, Throwable error) {
		public Found best() {
			return found.isEmpty() ? null : found.getFirst();
		}
	}

	private final Surface surface;
	private final KeySearch search;
	private final List<Observation> secondary;
	private final OptionalLong hashedSeed;
	private final long fromPrefix;
	private final long toPrefix;
	private final long units;

	private final AtomicLong nextUnit = new AtomicLong();
	private final AtomicLong doneUnits = new AtomicLong();
	private final AtomicInteger running = new AtomicInteger();
	private final AtomicReference<Throwable> error = new AtomicReference<>();
	private final Map<Long, Found> found = new LinkedHashMap<>();
	private volatile boolean stop;
	private volatile boolean cancelled;
	private volatile Outcome outcome;

	public Crack(Surface surface, List<Observation> primary, List<Observation> secondary, OptionalLong hashedSeed) {
		this(surface, primary, secondary, hashedSeed, 0, KeySearch.PREFIXES);
	}

	/** Searches only the prefixes in [fromPrefix, toPrefix): for tests that know roughly where the answer is. */
	public Crack(Surface surface, List<Observation> primary, List<Observation> secondary, OptionalLong hashedSeed,
			long fromPrefix, long toPrefix) {
		this.surface = surface;
		this.search = new KeySearch(surface, primary);
		this.secondary = List.copyOf(secondary);
		this.hashedSeed = hashedSeed;
		this.fromPrefix = fromPrefix;
		this.toPrefix = toPrefix;
		this.units = Math.max(1, (toPrefix - fromPrefix + UNIT - 1) / UNIT);
	}

	public KeySearch search() {
		return search;
	}

	public void start(int threads) {
		running.set(threads);
		for (int i = 0; i < threads; i++) {
			Thread thread = new Thread(this::work, "Seed Cracker #" + (i + 1));
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
		}
	}

	public void cancel() {
		cancelled = true;
		stop = true;
	}

	/** 0 to 1. */
	public double progress() {
		return Math.min(1.0, doneUnits.get() / (double) units);
	}

	/** Null while running. */
	public Outcome outcome() {
		return outcome;
	}

	private void work() {
		try {
			long unit;
			while (!stop && (unit = nextUnit.getAndIncrement()) < units) {
				long from = fromPrefix + unit * UNIT;
				long to = Math.min(toPrefix, from + UNIT);
				search.search(from, to, this::onKey);
				doneUnits.incrementAndGet();
			}
		} catch (Throwable t) {
			error.compareAndSet(null, t);
			stop = true;
		} finally {
			if (running.decrementAndGet() == 0) {
				finish();
			}
		}
	}

	/** A key that fits every primary block. Rare (normally only the right one), so this can afford to be slow. */
	private void onKey(long surfaceKey) {
		Surface other = surface.other();
		List<Long> structureSeeds = SeedRecovery.structureSeeds(surfaceKey, surface,
			rootKey -> fitsSecondary(NetherBedrock.surfaceKey(rootKey, other), other));
		for (long structureSeed : structureSeeds) {
			OptionalLong confirmed = hashedSeed.isPresent()
				? SeedRecovery.worldSeedFromHash(structureSeed, hashedSeed.getAsLong())
				: OptionalLong.empty();
			if (confirmed.isPresent()) {
				add(new Found(structureSeed, confirmed.getAsLong(), Confidence.CONFIRMED));
				stop = true;
				continue;
			}
			long[] guesses = SeedRecovery.randomWorldSeeds(structureSeed);
			if (guesses.length == 0) {
				add(new Found(structureSeed, structureSeed, Confidence.STRUCTURE_SEED_ONLY));
			}
			for (long guess : guesses) {
				add(new Found(structureSeed, guess, Confidence.RANDOM_SEED_GUESS));
			}
			if (hashedSeed.isEmpty() && secondary.size() >= STRONG_SECONDARY) {
				stop = true;
			}
		}
	}

	private boolean fitsSecondary(long otherKey, Surface other) {
		int allowed = (int) (secondary.size() * SECONDARY_TOLERANCE);
		int wrong = 0;
		for (Observation observation : secondary) {
			if (!observation.matches(otherKey, other) && ++wrong > allowed) {
				return false;
			}
		}
		return true;
	}

	private void add(Found result) {
		synchronized (found) {
			found.putIfAbsent(result.seed(), result);
		}
	}

	private void finish() {
		List<Found> results;
		synchronized (found) {
			results = new ArrayList<>(found.values());
		}
		// Confirmed seeds first.
		results.sort((a, b) -> a.confidence().compareTo(b.confidence()));
		boolean complete = doneUnits.get() == units && error.get() == null;
		outcome = new Outcome(List.copyOf(results), complete, cancelled, error.get());
	}
}
