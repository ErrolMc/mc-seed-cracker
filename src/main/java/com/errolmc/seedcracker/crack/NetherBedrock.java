package com.errolmc.seedcracker.crack;

/**
 * Where vanilla puts Nether bedrock, given the world seed. This is the chain the cracker runs backwards:
 *
 * <pre>
 * world seed  --new LegacyRandomSource(seed).nextLong()-->  root key
 * root key ^ "minecraft:bedrock_floor".hashCode()  --nextLong()-->  floor key   (same for the roof)
 * block (x, y, z): new LegacyRandomSource(Mth.getSeed(x, y, z) ^ floor key).nextFloat() &lt; probability(y)
 * </pre>
 *
 * Every step only looks at the low 48 bits of its input, so the floor and roof keys depend only on the low 48 bits of
 * the world seed (the "structure seed").
 */
public final class NetherBedrock {
	private NetherBedrock() {
	}

	/** {@code RandomState}'s root positional key: {@code new LegacyRandomSource(worldSeed).forkPositional()}. */
	public static long rootKey(long worldSeed) {
		return JavaLcg.nextLong(worldSeed);
	}

	/** The surface's key ({@code getOrCreateRandomFactory(randomName)}), low 48 bits. */
	public static long surfaceKey(long rootKey, Surface surface) {
		return JavaLcg.nextLong(surface.randomName.hashCode() ^ rootKey) & JavaLcg.MASK;
	}

	public static long surfaceKeyOfWorld(long worldSeed, Surface surface) {
		return surfaceKey(rootKey(worldSeed), surface);
	}

	/** {@code Mth.getSeed(x, y, z)}. */
	public static long positionSeed(int x, int y, int z) {
		long seed = x * 3129871 ^ z * 116129781L ^ y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}

	/** What {@code next(24)} returns for this block, the value behind its {@code nextFloat()}. */
	public static int roll(long surfaceKey, int x, int y, int z) {
		long state = JavaLcg.step(JavaLcg.scramble(positionSeed(x, y, z) ^ surfaceKey));
		return (int) (state >>> 24);
	}

	public static boolean isBedrock(long surfaceKey, Surface surface, int x, int y, int z) {
		if (y <= surface.trueAtAndBelow) {
			return !surface.inverted;
		}
		if (y >= surface.falseAtAndAbove) {
			return surface.inverted;
		}
		boolean condition = roll(surfaceKey, x, y, z) < surface.threshold(y);
		return condition != surface.inverted;
	}
}
