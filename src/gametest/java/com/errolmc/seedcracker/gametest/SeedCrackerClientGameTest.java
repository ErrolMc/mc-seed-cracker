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
import com.errolmc.seedcracker.crack.OverworldBedrock;
import com.errolmc.seedcracker.crack.OverworldCheck;
import com.errolmc.seedcracker.crack.SeedRecovery;
import com.errolmc.seedcracker.crack.Surface;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
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
 *
 * <p>Then it breaks bedrock the way survival players do (holes through the floor, a way up through the roof, building on
 * top of it, all of the layers the search relies on most), places one in the Overworld as a creative player might, and
 * cracks and checks again from the damaged chunks.
 */
public class SeedCrackerClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("seed-cracker-gametest");
	private static final long SEED = 607;
	/** Bedrock is broken in the 9 x 9 chunks around the player: more than it sees at render distance 2. */
	private static final int DAMAGE_RADIUS = 4;

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
			BlockPos spawn = context.computeOnClient(minecraft -> minecraft.player.blockPosition());

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
			brokenBedrock(context, world, spawn, hash);
			check(context.computeOnClient(minecraft -> minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).get()) == 0.0,
				"test client is not muted");
		}
	}

	/** Every floor and roof the client read is exactly what the seed predicts. */
	private static void bedrockMatchesTheModel(ClientGameTestContext context) {
		List<ChunkBedrock> chunks = context.computeOnClient(minecraft -> BedrockCollector.chunks());
		check(chunks.size() >= 25, "only " + chunks.size() + " Nether chunks read");
		long floorKey = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.FLOOR);
		long roofKey = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.ROOF);
		int bedrock = 0;
		for (ChunkBedrock chunk : chunks) {
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
		check(report.usable() == chunks.size() && report.fitting() == chunks.size(), "not every Overworld chunk fits seed " + SEED + ": " + report);
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

	/**
	 * Breaks bedrock in both dimensions, comes back at render distance 2 so every chunk the client reads is damaged, and
	 * cracks and checks again.
	 */
	private static void brokenBedrock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos spawn, long hash) {
		int spawnChunkX = spawn.getX() >> 4;
		int spawnChunkZ = spawn.getZ() >> 4;
		String toSpawn = "execute in minecraft:overworld run tp @a " + (spawn.getX() + 0.5) + " " + spawn.getY() + " " + (spawn.getZ() + 0.5);
		// Still in the Nether, at chunk 0 0.
		runCommands(world, breakNetherBedrock());
		world.getServer().runCommand(toSpawn);
		context.waitFor(minecraft -> minecraft.level.dimension() == Level.OVERWORLD, 200);
		world.getConnection().waitForChunksRender();
		runCommands(world, breakOverworldBedrock(spawnChunkX, spawnChunkZ));

		// Chunks still on their way from the full render distance arrive before the reset.
		int renderDistance = context.computeOnClient(minecraft -> minecraft.options.renderDistance().get());
		context.runOnClient(minecraft -> minecraft.options.renderDistance().set(2));
		context.waitTicks(40);
		context.runOnClient(minecraft -> CrackController.reset());
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 8.5 64 8.5");
		context.waitFor(minecraft -> minecraft.level.dimension() == Level.NETHER, 200);
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		brokenNetherBedrockArrived(context);

		sendCommand(context, "seedcracker start");
		context.waitFor(minecraft -> CrackController.state() != CrackController.State.RUNNING
			&& CrackController.state() != CrackController.State.WAITING, 20 * 120);
		CrackController.Result result = context.computeOnClient(minecraft -> CrackController.result());
		check(context.computeOnClient(minecraft -> CrackController.state()) == CrackController.State.FOUND, "no seed found from broken bedrock");
		check(result.seed() == SEED && result.confidence() == Crack.Confidence.CONFIRMED && !result.remembered(),
			"from broken bedrock: " + result);
		check(KnownSeeds.forHash(hash).isPresent(), "seed not remembered");

		world.getServer().runCommand(toSpawn);
		context.waitFor(minecraft -> minecraft.level.dimension() == Level.OVERWORLD, 200);
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		brokenOverworldBedrockArrived(context, spawnChunkX, spawnChunkZ);
		sendCommand(context, "seedcracker check");
		sendCommand(context, "seedcracker");
		context.takeScreenshot("broken-bedrock");
		context.runOnClient(minecraft -> minecraft.options.renderDistance().set(renderDistance));
	}

	/** Holes through the floor, a way up through the roof, a platform on top, and all of y 4 and y 123 broken. */
	private static List<String> breakNetherBedrock() {
		String in = "execute in minecraft:the_nether run ";
		int min = -DAMAGE_RADIUS * 16;
		int max = DAMAGE_RADIUS * 16 + 15;
		List<String> commands = new ArrayList<>();
		commands.add(in + fill(min, Surface.FLOOR.maxY, min, max, Surface.FLOOR.maxY, max, "air replace minecraft:bedrock"));
		commands.add(in + fill(min, Surface.ROOF.minY, min, max, Surface.ROOF.minY, max, "air replace minecraft:bedrock"));
		commands.add(in + fill(min, Surface.ROOF.solidY + 1, min, max, Surface.ROOF.solidY + 1, max, "minecraft:netherrack"));
		for (int x = min; x < max; x += 16) {
			for (int z = min; z < max; z += 16) {
				commands.add(in + fill(x, Surface.FLOOR.solidY, z, x + 7, Surface.FLOOR.maxY - 1, z + 7, "air replace minecraft:bedrock"));
				commands.add(in + fill(x, Surface.ROOF.minY, z + 12, x + 15, Surface.ROOF.solidY, z + 15, "air replace minecraft:bedrock"));
			}
		}
		return commands;
	}

	/** Holes through the floor, all of y -60 broken, and one bedrock placed where the seed has none. */
	private static List<String> breakOverworldBedrock(int chunkX, int chunkZ) {
		String in = "execute in minecraft:overworld run ";
		int minX = (chunkX - DAMAGE_RADIUS) * 16;
		int minZ = (chunkZ - DAMAGE_RADIUS) * 16;
		int maxX = (chunkX + DAMAGE_RADIUS) * 16 + 15;
		int maxZ = (chunkZ + DAMAGE_RADIUS) * 16 + 15;
		List<String> commands = new ArrayList<>();
		commands.add(in + fill(minX, OverworldBedrock.MAX_Y, minZ, maxX, OverworldBedrock.MAX_Y, maxZ, "air replace minecraft:bedrock"));
		for (int x = minX; x < maxX; x += 16) {
			for (int z = minZ; z < maxZ; z += 16) {
				commands.add(in + fill(x, OverworldBedrock.SOLID_Y, z, x + 7, OverworldBedrock.MAX_Y - 1, z + 7, "air replace minecraft:bedrock"));
			}
		}
		// In the spawn chunk, away from its hole.
		OverworldBedrock.Key key = OverworldBedrock.floorKey(SEED);
		int y = OverworldBedrock.MIN_Y + 1;
		int z = chunkZ * 16 + 8;
		int x = chunkX * 16 + 8;
		while (OverworldBedrock.isBedrock(key, x, y, z)) {
			x++;
		}
		commands.add(in + "setblock " + x + " " + y + " " + z + " minecraft:bedrock");
		return commands;
	}

	private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
	}

	/** All in one go on the server thread, not one command per tick. */
	private static void runCommands(TestSingleplayerContext world, List<String> commands) {
		world.getServer().runOnServer(server -> {
			for (String command : commands) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
		});
	}

	/** Every Nether chunk read is damaged, yet has no bedrock the seed doesn't place. */
	private static void brokenNetherBedrockArrived(ClientGameTestContext context) {
		List<ChunkBedrock> chunks = context.computeOnClient(minecraft -> BedrockCollector.chunks());
		check(chunks.size() >= 9, "only " + chunks.size() + " Nether chunks read at render distance 2");
		long floorKey = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.FLOOR);
		long roofKey = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.ROOF);
		for (ChunkBedrock chunk : chunks) {
			String name = "chunk " + chunk.chunkX() + " " + chunk.chunkZ();
			check(Math.abs(chunk.chunkX()) <= DAMAGE_RADIUS && Math.abs(chunk.chunkZ()) <= DAMAGE_RADIUS, name + " read outside the damage");
			check(chunk.observations(Surface.FLOOR).stream().noneMatch(o -> o.y() == Surface.FLOOR.maxY), name + ": floor bedrock at y 4 left");
			check(chunk.observations(Surface.ROOF).stream().noneMatch(o -> o.y() == Surface.ROOF.minY), name + ": roof bedrock at y 123 left");
			check(chunk.misplaced(Surface.FLOOR, floorKey) == 0 && chunk.misplaced(Surface.ROOF, roofKey) == 0, name + ": bedrock the seed doesn't place");
		}
		LOGGER.info("{} damaged Nether chunks read, {} to {} floor bedrock blocks left in each", chunks.size(),
			chunks.stream().mapToInt(chunk -> chunk.bedrockCount(Surface.FLOOR)).min().orElse(0),
			chunks.stream().mapToInt(chunk -> chunk.bedrockCount(Surface.FLOOR)).max().orElse(0));
	}

	/**
	 * Every Overworld chunk read fits the seed, except the spawn chunk with its placed bedrock, and those in the damaged
	 * area are damaged. (Overworld chunks can still trickle in from before render distance 2, from outside the area.)
	 */
	private static void brokenOverworldBedrockArrived(ClientGameTestContext context, int centerX, int centerZ) {
		List<OverworldCheck.Chunk> chunks = context.computeOnClient(minecraft -> BedrockCollector.overworldChunks());
		int topLayer = OverworldCheck.index(OverworldBedrock.MAX_Y, 0, 0);
		int damaged = 0;
		for (OverworldCheck.Chunk chunk : chunks) {
			if (Math.abs(chunk.chunkX() - centerX) <= DAMAGE_RADIUS && Math.abs(chunk.chunkZ() - centerZ) <= DAMAGE_RADIUS) {
				check(chunk.bedrock().get(topLayer, OverworldCheck.BLOCKS).isEmpty(),
					"Overworld chunk " + chunk.chunkX() + " " + chunk.chunkZ() + ": bedrock at y -60 left");
				damaged++;
			}
		}
		check(damaged >= 9, "only " + damaged + " damaged Overworld chunks read at render distance 2");
		OverworldCheck.Report report = OverworldCheck.check(SEED, chunks);
		check(report.usable() == chunks.size() && report.fitting() == chunks.size() - 1 && report.extra() == 1 && report.matches(),
			"broken Overworld bedrock: " + report);
		long sameNether = SEED ^ 1L << 63;
		OverworldCheck.Report other = OverworldCheck.check(sameNether, chunks);
		check(!other.matches(), "seed " + sameNether + " fits the broken Overworld bedrock: " + other);
		LOGGER.info("{} Overworld chunks, {} damaged: {}; seed {}: {}", chunks.size(), damaged, report, sameNether, other);
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
