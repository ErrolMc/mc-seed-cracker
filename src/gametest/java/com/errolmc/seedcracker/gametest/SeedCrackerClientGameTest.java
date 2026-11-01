package com.errolmc.seedcracker.gametest;

import com.errolmc.seedcracker.client.collect.BedrockCollector;
import com.errolmc.seedcracker.client.collect.ChunkBedrock;
import com.errolmc.seedcracker.client.config.CrackerConfig;
import com.errolmc.seedcracker.client.config.ModMenuIntegration;
import com.errolmc.seedcracker.client.crack.CrackController;
import com.errolmc.seedcracker.client.crack.KnownSeeds;
import com.errolmc.seedcracker.client.mixin.BiomeManagerAccessor;
import com.errolmc.seedcracker.crack.Crack;
import com.errolmc.seedcracker.crack.NetherBedrock;
import com.errolmc.seedcracker.crack.OverworldCheck;
import com.errolmc.seedcracker.crack.SeedRecovery;
import com.errolmc.seedcracker.crack.Surface;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a world with a known seed, goes to the Nether, checks the mod doesn't start by itself, then cracks the seed
 * with {@code /seedcracker start}, as in normal play.
 *
 * <p>The seed is picked so its floor key is near the start of the search (about 0.05% in), so the real, unrestricted
 * search finds it in a second or two. Before that, every bedrock block the client received is checked against the
 * models in {@code NetherBedrock} and {@code OverworldBedrock}, which tests both seed-to-bedrock chains against the real
 * game.
 */
