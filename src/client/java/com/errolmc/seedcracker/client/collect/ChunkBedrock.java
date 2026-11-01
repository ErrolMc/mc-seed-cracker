package com.errolmc.seedcracker.client.collect;

import com.errolmc.seedcracker.crack.NetherBedrock;
import com.errolmc.seedcracker.crack.Observation;
import com.errolmc.seedcracker.crack.Surface;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The random bedrock layers of one Nether chunk: which of the 4 x 256 floor and roof blocks are bedrock.
 *
 * <p>Only the bedrock that is there counts as evidence. Survival players can break bedrock (pistons and TNT, mostly to
 * get onto the roof) but nothing in survival places it, so a bedrock block is always where generation put it, while a
 * block without bedrock may have had it broken.
 */
public record ChunkBedrock(int chunkX, int chunkZ, BitSet floor, BitSet roof) {
	public static final int BLOCKS_PER_LAYER = 16 * 16;

	static int index(Surface surface, int y, int localX, int localZ) {
		return (y - surface.minY) * BLOCKS_PER_LAYER + localZ * 16 + localX;
	}

	public BitSet bedrock(Surface surface) {
		return surface == Surface.FLOOR ? floor : roof;
	}

	public int bedrockCount(Surface surface) {
		return bedrock(surface).cardinality();
	}

	/** The surface's bedrock blocks, the ones that can be trusted. */
	public List<Observation> observations(Surface surface) {
		BitSet bits = bedrock(surface);
		List<Observation> observations = new ArrayList<>(bits.cardinality());
		for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
			int y = surface.minY + i / BLOCKS_PER_LAYER;
			int local = i % BLOCKS_PER_LAYER;
			observations.add(new Observation(chunkX * 16 + local % 16, y, chunkZ * 16 + local / 16, true));
		}
		return observations;
	}

	/** How many of this surface's bedrock blocks the surface key doesn't place: 0 for the right key. */
	public int misplaced(Surface surface, long surfaceKey) {
		int wrong = 0;
		for (Observation observation : observations(surface)) {
			if (!observation.matches(surfaceKey, surface)) {
				wrong++;
			}
		}
		return wrong;
	}

	/** How many of this surface's random blocks differ from what the surface key predicts, both ways. */
	public int mismatches(Surface surface, long surfaceKey) {
		BitSet bits = bedrock(surface);
		int wrong = 0;
		for (int y = surface.minY; y <= surface.maxY; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					boolean expected = NetherBedrock.isBedrock(surfaceKey, surface, chunkX * 16 + x, y, chunkZ * 16 + z);
					if (expected != bits.get(index(surface, y, x, z))) {
						wrong++;
					}
				}
			}
		}
		return wrong;
	}

	public long distanceSquared(int otherChunkX, int otherChunkZ) {
		long dx = chunkX - otherChunkX;
		long dz = chunkZ - otherChunkZ;
		return dx * dx + dz * dz;
	}
}
