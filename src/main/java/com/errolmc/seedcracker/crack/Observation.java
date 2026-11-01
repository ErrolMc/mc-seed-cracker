package com.errolmc.seedcracker.crack;

/** One block in a random bedrock layer: bedrock or not. */
public record Observation(int x, int y, int z, boolean bedrock) {
	public boolean matches(long surfaceKey, Surface surface) {
		return NetherBedrock.isBedrock(surfaceKey, surface, x, y, z) == bedrock;
	}

	/** Bits of information this block gives about the key: rarer outcomes (bedrock at y 4, none at y 1) say more. */
	public double information(Surface surface) {
		double chance = surface.threshold(y) / (double) (1 << 24);
		boolean conditionHolds = bedrock != surface.inverted;
		return -Math.log(conditionHolds ? chance : 1 - chance) / Math.log(2);
	}
}
