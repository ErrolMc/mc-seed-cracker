package com.errolmc.seedcracker.crack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongPredicate;

/** From a surface key back to the world seed. */
public final class SeedRecovery {
	private SeedRecovery() {
	}

	/** The low 48 bits of every root key that gives this surface key. */
	public static long[] rootKeys(long surfaceKey, Surface surface) {
		long[] inputs = JavaLcg.inputsForNextLong(surfaceKey);
		for (int i = 0; i < inputs.length; i++) {
			inputs[i] = (inputs[i] ^ surface.randomName.hashCode()) & JavaLcg.MASK;
		}
		return inputs;
	}

	/** Structure seeds (the low 48 bits of a world seed) behind a surface key, keeping only root keys that pass the filter. */
	public static List<Long> structureSeeds(long surfaceKey, Surface surface, LongPredicate rootKeyFilter) {
		List<Long> seeds = new ArrayList<>();
		for (long rootKey : rootKeys(surfaceKey, surface)) {
			if (rootKeyFilter.test(rootKey)) {
				for (long seed : JavaLcg.inputsForNextLong(rootKey)) {
					seeds.add(seed);
				}
			}
		}
		return seeds;
	}

	/**
	 * The world seed with these low 48 bits whose hash is the one the server sent ({@code BiomeManager.obfuscateSeed}),
	 * found by trying all 65536 values of the top 16 bits. Covers every seed, typed or random.
	 */
	public static OptionalLong worldSeedFromHash(long structureSeed, long hashedSeed) {
		SeedHasher hasher = new SeedHasher();
		for (long top = 0; top < 1 << 16; top++) {
			long seed = top << 48 | structureSeed & JavaLcg.MASK;
			if (hasher.hash(seed) == hashedSeed) {
				return OptionalLong.of(seed);
			}
		}
		return OptionalLong.empty();
	}

	/**
	 * The world seeds with these low 48 bits that "Create World" could have picked at random: those are
	 * {@code RandomSource.create().nextLong()}, so they are the output of the same generator. Usually one.
	 */
	public static long[] randomWorldSeeds(long structureSeed) {
		long[] inputs = JavaLcg.inputsForNextLong(structureSeed);
		for (int i = 0; i < inputs.length; i++) {
			inputs[i] = JavaLcg.nextLong(inputs[i]);
		}
		return inputs;
	}

	/** {@code BiomeManager.obfuscateSeed}: the first 8 bytes of SHA-256 of the seed, little-endian both ways. */
	public static long obfuscate(long seed) {
		return new SeedHasher().hash(seed);
	}

	private static final class SeedHasher {
		private final MessageDigest sha256;
		private final ByteBuffer input = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);

		SeedHasher() {
			try {
				sha256 = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("every Java runtime has SHA-256", e);
			}
		}

		long hash(long seed) {
			input.clear();
			input.putLong(seed);
			byte[] digest = sha256.digest(input.array());
			return ByteBuffer.wrap(digest, 0, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).getLong();
		}
	}
}
