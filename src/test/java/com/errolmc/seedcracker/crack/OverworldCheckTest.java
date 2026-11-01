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

	/** Breaks a share of each chunk's bedrock at random. */
	private static void breakBedrock(List<OverworldCheck.Chunk> chunks, double share) {
		SplittableRandom random = new SplittableRandom(11);
		for (OverworldCheck.Chunk chunk : chunks) {
			BitSet bedrock = chunk.bedrock();
			for (int bit = bedrock.nextSetBit(0); bit >= 0; bit = bedrock.nextSetBit(bit + 1)) {
				if (random.nextDouble() < share) {
					bedrock.clear(bit);
				}
			}
		}
	}

	@Test
	void theRightSeedFitsEveryChunk() {
		OverworldCheck.Report report = OverworldCheck.check(SEED, area(SEED, 5));
		assertEquals(new OverworldCheck.Report(25, 25, 25, 0, 0), report);
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

	/** The same with 40% of the bedrock broken, not far above the least a chunk may have left and still count. */
	@Test
	void noOtherSeedWithTheSameNetherBedrockMatchesBrokenBedrock() {
		List<OverworldCheck.Chunk> chunks = area(SEED, 3);
		breakBedrock(chunks, 0.4);
		assertEquals(9, OverworldCheck.check(SEED, chunks).usable());
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

	/** Caves, lava and players take bedrock away, which never counts against a seed. */
	@Test
	void brokenBedrockDoesNotMatter() {
		List<OverworldCheck.Chunk> chunks = area(SEED, 5);
		breakBedrock(chunks, 0.3);
		// The top layer gone in one chunk, and nearly all of another chunk (too little left to count).
		chunks.get(0).bedrock().clear(OverworldCheck.index(OverworldBedrock.MAX_Y, 0, 0), OverworldCheck.BLOCKS);
		chunks.get(1).bedrock().clear(0, OverworldCheck.BLOCKS - 100);
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		assertEquals(24, report.usable());
		assertEquals(24, report.fitting());
		assertEquals(0, report.extra());
		assertTrue(report.missing() > 0);
		assertTrue(report.matches());
	}

	/** Bedrock placed where the seed has none (creative) in a few chunks: they're outvoted, up to a tenth. */
	@Test
	void toleratesAFewChunksWithPlacedBedrock() {
		List<OverworldCheck.Chunk> chunks = area(SEED, 5);
		for (int i = 0; i < 2; i++) {
			placeBedrock(chunks.get(i));
		}
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		assertEquals(23, report.fitting());
		assertEquals(2, report.extra());
		assertTrue(report.matches());

		placeBedrock(chunks.get(2));
		report = OverworldCheck.check(SEED, chunks);
		assertEquals(22, report.fitting());
		assertFalse(report.matches());
	}

	private static void placeBedrock(OverworldCheck.Chunk chunk) {
		chunk.bedrock().set(OverworldCheck.generate(SEED, chunk.chunkX(), chunk.chunkZ()).bedrock().nextClearBit(0));
	}

	/** A superflat world: bedrock at y -64 only, so no chunk counts. */
	@Test
	void noRandomBedrockDoesNotMatch() {
		List<OverworldCheck.Chunk> chunks = List.of(new OverworldCheck.Chunk(0, 0, new BitSet()), new OverworldCheck.Chunk(1, 0, new BitSet()));
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		assertEquals(new OverworldCheck.Report(2, 0, 0, 0, 0), report);
		assertFalse(report.matches());
	}

	@Test
	void noChunksDoesNotMatch() {
		assertFalse(OverworldCheck.check(SEED, List.of()).matches());
	}
}
