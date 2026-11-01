package com.errolmc.seedcracker.crack;

import java.util.Arrays;

/**
 * Java's 48-bit linear congruential generator, the one behind Minecraft's {@code LegacyRandomSource}, plus the step
 * that runs {@code nextLong()} backwards.
 *
 * <p>"Input seed" below means the value passed to {@code new LegacyRandomSource(seed)}; the generator's state is that
 * value XOR {@link #MULTIPLIER}, cut to 48 bits.
 */
public final class JavaLcg {
	public static final long MULTIPLIER = 0x5DEECE66DL;
	public static final long ADDEND = 0xBL;
	public static final long MASK = (1L << 48) - 1;
	/** The multiplier's inverse modulo 2^48, for stepping the generator backwards. */
	public static final long INVERSE_MULTIPLIER = inverse(MULTIPLIER);

	private JavaLcg() {
	}

	/** {@code new LegacyRandomSource(seed).nextLong()}. */
	public static long nextLong(long seed) {
		long state = scramble(seed);
		state = step(state);
		int high = (int) (state >>> 16);
		state = step(state);
		int low = (int) (state >>> 16);
		return ((long) high << 32) + low;
	}

	/**
	 * Every input seed (low 48 bits) whose first {@code nextLong()} has the given low 48 bits. There is usually one,
	 * sometimes none or two. The low 32 bits of the output are the top of the second state, so only its bottom 16 bits
	 * are unknown; each guess is stepped back and checked against the output's bits 32 to 47.
	 */
	public static long[] inputsForNextLong(long low48) {
		long low = low48 & 0xFFFF_FFFFL;
		// nextLong() adds the second int sign-extended, which borrows one from the first int when it is negative.
		long expectedHigh = ((low48 >>> 32) + ((int) low < 0 ? 1 : 0)) & 0xFFFF;
		long[] found = new long[4];
		int count = 0;
		for (long guess = 0; guess < 1 << 16; guess++) {
			long second = low << 16 | guess;
			long first = back(second);
			if ((first >>> 16 & 0xFFFF) == expectedHigh) {
				if (count == found.length) {
					found = Arrays.copyOf(found, count * 2);
				}
				found[count++] = scramble(back(first));
			}
		}
		return Arrays.copyOf(found, count);
	}

	/** The state {@code new LegacyRandomSource(seed)} starts in. Applying it twice gives the seed back. */
	public static long scramble(long seed) {
		return (seed ^ MULTIPLIER) & MASK;
	}

	public static long step(long state) {
		return state * MULTIPLIER + ADDEND & MASK;
	}

	public static long back(long state) {
		return (state - ADDEND) * INVERSE_MULTIPLIER & MASK;
	}

	private static long inverse(long odd) {
		// Newton's iteration: each round doubles the number of correct low bits.
		long x = odd;
		for (int i = 0; i < 5; i++) {
			x *= 2 - odd * x;
		}
		return x & MASK;
	}
}
