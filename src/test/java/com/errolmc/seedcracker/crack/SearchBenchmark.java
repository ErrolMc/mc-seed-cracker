package com.errolmc.seedcracker.crack;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code gradlew benchmark}: how fast the search runs here, and how long a whole crack would take. */
@Tag("benchmark")
class SearchBenchmark {
	@Test
	void fullSpeed() {
		long seed = 123456789L;
		List<Observation> floor = Chunks.observe(seed, Surface.FLOOR, 7, 7);
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

		// Warm up the JIT on one thread first.
		new KeySearch(Surface.FLOOR, floor).search(1L << 34, (1L << 34) + (1L << 24), key -> { });

		long prefixes = 1L << 31;
		long start = 1L << 35;
		Crack crack = new Crack(Surface.FLOOR, floor, List.of(), OptionalLong.empty(), start, start + prefixes);
		long began = System.nanoTime();
		crack.start(threads);
		while (crack.outcome() == null) {
			Thread.onSpinWait();
		}
		double seconds = (System.nanoTime() - began) / 1e9;
		double perSecond = prefixes / seconds;
		double full = KeySearch.PREFIXES / perSecond;
		System.out.printf("%d threads: %.0f million prefixes/s (%.1f ns per prefix per thread)%n",
			threads, perSecond / 1e6, seconds * 1e9 * threads / prefixes);
		System.out.printf("whole search: %.0f s worst case, %.0f s on average%n", full, full / 2);
	}

	/** A real crack with nothing narrowed down: a random seed, one chunk's floor and roof, and the seed hash. */
	@Test
	void wholeCrack() {
		long seed = new java.util.Random().nextLong();
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		long key = NetherBedrock.surfaceKeyOfWorld(seed, Surface.FLOOR);
		Crack crack = new Crack(Surface.FLOOR, Chunks.observe(seed, Surface.FLOOR, -20, 31), Chunks.observe(seed, Surface.ROOF, -20, 31),
			OptionalLong.of(SeedRecovery.obfuscate(seed)));
		long began = System.nanoTime();
		crack.start(threads);
		while (crack.outcome() == null) {
			Thread.onSpinWait();
		}
		double seconds = (System.nanoTime() - began) / 1e9;
		Crack.Outcome outcome = crack.outcome();
		System.out.printf("seed %d (key %.0f%% into the search): found %s in %.1f s%n",
			seed, 100.0 * KeySearch.prefixOf(key) / KeySearch.PREFIXES, outcome.found(), seconds);
		if (outcome.best() == null || outcome.best().seed() != seed || outcome.best().confidence() != Crack.Confidence.CONFIRMED) {
			throw new AssertionError("wrong result for " + seed + ": " + outcome);
		}
	}
}
