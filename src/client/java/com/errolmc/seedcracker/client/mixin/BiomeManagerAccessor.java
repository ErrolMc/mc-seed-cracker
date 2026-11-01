package com.errolmc.seedcracker.client.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The seed hash the server sent on join ({@code CommonPlayerSpawnInfo.seed}, which is
 * {@code BiomeManager.obfuscateSeed(worldSeed)}). The client keeps it for biome blending.
 */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
	@Accessor("biomeZoomSeed")
	long seedCracker$biomeZoomSeed();
}
