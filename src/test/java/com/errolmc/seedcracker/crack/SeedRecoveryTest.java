package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.hash.Hashing;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SeedRecoveryTest {
	@Test
	void obfuscateMatchesBiomeManager() {
		Random seeds = new Random(5);
		for (int i = 0; i < 100; i++) {
			long seed = seeds.nextLong();
			// BiomeManager.obfuscateSeed in 26.3.
			assertEquals(Hashing.sha256().hashLong(seed).asLong(), SeedRecovery.obfuscate(seed));
		}
	}

	@Test
	void keyBackToStructureSeed() {
		long worldSeed = NetherBedrockTest.WORLD_SEED;
		for (Surface surface : Surface.values()) {
			long key = NetherBedrock.surfaceKeyOfWorld(worldSeed, surface);
			List<Long> seeds = SeedRecovery.structureSeeds(key, surface, rootKey -> true);
			assertTrue(seeds.contains(worldSeed & JavaLcg.MASK), surface + ": " + seeds);
		}
	}

	@Test
	void typedSeedFromHash() {
		// "hello".hashCode(): what a typed text seed becomes. Its top bits are all sign bits.
		for (long seed : new long[] {"hello".hashCode(), -1234567890123456789L, 1L << 63, 42}) {
			OptionalLong found = SeedRecovery.worldSeedFromHash(seed & JavaLcg.MASK, SeedRecovery.obfuscate(seed));
			assertEquals(OptionalLong.of(seed), found);
		}
	}

	@Test
	void randomSeedFromLow48Bits() {
		Random generator = new Random(6);
		for (int i = 0; i < 50; i++) {
			long seed = new Random(generator.nextLong()).nextLong();
			long[] guesses = SeedRecovery.randomWorldSeeds(seed & JavaLcg.MASK);
			assertTrue(Arrays.stream(guesses).anyMatch(guess -> guess == seed), seed + " not in " + Arrays.toString(guesses));
		}
	}

	@Test
	void knownWorldIsARandomSeed() {
		long[] guesses = SeedRecovery.randomWorldSeeds(NetherBedrockTest.WORLD_SEED & JavaLcg.MASK);
		assertTrue(Arrays.stream(guesses).anyMatch(guess -> guess == NetherBedrockTest.WORLD_SEED));
	}
}
