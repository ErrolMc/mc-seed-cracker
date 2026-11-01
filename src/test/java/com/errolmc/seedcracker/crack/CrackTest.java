package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The whole run on threads, searching a window around the right key (the full 2^36 prefixes take minutes). */
class CrackTest {
	private static final long WINDOW = 1L << 20;

	@Test
	void typedSeedConfirmedByHash() {
		long seed = "minecraft".hashCode();
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.of(SeedRecovery.obfuscate(seed)),
			Chunks.present(seed, Surface.FLOOR, 5, 5), Chunks.present(seed, Surface.ROOF, 5, 5));
		assertEquals(List.of(new Crack.Found(seed & JavaLcg.MASK, seed, Crack.Confidence.CONFIRMED)), outcome.found());
	}

	@Test
	void neighbourKeysWithoutOtherSurfaceOrHash() {
		// With nothing to tell the key's near neighbours apart, each comes out as a guess; the right seed is among them.
		long seed = new Random(98).nextLong();
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.empty(), Chunks.present(seed, Surface.FLOOR, 0, 0), List.of());
		assertTrue(outcome.found().stream().anyMatch(found -> found.seed() == seed), outcome.found().toString());
	}

	@Test
	void stopsOnceTheHashMatches() {
		long seed = 77L;
		long prefix = KeySearch.prefixOf(NetherBedrock.surfaceKeyOfWorld(seed, Surface.FLOOR));
		// The key sits near the start of a window that is many work units long.
		Crack crack = new Crack(Surface.FLOOR, Chunks.present(seed, Surface.FLOOR, 0, 0), List.of(),
			OptionalLong.of(SeedRecovery.obfuscate(seed)), prefix - 10, prefix + (1L << 30));
		crack.start(2);
		Crack.Outcome outcome = await(crack);
		assertEquals(seed, outcome.best().seed());
		assertTrue(!outcome.complete());
	}

	@Test
	void roofAsPrimary() {
		long seed = 8_675_309L;
		Crack.Outcome outcome = run(seed, Surface.ROOF, OptionalLong.of(SeedRecovery.obfuscate(seed)),
			Chunks.present(seed, Surface.ROOF, -40, 12), Chunks.present(seed, Surface.FLOOR, -40, 12));
		assertEquals(seed, outcome.best().seed());
		assertEquals(Crack.Confidence.CONFIRMED, outcome.best().confidence());
	}

	@Test
	void randomSeedWithoutHash() {
		long seed = new Random(99).nextLong();
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.empty(),
			Chunks.present(seed, Surface.FLOOR, 0, 0), Chunks.present(seed, Surface.ROOF, 0, 0));
		assertEquals(seed, outcome.best().seed());
		assertEquals(Crack.Confidence.RANDOM_SEED_GUESS, outcome.best().confidence());
	}

	@Test
	void hashThatMatchesNothing() {
		// A server that hides the seed hash: the low 48 bits still come out, and a random seed is offered.
		long seed = new Random(100).nextLong();
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.of(12345),
			Chunks.present(seed, Surface.FLOOR, 2, 2), Chunks.present(seed, Surface.ROOF, 2, 2));
		assertEquals(seed, outcome.best().seed());
		assertEquals(Crack.Confidence.RANDOM_SEED_GUESS, outcome.best().confidence());
		assertTrue(outcome.complete());
	}

	/** Players broke most of the bedrock, including every block at y 4 (the search's best tests). */
	@Test
	void brokenBedrockDoesNotMatter() {
		long seed = 31337L;
		List<Observation> floor = Chunks.broken(Chunks.present(seed, Surface.FLOOR, 1, 1), 0.6, 1).stream()
			.filter(o -> o.y() != Surface.FLOOR.maxY)
			.toList();
		List<Observation> roof = Chunks.broken(Chunks.present(seed, Surface.ROOF, 1, 1), 0.9, 2);
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.of(SeedRecovery.obfuscate(seed)), floor, roof);
		assertEquals(new Crack.Found(seed & JavaLcg.MASK, seed, Crack.Confidence.CONFIRMED), outcome.best());
	}

	/** Bedrock placed in creative on the other surface is tolerated. */
	@Test
	void placedRoofBedrockIsTolerated() {
		long seed = 31337L;
		List<Observation> roof = Chunks.observe(seed, Surface.ROOF, 1, 1);
		int placed = 0;
		for (int i = 0; placed < 10; i += 37) {
			if (!roof.get(i).bedrock()) {
				roof = Chunks.tamper(roof, i);
				placed++;
			}
		}
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.of(SeedRecovery.obfuscate(seed)),
			Chunks.present(seed, Surface.FLOOR, 1, 1), Chunks.present(roof));
		assertEquals(seed, outcome.best().seed());
	}

	@Test
	void wrongOtherSurfaceRejectsTheKey() {
		long seed = 31337L;
		Crack.Outcome outcome = run(seed, Surface.FLOOR, OptionalLong.of(SeedRecovery.obfuscate(seed)),
			Chunks.present(seed, Surface.FLOOR, 1, 1), Chunks.present(seed + 1, Surface.ROOF, 1, 1));
		assertEquals(List.of(), outcome.found());
		assertTrue(outcome.complete());
	}

	@Test
	void cancel() throws InterruptedException {
		long seed = 1;
		Crack crack = new Crack(Surface.FLOOR, Chunks.present(seed, Surface.FLOOR, 0, 0), List.of(), OptionalLong.empty());
		crack.start(2);
		Thread.sleep(200);
		assertNull(crack.outcome());
		crack.cancel();
		Crack.Outcome outcome = await(crack);
		assertTrue(outcome.cancelled());
		assertTrue(!outcome.complete());
	}

	private static Crack.Outcome run(long seed, Surface primary, OptionalLong hash, List<Observation> primaryBlocks,
			List<Observation> secondaryBlocks) {
		long prefix = KeySearch.prefixOf(NetherBedrock.surfaceKeyOfWorld(seed, primary));
		long from = Math.max(0, prefix - WINDOW / 3);
		Crack crack = new Crack(primary, primaryBlocks, secondaryBlocks, hash, from, Math.min(KeySearch.PREFIXES, from + WINDOW));
		crack.start(4);
		Crack.Outcome outcome = await(crack);
		assertNull(outcome.error());
		return outcome;
	}

	private static Crack.Outcome await(Crack crack) {
		long deadline = System.currentTimeMillis() + 60_000;
		while (crack.outcome() == null) {
			assertTrue(System.currentTimeMillis() < deadline, "crack did not finish");
			Thread.onSpinWait();
		}
		Crack.Outcome outcome = crack.outcome();
		assertNotNull(outcome);
		return outcome;
	}
}
