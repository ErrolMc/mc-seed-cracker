package com.errolmc.seedcracker.crack;

import java.util.BitSet;
import java.util.Collection;

/**
 * Checks a whole seed against the Overworld's bedrock floor. The Nether only pins down the low 48 bits; the Overworld
 * floor depends on all 64, so this tells apart even seeds that share their Nether bedrock.
 *
 * <p>Generation can take floor bedrock away (a cave or lava lake cutting into it) but nothing puts bedrock where the
 * rule didn't, and survival players can't either. So a chunk fits a seed when it has no bedrock this seed doesn't
 * place, and no more than a tenth of what it does place is missing.
 *
 * <p>Because Xoroshiro's first output mixes its seed weakly, a few wrong seeds come close: the worst of all 65,535
 * seeds with 607's low 48 bits differs from it in about 2% of the blocks. But it still puts misplaced bedrock in every
 * chunk (1 to 34 blocks in each of 25 chunks tried), so it fits none of them, while the right seed fits all.
 */
public final class OverworldCheck {
	/** Share of read chunks that must fit; the rest may have been changed by creative players. */
	private static final double MATCHING_SHARE = 0.9;
	private static final int BLOCKS_PER_LAYER = 16 * 16;
	public static final int BLOCKS = OverworldBedrock.LAYERS * BLOCKS_PER_LAYER;

	private OverworldCheck() {
	}

	/** One Overworld chunk's random floor layers (y -63 to -60) as read: which blocks are bedrock. */
	public record Chunk(int chunkX, int chunkZ, BitSet bedrock) {
	}

	/** {@code extra}: bedrock where the seed puts none; {@code missing}: the seed's bedrock that isn't there. */
	public record Report(int chunks, int fitting, int extra, int missing) {
		public boolean matches() {
			return chunks > 0 && fitting >= Math.ceil(chunks * MATCHING_SHARE);
		}
	}

	public static int index(int y, int localX, int localZ) {
		return (y - OverworldBedrock.MIN_Y) * BLOCKS_PER_LAYER + localZ * 16 + localX;
	}

	public static Report check(long seed, Collection<Chunk> chunks) {
		OverworldBedrock.Key key = OverworldBedrock.floorKey(seed);
		int fitting = 0;
		int extra = 0;
		int missing = 0;
		for (Chunk chunk : chunks) {
			int chunkExtra = 0;
			int chunkMissing = 0;
			int placed = 0;
			for (int y = OverworldBedrock.MIN_Y; y <= OverworldBedrock.MAX_Y; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						boolean expected = OverworldBedrock.isBedrock(key, chunk.chunkX() * 16 + x, y, chunk.chunkZ() * 16 + z);
						boolean actual = chunk.bedrock().get(index(y, x, z));
						if (expected) {
							placed++;
							if (!actual) {
								chunkMissing++;
							}
						} else if (actual) {
							chunkExtra++;
						}
					}
				}
			}
			if (chunkExtra == 0 && chunkMissing * 10 <= placed) {
				fitting++;
			}
			extra += chunkExtra;
			missing += chunkMissing;
		}
		return new Report(chunks.size(), fitting, extra, missing);
	}

	/** The floor this seed generates, as {@link #check} expects to read it. */
	public static Chunk generate(long seed, int chunkX, int chunkZ) {
		OverworldBedrock.Key key = OverworldBedrock.floorKey(seed);
		BitSet bedrock = new BitSet(BLOCKS);
		for (int y = OverworldBedrock.MIN_Y; y <= OverworldBedrock.MAX_Y; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (OverworldBedrock.isBedrock(key, chunkX * 16 + x, y, chunkZ * 16 + z)) {
						bedrock.set(index(y, x, z));
					}
				}
			}
		}
		return new Chunk(chunkX, chunkZ, bedrock);
	}
}
