package com.errolmc.seedcracker.crack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Where vanilla puts the Overworld's bedrock floor, given the world seed. Used to check a seed, not to find one: the
 * Overworld uses Xoroshiro128++ ({@code legacy_random_source: false}), seeded from all 64 bits of the world seed, so
 * running it forwards is cheap but there is no way to run it backwards.
 *
 * <pre>
 * world seed  --new XoroshiroRandomSource(seed).forkPositional()-->  root (lo, hi)
 * root ^ md5("minecraft:bedrock_floor")  --forkPositional()-->  floor key (lo, hi)
 * block (x, y, z): new XoroshiroRandomSource(Mth.getSeed(x, y, z) ^ lo, hi).nextFloat() &lt; probability(y)
 * </pre>
 *
 * The rule is the same {@code bedrock_floor.json} as the Nether's (true at y &lt;= bottom, false at y &gt;= bottom + 5),
 * on an Overworld with min_y -64 (all vanilla presets: normal, large biomes, amplified).
 */
public final class OverworldBedrock {
	public static final int SOLID_Y = -64;
	public static final int MIN_Y = SOLID_Y + 1;
	public static final int MAX_Y = SOLID_Y + 4;
	public static final int LAYERS = MAX_Y - MIN_Y + 1;

	private static final long GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15L;
	private static final long SILVER_RATIO_64 = 0x6A09E667F3BCC909L;
	/** {@code RandomSupport.seedFromHashOf("minecraft:bedrock_floor")}: MD5, read as two big-endian longs. */
	private static final long NAME_LO;
	private static final long NAME_HI;

	static {
		try {
			ByteBuffer md5 = ByteBuffer.wrap(MessageDigest.getInstance("MD5").digest(Surface.FLOOR.randomName.getBytes(StandardCharsets.UTF_8)));
			NAME_LO = md5.getLong();
			NAME_HI = md5.getLong();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private OverworldBedrock() {
	}

	/** The floor's positional random factory, {@code getOrCreateRandomFactory("minecraft:bedrock_floor")}. */
	public record Key(long lo, long hi) {
	}

	public static Key floorKey(long worldSeed) {
		// RandomSupport.upgradeSeedTo128bit, then RandomState's forkPositional().
		long lo = worldSeed ^ SILVER_RATIO_64;
		Xoroshiro root = new Xoroshiro(mixStafford13(lo), mixStafford13(lo + GOLDEN_RATIO_64));
		long rootLo = root.nextLong();
		long rootHi = root.nextLong();
		// fromHashOf(name).forkPositional()
		Xoroshiro floor = new Xoroshiro(NAME_LO ^ rootLo, NAME_HI ^ rootHi);
		long keyLo = floor.nextLong();
		return new Key(keyLo, floor.nextLong());
	}

	public static boolean isBedrock(Key key, int x, int y, int z) {
		if (y <= SOLID_Y) {
			return true;
		}
		if (y > MAX_Y) {
			return false;
		}
		Xoroshiro random = new Xoroshiro(NetherBedrock.positionSeed(x, y, z) ^ key.lo(), key.hi());
		// nextFloat() is nextBits(24) * 2^-24, the same float comparison as the Nether floor's, at the same height
		// above the bottom.
		return (int) (random.nextLong() >>> 40) < Surface.FLOOR.threshold(y - SOLID_Y);
	}

	private static long mixStafford13(long z) {
		z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
		z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
		return z ^ z >>> 31;
	}

	/** {@code Xoroshiro128PlusPlus}. */
	private static final class Xoroshiro {
		private long lo;
		private long hi;

		Xoroshiro(long lo, long hi) {
			if ((lo | hi) == 0) {
				lo = GOLDEN_RATIO_64;
				hi = SILVER_RATIO_64;
			}
			this.lo = lo;
			this.hi = hi;
		}

		long nextLong() {
			long s0 = lo;
			long s1 = hi;
			long result = Long.rotateLeft(s0 + s1, 17) + s0;
			s1 ^= s0;
			lo = Long.rotateLeft(s0, 49) ^ s1 ^ s1 << 21;
			hi = Long.rotateLeft(s1, 28);
			return result;
		}
	}
}