public class SeedCrackerClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("seed-cracker-gametest");
	private static final long SEED = 607;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(MuteAudio::mute);
		forgetKnownSeeds();
		context.runOnClient(minecraft -> {
			CrackerConfig defaults = new CrackerConfig();
			CrackerConfig config = CrackerConfig.get();
			config.showProgress = defaults.showProgress;
			config.threads = defaults.threads;
		});
		// The test worlds are superflat unless told otherwise; the Overworld check needs real terrain.
		try (TestSingleplayerContext world = context.worldBuilder()
				.adjustSettings(settings -> {
					settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(settings.getSettings().worldgenLoadContext()
						.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)));
					settings.setGenerateStructures(true);
					settings.setSeed(Long.toString(SEED));
				})
				.create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode creative @a");

			long hash = context.computeOnClient(minecraft -> ((BiomeManagerAccessor) minecraft.level.getBiomeManager()).seedCracker$biomeZoomSeed());
			check(hash == SeedRecovery.obfuscate(SEED), "seed hash from the server is not obfuscateSeed(" + SEED + ")");
			check(context.computeOnClient(minecraft -> BedrockCollector.count()) == 0, "read chunks outside the Nether");
			overworldMatchesTheModel(context);
			sendCommand(context, "seedcracker check");
			sendCommand(context, "seedcracker start");
			check(context.computeOnClient(minecraft -> CrackController.state()) == CrackController.State.WAITING, "started with no Nether chunks");

			world.getServer().runCommand("execute in minecraft:the_nether run tp @a 8.5 64 8.5");
			context.waitFor(minecraft -> minecraft.level.dimension() == Level.NETHER, 200);
			world.getConnection().waitForChunksRender();
			context.waitTicks(10);
			bedrockMatchesTheModel(context);

			// Ten seconds in the Nether with every chunk in: only the command may start it.
			context.waitTicks(200);
			check(context.computeOnClient(minecraft -> CrackController.state()) == CrackController.State.WAITING, "started by itself");
			sendCommand(context, "seedcracker start");
			int ticks = context.waitFor(minecraft -> CrackController.state() != CrackController.State.WAITING
				&& CrackController.state() != CrackController.State.RUNNING, 20 * 120);
			LOGGER.info("cracked {} ticks after /seedcracker start", ticks);
			CrackController.Result result = context.computeOnClient(minecraft -> CrackController.result());
			check(context.computeOnClient(minecraft -> CrackController.state()) == CrackController.State.FOUND, "no seed found");
			check(result.seed() == SEED, "found " + result.seed() + " instead of " + SEED);
			check(result.confidence() == Crack.Confidence.CONFIRMED, "not confirmed by the hash: " + result.confidence());
			check(!result.remembered(), "came from the known seeds file, not from cracking");
			check(KnownSeeds.forHash(hash).isPresent(), "seed not remembered");
			check(context.computeOnClient(minecraft -> BedrockCollector.overworldCount()) >= 25, "Overworld chunks lost on the way to the Nether");
			context.takeScreenshot("found");

			commands(context);
			configScreen(context);
			check(context.computeOnClient(minecraft -> minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).get()) == 0.0,
				"test client is not muted");
		}
	}

	/** Every intact floor and roof the client read is exactly what the seed predicts. */
	private static void bedrockMatchesTheModel(ClientGameTestContext context) {
		List<ChunkBedrock> chunks = context.computeOnClient(minecraft -> BedrockCollector.chunks());
		check(chunks.size() >= 25, "only " + chunks.size() + " Nether chunks read");
		long floorKey = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.FLOOR);
		long roofKey = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.ROOF);
		int bedrock = 0;
		for (ChunkBedrock chunk : chunks) {
			check(chunk.floorIntact() && chunk.roofIntact(), "chunk " + chunk.chunkX() + " " + chunk.chunkZ() + " not intact in a fresh world");
			check(chunk.mismatches(Surface.FLOOR, floorKey) == 0, "floor of chunk " + chunk.chunkX() + " " + chunk.chunkZ() + " differs from the model");
			check(chunk.mismatches(Surface.ROOF, roofKey) == 0, "roof of chunk " + chunk.chunkX() + " " + chunk.chunkZ() + " differs from the model");
			bedrock += chunk.floor().cardinality() + chunk.roof().cardinality();
		}
		// About half of the random layers' blocks are bedrock; all air would mean nothing was read.
		double share = bedrock / (chunks.size() * 2.0 * 4 * ChunkBedrock.BLOCKS_PER_LAYER);
		check(share > 0.45 && share < 0.55, "share of bedrock in the random layers: " + share);
		LOGGER.info("{} Nether chunks match the bedrock model of seed {}", chunks.size(), SEED);
	}

	/**
	 * Every Overworld chunk read fits the seed, and a seed with the same low 48 bits (the same Nether bedrock) fits none.
	 * Logs how much bedrock caves took away.
	 */
	private static void overworldMatchesTheModel(ClientGameTestContext context) {
		List<OverworldCheck.Chunk> chunks = context.computeOnClient(minecraft -> BedrockCollector.overworldChunks());
		check(chunks.size() >= 25, "only " + chunks.size() + " Overworld chunks read");
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		check(report.extra() == 0, "bedrock where seed " + SEED + " has none: " + report);
		check(report.fitting() == chunks.size(), "not every Overworld chunk fits seed " + SEED + ": " + report);
		long sameNether = SEED ^ 1L << 63;
		OverworldCheck.Report other = OverworldCheck.check(sameNether, chunks);
		check(!other.matches() && other.fitting() == 0, "seed " + sameNether + " fits the Overworld too: " + other);
		int bedrock = chunks.stream().mapToInt(chunk -> chunk.bedrock().cardinality()).sum();
		double share = bedrock / (chunks.size() * (double) OverworldCheck.BLOCKS);
		check(share > 0.45 && share < 0.55, "share of bedrock in the Overworld's random layers: " + share);
		LOGGER.info("{} Overworld chunks fit seed {} ({} blocks of its bedrock missing); seed {}: {}", chunks.size(), SEED,
			report.missing(), sameNether, other);
	}

	/** {@code /seedcracker start} tries the next chunk (the roof, far from the first); {@code stop} stops it. */
	private static void commands(ClientGameTestContext context) {
		sendCommand(context, "seedcracker check");
		sendCommand(context, "seedcracker check " + (SEED ^ 1L << 63));
		sendCommand(context, "seedcracker");
		sendCommand(context, "seedcracker start");
		check(context.computeOnClient(minecraft -> CrackController.state()) == CrackController.State.RUNNING, "start did not start");
		sendCommand(context, "seedcracker stop");
		context.waitFor(minecraft -> CrackController.state() == CrackController.State.STOPPED, 200);
		sendCommand(context, "seedcracker");
		context.takeScreenshot("commands");
	}

	private static void configScreen(ClientGameTestContext context) {
		context.setScreen(() -> new ModMenuIntegration().getModConfigScreenFactory().create(null));
		context.waitTicks(5);
		context.takeScreenshot("config-screen");
		context.setScreen(() -> null);
	}

	private static void sendCommand(ClientGameTestContext context, String command) {
		context.runOnClient(minecraft -> minecraft.player.connection.sendCommand(command));
		context.waitTicks(5);
	}

	/** A seed cracked in an earlier test run would be recalled instead of cracked. */
	private static void forgetKnownSeeds() {
		try {
			Files.deleteIfExists(FabricLoader.getInstance().getConfigDir().resolve("seed-cracker-seeds.json"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
