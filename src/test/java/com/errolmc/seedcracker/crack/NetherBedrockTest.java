package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Known values from 19MisterX98's Nether_Bedrock_Cracker tests (a real world, seed 765906787396911863), which the
 * game-side chain in {@link NetherBedrock} has to reproduce.
 */
class NetherBedrockTest {
	static final long WORLD_SEED = 765906787396911863L;
	static final long ROOF_KEY = 191924403737289L;
	static final long FLOOR_KEY = 18240473916414L;

	/** Blocks seen in that world: x, y, z, bedrock. */
	static final Observation[] BLOCKS = {
		new Observation(18, 123, -117, false),
		new Observation(18, 123, -118, false),
		new Observation(18, 123, -119, false),
		new Observation(33, 126, -99, false),
		new Observation(35, 126, -99, false),
		new Observation(38, 126, -99, false),
		new Observation(19, 123, -117, true),
		new Observation(19, 123, -118, true),
		new Observation(19, 123, -119, true),
		new Observation(25, 126, -112, true),
		new Observation(25, 126, -113, true),
		new Observation(25, 126, -114, true),
		new Observation(11, 1, -111, false),
		new Observation(11, 1, -110, false),
		new Observation(11, 1, -109, false),
		new Observation(14, 4, -97, false),
		new Observation(14, 4, -96, false),
		new Observation(14, 4, -94, false),
		new Observation(10, 1, -111, true),
		new Observation(10, 1, -110, true),
		new Observation(10, 1, -109, true),
		new Observation(11, 4, -97, true),
		new Observation(11, 4, -96, true),
		new Observation(11, 4, -94, true),
	};

	@Test
	void surfaceKeysOfAKnownWorld() {
		assertEquals(ROOF_KEY, NetherBedrock.surfaceKeyOfWorld(WORLD_SEED, Surface.ROOF));
		assertEquals(FLOOR_KEY, NetherBedrock.surfaceKeyOfWorld(WORLD_SEED, Surface.FLOOR));
	}

	@Test
	void randomNameHashes() {
		assertEquals(2042456806, Surface.FLOOR.randomName.hashCode());
		assertEquals(343340730, Surface.ROOF.randomName.hashCode());
	}

	@Test
	void positionSeed() {
		assertEquals(99261249361405L, NetherBedrock.positionSeed(-98, 4, -469) & JavaLcg.MASK);
	}

	@Test
	void blocksOfAKnownWorld() {
		for (Observation block : BLOCKS) {
			Surface surface = block.y() > 64 ? Surface.ROOF : Surface.FLOOR;
			long key = surface == Surface.ROOF ? ROOF_KEY : FLOOR_KEY;
			assertTrue(block.matches(key, surface), block.toString());
		}
	}

	@Test
	void thresholds() {
		// nextFloat() < 0.8, 0.6, 0.4, 0.2: the counts of next(24) values below 2^24 times each.
		assertEquals(13421773, Surface.FLOOR.threshold(1));
		assertEquals(10066330, Surface.FLOOR.threshold(2));
		assertEquals(6710887, Surface.FLOOR.threshold(3));
		assertEquals(3355444, Surface.FLOOR.threshold(4));
		assertEquals(13421773, Surface.ROOF.threshold(123));
		assertEquals(3355444, Surface.ROOF.threshold(126));
	}

	@Test
	void layers() {
		assertEquals(1, Surface.FLOOR.minY);
		assertEquals(4, Surface.FLOOR.maxY);
		assertEquals(0, Surface.FLOOR.solidY);
		assertEquals(123, Surface.ROOF.minY);
		assertEquals(126, Surface.ROOF.maxY);
		assertEquals(127, Surface.ROOF.solidY);
		assertTrue(NetherBedrock.isBedrock(FLOOR_KEY, Surface.FLOOR, 5, 0, 5));
		assertTrue(!NetherBedrock.isBedrock(FLOOR_KEY, Surface.FLOOR, 5, 5, 5));
		assertTrue(NetherBedrock.isBedrock(ROOF_KEY, Surface.ROOF, 5, 127, 5));
		assertTrue(!NetherBedrock.isBedrock(ROOF_KEY, Surface.ROOF, 5, 122, 5));
	}

	@Test
	void bedrockGetsRarerTowardsTheMiddle() {
		long key = NetherBedrock.surfaceKeyOfWorld(42, Surface.FLOOR);
		int[] count = new int[5];
		for (int x = 0; x < 200; x++) {
			for (int z = 0; z < 200; z++) {
				for (int y = 1; y <= 4; y++) {
					if (NetherBedrock.isBedrock(key, Surface.FLOOR, x, y, z)) {
						count[y]++;
					}
				}
			}
		}
		double total = 200 * 200;
		assertEquals(0.8, count[1] / total, 0.01);
		assertEquals(0.6, count[2] / total, 0.01);
		assertEquals(0.4, count[3] / total, 0.01);
		assertEquals(0.2, count[4] / total, 0.01);
	}
}
