package com.errolmc.seedcracker.crack;

import java.util.ArrayList;
import java.util.List;

/** Test data: what a chunk's bedrock layers look like in a world with a given seed. */
final class Chunks {
	private Chunks() {
	}

	static List<Observation> observe(long worldSeed, Surface surface, int chunkX, int chunkZ) {
		long key = NetherBedrock.surfaceKeyOfWorld(worldSeed, surface);
		List<Observation> observations = new ArrayList<>();
		for (int y = surface.minY; y <= surface.maxY; y++) {
			for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
				for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
					observations.add(new Observation(x, y, z, NetherBedrock.isBedrock(key, surface, x, y, z)));
				}
			}
		}
		return observations;
	}

	/** The same blocks with one outcome flipped, as if a player had broken or placed that bedrock. */
	static List<Observation> tamper(List<Observation> observations, int index) {
		List<Observation> copy = new ArrayList<>(observations);
		Observation o = copy.get(index);
		copy.set(index, new Observation(o.x(), o.y(), o.z(), !o.bedrock()));
		return copy;
	}
}
