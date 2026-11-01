package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

/**
 * {@link OverworldBedrock} against the game's own classes, built up the way {@code RandomState} and
 * {@code VerticalGradientCondition} do it for the Overworld (the game test checks it against real chunks too).
 */
class OverworldBedrockTest {
	/** {@code RandomState.getOrCreateRandomFactory("minecraft:bedrock_floor")} for a Xoroshiro world. */
	private static PositionalRandomFactory gameFloor(long seed) {
		return new XoroshiroRandomSource(seed).forkPositional().fromHashOf(Surface.FLOOR.randomName).forkPositional();
	}

	private static boolean gameIsBedrock(PositionalRandomFactory floor, int x, int y, int z) {
		int trueAtAndBelow = OverworldBedrock.SOLID_Y;
		int falseAtAndAbove = OverworldBedrock.SOLID_Y + 5;
		if (y <= trueAtAndBelow) {
			return true;
		}
		if (y >= falseAtAndAbove) {
			return false;
		}
		return floor.at(x, y, z).nextFloat() < Mth.map(y, trueAtAndBelow, falseAtAndAbove, 1.0, 0.0);
	}

	@Test
	void matchesTheGame() {
		SplittableRandom random = new SplittableRandom(1);
		long[] seeds = {0, 1, -1, 607, Long.MIN_VALUE, Long.MAX_VALUE, random.nextLong(), random.nextLong(), random.nextLong()};
		for (long seed : seeds) {
			PositionalRandomFactory floor = gameFloor(seed);
			OverworldBedrock.Key key = OverworldBedrock.floorKey(seed);
			for (int i = 0; i < 4000; i++) {
				int x = random.nextInt(-30_000_000, 30_000_000);
				int z = random.nextInt(-30_000_000, 30_000_000);
				int y = random.nextInt(OverworldBedrock.SOLID_Y - 1, OverworldBedrock.MAX_Y + 3);
				assertEquals(gameIsBedrock(floor, x, y, z), OverworldBedrock.isBedrock(key, x, y, z),
					"seed " + seed + " at " + x + " " + y + " " + z);
			}
		}
	}

	@Test
	void layers() {
		OverworldBedrock.Key key = OverworldBedrock.floorKey(607);
		assertTrue(OverworldBedrock.isBedrock(key, 5, -64, 9));
		assertFalse(OverworldBedrock.isBedrock(key, 5, -59, 9));
		assertEquals(4, OverworldBedrock.LAYERS);
	}
}
