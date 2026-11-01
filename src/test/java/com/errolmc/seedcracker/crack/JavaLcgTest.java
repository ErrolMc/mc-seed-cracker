package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JavaLcgTest {
	@Test
	void inverseMultiplier() {
		assertEquals(1, JavaLcg.MULTIPLIER * JavaLcg.INVERSE_MULTIPLIER & JavaLcg.MASK);
	}

	@Test
	void nextLongMatchesJavaUtilRandom() {
		Random seeds = new Random(1);
		for (int i = 0; i < 1000; i++) {
			long seed = seeds.nextLong();
			assertEquals(new Random(seed).nextLong(), JavaLcg.nextLong(seed), "seed " + seed);
		}
	}

	@Test
	void stepAndBackAreInverse() {
		Random seeds = new Random(2);
		for (int i = 0; i < 1000; i++) {
			long state = seeds.nextLong() & JavaLcg.MASK;
			assertEquals(state, JavaLcg.back(JavaLcg.step(state)));
		}
	}

	@Test
	void inputsForNextLongFindTheSeed() {
		Random seeds = new Random(3);
		for (int i = 0; i < 200; i++) {
			long seed = seeds.nextLong() & JavaLcg.MASK;
			long output = JavaLcg.nextLong(seed) & JavaLcg.MASK;
			long[] inputs = JavaLcg.inputsForNextLong(output);
			assertTrue(Arrays.stream(inputs).anyMatch(input -> input == seed), "seed " + seed + " not in " + Arrays.toString(inputs));
			for (long input : inputs) {
				assertEquals(output, JavaLcg.nextLong(input) & JavaLcg.MASK, "wrong input " + input);
			}
		}
	}

	@Test
	void inputsForNextLongWithNegativeLowHalf() {
		// Seeds whose second int is negative: nextLong() borrows from the first int, the case that is easy to get wrong.
		Random seeds = new Random(4);
		int checked = 0;
		while (checked < 100) {
			long seed = seeds.nextLong() & JavaLcg.MASK;
			long output = JavaLcg.nextLong(seed);
			if ((int) output >= 0) {
				continue;
			}
			checked++;
			long[] inputs = JavaLcg.inputsForNextLong(output & JavaLcg.MASK);
			assertTrue(Arrays.stream(inputs).anyMatch(input -> input == seed), "seed " + seed);
		}
	}
}
