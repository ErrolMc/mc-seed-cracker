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
 * <p>A surface is "intact" when its always-bedrock layer (y 0, y 127) is complete and, for the roof, nothing is built
 * above it. A gap there means someone has broken through, so that surface may have other broken blocks too.
 */
public record ChunkBedrock(int chunkX, int chunkZ, BitSet floor, BitSet roof, boolean floorIntact, boolean roofIntact) {
	public static final int BLOCKS_PER_LAYER = 16 * 16;

	static int index(Surface surface, int y, int localX, int localZ) {
		return (y - surface.minY) * BLOCKS_PER_LAYER + localZ * 16 + localX;
	}

	public boolean intact(Surface surface) {
		return surface == Surface.FLOOR ? floorIntact : roofIntact;
	}

	public BitSet bedrock(Surface surface) {
		return surface == Surface.FLOOR ? floor : roof;
	}

	public List<Observation> observations(Surface surface) {
		BitSet bits = bedrock(surface);
		List<Observation> observations = new ArrayList<>(surface.layers() * BLOCKS_PER_LAYER);
		for (int y = surface.minY; y <= surface.maxY; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					observations.add(new Observation(chunkX * 16 + x, y, chunkZ * 16 + z, bits.get(index(surface, y, x, z))));
				}
			}
		}
		return observations;
	}

	/** How many of this surface's random blocks differ from what the surface key predicts. */
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
