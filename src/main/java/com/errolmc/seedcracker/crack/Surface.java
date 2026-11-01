package com.errolmc.seedcracker.crack;

/**
 * The two random bedrock layers of the Nether, as in {@code data/minecraft/worldgen/material_rule/bedrock_floor.json}
 * and {@code bedrock_roof.json} of 26.3 (Nether: min_y 0, generation height 128).
 *
 * <p>Both are {@code vertical_gradient} conditions: at or below {@code trueAtAndBelow} the condition always holds, at or
 * above {@code falseAtAndAbove} it never does, and in between it holds when the block's random float is below a
 * probability that falls from 1 to 0. The floor places bedrock where the condition holds, the roof where it doesn't.
 */
public enum Surface {
	FLOOR("minecraft:bedrock_floor", 0, 5, false),
	ROOF("minecraft:bedrock_roof", 122, 127, true);

	/** The {@code random_name}; its {@code String.hashCode()} is mixed into the surface's key. */
	public final String randomName;
	public final int trueAtAndBelow;
	public final int falseAtAndAbove;
	/** The roof wraps its gradient in a {@code minecraft:not}. */
	public final boolean inverted;
	/** Lowest and highest y where the outcome is random. */
	public final int minY;
	public final int maxY;
	/** The layer that is always bedrock (y 0 on the floor, y 127 on the roof). */
	public final int solidY;

	private final int[] thresholds;

	Surface(String randomName, int trueAtAndBelow, int falseAtAndAbove, boolean inverted) {
		this.randomName = randomName;
		this.trueAtAndBelow = trueAtAndBelow;
		this.falseAtAndAbove = falseAtAndAbove;
		this.inverted = inverted;
		this.minY = trueAtAndBelow + 1;
		this.maxY = falseAtAndAbove - 1;
		this.solidY = inverted ? falseAtAndAbove : trueAtAndBelow;
		this.thresholds = new int[falseAtAndAbove - trueAtAndBelow];
		for (int y = minY; y <= maxY; y++) {
			thresholds[y - trueAtAndBelow] = countBelow(probability(y));
		}
	}

	public int layers() {
		return maxY - minY + 1;
	}

	public boolean hasRandomLayer(int y) {
		return y >= minY && y <= maxY;
	}

	/**
	 * How many of the 2^24 values of {@code next(24)} make the gradient condition true at this y: it holds exactly when
	 * {@code next(24) < threshold(y)}.
	 */
	public int threshold(int y) {
		if (!hasRandomLayer(y)) {
			throw new IllegalArgumentException("y " + y + " is not a random bedrock layer of the " + this);
		}
		return thresholds[y - trueAtAndBelow];
	}

	/** {@code Mth.map(y, trueAtAndBelow, falseAtAndAbove, 1.0, 0.0)}, written out the same way. */
	private double probability(int y) {
		double alpha = ((double) y - trueAtAndBelow) / ((double) falseAtAndAbove - trueAtAndBelow);
		return 1.0 + alpha * (0.0 - 1.0);
	}

	/** Counts the n with {@code n * 5.9604645E-8F < probability}, the comparison vanilla makes with {@code nextFloat()}. */
	private static int countBelow(double probability) {
		int n = (int) Math.ceil(probability * (1 << 24));
		n = Math.max(0, Math.min(1 << 24, n));
		while (n > 0 && (n - 1) * 5.9604645E-8F >= probability) {
			n--;
		}
		while (n < 1 << 24 && n * 5.9604645E-8F < probability) {
			n++;
		}
		return n;
	}

	public Surface other() {
		return this == FLOOR ? ROOF : FLOOR;
	}
}
