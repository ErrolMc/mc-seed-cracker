package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class OverworldCheckTest {
	private static final long SEED = 607;

	private static List<OverworldCheck.Chunk> area(long seed, int size) {
		List<OverworldCheck.Chunk> chunks = new ArrayList<>();
		for (int chunkX = -size / 2; chunkX < size - size / 2; chunkX++) {
			for (int chunkZ = -size / 2; chunkZ < size - size / 2; chunkZ++) {
				chunks.add(OverworldCheck.generate(seed, chunkX, chunkZ));
			}
		}
		return chunks;
	}

	@Test
	void theRightSeedFitsEveryChunk() {
		OverworldCheck.Report report = OverworldCheck.check(SEED, area(SEED, 5));
		assertEquals(new OverworldCheck.Report(25, 25, 0, 0), report);
		assertTrue(report.matches());
	}

	/** The seeds the Nether can't tell apart from the right one: none of them passes, not even on 9 chunks. */
	@Test
	void noOtherSeedWithTheSameNetherBedrockMatches() {
		List<OverworldCheck.Chunk> chunks = area(SEED, 3);
		for (long top = 1; top < 1 << 16; top++) {
			long other = SEED ^ top << 48;
			OverworldCheck.Report report = OverworldCheck.check(other, chunks);
			assertFalse(report.matches(), "seed " + other + " matches: " + report);
		}
	}

	@Test
	void randomSeedsDontMatch() {
		List<OverworldCheck.Chunk> chunks = area(SEED, 3);
		SplittableRandom random = new SplittableRandom(3);
		for (int i = 0; i < 20_000; i++) {
			long other = random.nextLong();
			assertFalse(OverworldCheck.check(other, chunks).matches(), "seed " + other);
		}
	}

	/** Caves and lava take some bedrock away; a chunk changed beyond that is outvoted by the others. */
	@Test
	void toleratesMissingBedrockAndAFewChangedChunks() {
		List<OverworldCheck.Chunk> chunks = area(SEED, 5);
		for (OverworldCheck.Chunk chunk : chunks) {
			BitSet bedrock = chunk.bedrock();
			int n = 0;
			for (int bit = bedrock.nextSetBit(0); bit >= 0; bit = bedrock.nextSetBit(bit + 1)) {
				if (n++ % 20 == 0) {
					bedrock.clear(bit);
				}
			}
		}
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		assertEquals(25, report.fitting());
		assertEquals(0, report.extra());
		assertTrue(report.missing() > 0);

		// Bedrock placed where the seed has none, in two chunks.
		for (int i = 0; i < 2; i++) {
			OverworldCheck.Chunk chunk = chunks.get(i);
			chunk.bedrock().set(OverworldCheck.generate(SEED, chunk.chunkX(), chunk.chunkZ()).bedrock().nextClearBit(0));
		}
		report = OverworldCheck.check(SEED, chunks);
		assertEquals(23, report.fitting());
		assertEquals(2, report.extra());
		assertTrue(report.matches());

		// Bedrock broken in a chunk: well over a tenth missing.
		chunks.get(2).bedrock().clear(0, OverworldCheck.BLOCKS / 2);
		report = OverworldCheck.check(SEED, chunks);
		assertEquals(22, report.fitting());
		assertFalse(report.matches());
	}

	/** A superflat world: bedrock at y -64 only. */
	@Test
	void noRandomBedrockDoesNotMatch() {
		List<OverworldCheck.Chunk> chunks = List.of(new OverworldCheck.Chunk(0, 0, new BitSet()), new OverworldCheck.Chunk(1, 0, new BitSet()));
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		assertEquals(0, report.fitting());
		assertFalse(report.matches());
	}

	@Test
	void noChunksDoesNotMatch() {
		assertFalse(OverworldCheck.check(SEED, List.of()).matches());
	}
}
