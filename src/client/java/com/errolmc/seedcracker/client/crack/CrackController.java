package com.errolmc.seedcracker.client.crack;

import com.errolmc.seedcracker.client.collect.BedrockCollector;
import com.errolmc.seedcracker.client.collect.ChunkBedrock;
import com.errolmc.seedcracker.client.config.CrackerConfig;
import com.errolmc.seedcracker.client.mixin.BiomeManagerAccessor;
import com.errolmc.seedcracker.crack.Crack;
import com.errolmc.seedcracker.crack.JavaLcg;
import com.errolmc.seedcracker.crack.NetherBedrock;
import com.errolmc.seedcracker.crack.Observation;
import com.errolmc.seedcracker.crack.OverworldCheck;
import com.errolmc.seedcracker.crack.Surface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the {@link Crack} when the player asks ({@code /seedcracker start}; it never starts by itself), picks the blocks
 * and reports in chat. Client thread only.
 *
 * <p>Each attempt searches the bedrock of one chunk's floor or roof and checks hits against the bedrock of its other
 * surface. Only bedrock that is there is used: survival players can break it but not place it, so broken bedrock
 * doesn't get in the way. The first attempt takes the chunk farthest from the player, where players are least likely to
 * have built and, on an old world, the Nether is most likely to be post-1.18. If it finds nothing, the next attempt
 * switches surface and picks a chunk far from the ones already tried.
 */
public final class CrackController {
	private static final Logger LOGGER = LoggerFactory.getLogger("seed-cracker");
	/** Chunks one {@code /seedcracker start} tries before giving up. */
	private static final int ATTEMPTS = 3;
	/** Bedrock blocks a surface needs to be searched: half of the about 512 it normally has. */
	private static final int MIN_BEDROCK = 256;

	public enum State {
		/** Collecting chunks; nothing runs until {@code /seedcracker start}. */
		WAITING,
		RUNNING,
		FOUND,
		FAILED,
		STOPPED
	}

	private record Attempt(Surface surface, ChunkBedrock chunk, List<Observation> secondary) {
	}

	/** The answer: {@code alternatives} holds other candidates when the seed could not be confirmed. */
	public record Result(long seed, long structureSeed, Crack.Confidence confidence, List<Long> alternatives, boolean remembered) {
	}

	private static State state = State.WAITING;
	private static Crack crack;
	private static Attempt attempt;
	private static long startedNanos;
	private static int attemptsLeft;
	private static final List<Attempt> tried = new ArrayList<>();
	private static final Set<String> used = new HashSet<>();
	private static Result result;
	private static OptionalLong hashedSeed = OptionalLong.empty();
	private static int ticks;

	private CrackController() {
	}

	public static State state() {
		return state;
	}

	public static Result result() {
		return result;
	}

	public static double progress() {
		return crack != null ? crack.progress() : 0;
	}

	/** Called on join and disconnect: everything belongs to one world. */
	public static void reset() {
		if (crack != null) {
			crack.cancel();
			crack = null;
		}
		BedrockCollector.clear();
		state = State.WAITING;
		attempt = null;
		attemptsLeft = 0;
		tried.clear();
		used.clear();
		result = null;
		hashedSeed = OptionalLong.empty();
	}

	/** Called every client tick. */
	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}
		ticks++;
		readHash(minecraft, level);

		if (crack != null) {
			Crack.Outcome outcome = crack.outcome();
			if (outcome != null) {
				finish(minecraft, outcome);
			} else if (CrackerConfig.get().showProgress && ticks % 20 == 0) {
				minecraft.player.sendOverlayMessage(progressLine());
			}
		}
	}

	/** {@code /seedcracker start}: (re)starts, using chunks not tried yet. */
	public static void startByCommand(Minecraft minecraft) {
		if (state == State.RUNNING) {
			say(minecraft, Component.translatable("seed-cracker.message.already_running"));
			return;
		}
		if (BedrockCollector.count() == 0) {
			say(minecraft, Component.translatable("seed-cracker.message.no_data"));
			return;
		}
		Attempt next = pickAttempt(minecraft);
		if (next == null) {
			say(minecraft, Component.translatable("seed-cracker.message.no_new_chunks"));
			return;
		}
		attemptsLeft = ATTEMPTS;
		result = null;
		start(minecraft, next);
	}

	/** {@code /seedcracker stop}. */
	public static void stopByCommand(Minecraft minecraft) {
		if (crack == null) {
			say(minecraft, Component.translatable("seed-cracker.message.not_running"));
			return;
		}
		attemptsLeft = 0;
		crack.cancel();
	}

	/** {@code /seedcracker check [seed]}: the given seed, or else the one found, against the Overworld floor. */
	public static void checkByCommand(Minecraft minecraft, OptionalLong seed) {
		if (seed.isEmpty() && (result == null || result.confidence() == Crack.Confidence.STRUCTURE_SEED_ONLY)) {
			say(minecraft, Component.translatable("seed-cracker.message.check_no_seed"));
			return;
		}
		if (BedrockCollector.overworldCount() == 0) {
			say(minecraft, Component.translatable("seed-cracker.message.check_no_data"));
			return;
		}
		say(minecraft, overworldLine(seed.isPresent() ? seed.getAsLong() : result.seed()));
	}

	/** Status lines for {@code /seedcracker}. */
	public static List<Component> status() {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("seed-cracker.status.chunks", BedrockCollector.count()));
		lines.add(Component.translatable("seed-cracker.status.overworld_chunks", BedrockCollector.overworldCount()));
		switch (state) {
			case WAITING -> lines.add(Component.translatable("seed-cracker.status.waiting"));
			case RUNNING -> lines.add(Component.translatable("seed-cracker.status.running", surfaceName(attempt.surface()),
				chunkName(attempt.chunk()), percent(progress())));
			case FOUND -> lines.add(Component.translatable("seed-cracker.status.found", seedText(result.seed()), confidenceText(result)));
			case FAILED -> lines.add(Component.translatable("seed-cracker.status.failed"));
			case STOPPED -> lines.add(Component.translatable("seed-cracker.status.stopped"));
		}
		if (hashedSeed.isPresent()) {
			lines.add(Component.translatable("seed-cracker.status.hash", Long.toHexString(hashedSeed.getAsLong())));
		}
		return lines;
	}

	// ------------------------------------------------------------------ attempts

	private static void start(Minecraft minecraft, Attempt next) {
		attemptsLeft--;
		attempt = next;
		tried.add(next);
		used.add(usedKey(next.surface(), next.chunk()));
		int threads = CrackerConfig.get().threadCount();
		crack = new Crack(next.surface(), next.chunk().observations(next.surface()), next.secondary(), hashedSeed);
		crack.start(threads);
		startedNanos = System.nanoTime();
		state = State.RUNNING;
		say(minecraft, Component.translatable("seed-cracker.message.started", surfaceName(next.surface()), chunkName(next.chunk()), threads));
		LOGGER.info("Cracking from the {} of chunk {} {} ({} other-surface blocks, {} threads)", next.surface(),
			next.chunk().chunkX(), next.chunk().chunkZ(), next.secondary().size(), threads);
	}

	/** Alternates floor and roof (floor first, players rarely dig it), each time on a chunk far from those tried. */
	private static Attempt pickAttempt(Minecraft minecraft) {
		List<ChunkBedrock> chunks = BedrockCollector.chunks();
		int playerX = minecraft.player.chunkPosition().x();
		int playerZ = minecraft.player.chunkPosition().z();
		Surface first = tried.size() % 2 == 0 ? Surface.FLOOR : Surface.ROOF;
		for (Surface surface : new Surface[] {first, first.other()}) {
			Optional<ChunkBedrock> best = chunks.stream()
				.filter(chunk -> chunk.bedrockCount(surface) >= MIN_BEDROCK && !used.contains(usedKey(surface, chunk)))
				.max(Comparator.<ChunkBedrock>comparingLong(chunk -> distanceToTried(chunk))
					.thenComparingLong(chunk -> chunk.distanceSquared(playerX, playerZ)));
			if (best.isPresent()) {
				ChunkBedrock chunk = best.get();
				return new Attempt(surface, chunk, chunk.observations(surface.other()));
			}
		}
		return null;
	}

	private static long distanceToTried(ChunkBedrock chunk) {
		long nearest = Long.MAX_VALUE;
		for (Attempt previous : tried) {
			nearest = Math.min(nearest, chunk.distanceSquared(previous.chunk().chunkX(), previous.chunk().chunkZ()));
		}
		return nearest;
	}

	private static String usedKey(Surface surface, ChunkBedrock chunk) {
		return surface + " " + chunk.chunkX() + " " + chunk.chunkZ();
	}

	private static void finish(Minecraft minecraft, Crack.Outcome outcome) {
		crack = null;
		if (outcome.error() != null) {
			LOGGER.error("Seed cracking failed", outcome.error());
			state = State.FAILED;
			say(minecraft, Component.translatable("seed-cracker.message.error", String.valueOf(outcome.error())));
			return;
		}
		if (outcome.cancelled()) {
			state = State.STOPPED;
			say(minecraft, Component.translatable("seed-cracker.message.stopped"));
			return;
		}
		if (!outcome.found().isEmpty()) {
			found(minecraft, outcome.found());
			return;
		}
		LOGGER.info("No seed fits the {} of chunk {} {}", attempt.surface(), attempt.chunk().chunkX(), attempt.chunk().chunkZ());
		Attempt next = attemptsLeft > 0 ? pickAttempt(minecraft) : null;
		if (next != null) {
			say(minecraft, Component.translatable("seed-cracker.message.retry"));
			start(minecraft, next);
			return;
		}
		state = State.FAILED;
		say(minecraft, Component.translatable("seed-cracker.message.failed"));
	}

	private static void found(Minecraft minecraft, List<Crack.Found> found) {
		Crack.Found best = found.getFirst();
		List<Long> alternatives = found.stream().skip(1).map(Crack.Found::seed).toList();
		result = new Result(best.seed(), best.structureSeed(), best.confidence(), alternatives, false);
		state = State.FOUND;
		LOGGER.info("Found seed {} ({}), {} other candidates", best.seed(), best.confidence(), alternatives.size());

		say(minecraft, Component.translatable("seed-cracker.message.found", seedText(best.seed()), elapsed()));
		switch (best.confidence()) {
			case CONFIRMED -> {
				say(minecraft, matchesLine(best.seed()));
				KnownSeeds.add(best.seed(), worldName(minecraft));
			}
			case RANDOM_SEED_GUESS -> say(minecraft, Component.translatable("seed-cracker.message.unconfirmed",
				seedText(best.structureSeed())));
			case STRUCTURE_SEED_ONLY -> say(minecraft, Component.translatable("seed-cracker.message.structure_only"));
		}
		if (best.confidence() != Crack.Confidence.STRUCTURE_SEED_ONLY && BedrockCollector.overworldCount() > 0) {
			say(minecraft, overworldLine(best.seed()));
		}
		if (!alternatives.isEmpty()) {
			MutableComponent list = Component.empty();
			for (int i = 0; i < Math.min(5, alternatives.size()); i++) {
				list.append(i == 0 ? Component.empty() : Component.literal(", ")).append(seedText(alternatives.get(i)));
			}
			say(minecraft, Component.translatable("seed-cracker.message.alternatives", list));
		}
	}

	/** How many of the loaded chunks have no bedrock this seed doesn't give (bedrock players broke doesn't count). */
	private static Component matchesLine(long seed) {
		long floorKey = NetherBedrock.surfaceKeyOfWorld(seed, Surface.FLOOR);
		long roofKey = NetherBedrock.surfaceKeyOfWorld(seed, Surface.ROOF);
		int matching = 0;
		int total = 0;
		for (ChunkBedrock chunk : BedrockCollector.chunks()) {
			total++;
			if (chunk.misplaced(Surface.FLOOR, floorKey) == 0 && chunk.misplaced(Surface.ROOF, roofKey) == 0) {
				matching++;
			}
		}
		return matching == total
			? Component.translatable("seed-cracker.message.matches_all", total)
			: Component.translatable("seed-cracker.message.matches_some", matching, total);
	}

	/** Whether the Overworld floor read so far is what this seed generates; it depends on all 64 bits of the seed. */
	private static Component overworldLine(long seed) {
		OverworldCheck.Report report = OverworldCheck.check(seed, BedrockCollector.overworldChunks());
		LOGGER.info("Overworld check of seed {}: {}", seed, report);
		if (report.usable() == 0) {
			return Component.translatable("seed-cracker.message.check_no_bedrock", report.chunks());
		}
		return Component.translatable(report.matches() ? "seed-cracker.message.check_match" : "seed-cracker.message.check_mismatch",
			seedText(seed), report.fitting(), report.usable());
	}

	// ------------------------------------------------------------------ seed hash

	/** The seed hash is the same in every dimension; a new one means a new world. */
	private static void readHash(Minecraft minecraft, ClientLevel level) {
		long hash = ((BiomeManagerAccessor) level.getBiomeManager()).seedCracker$biomeZoomSeed();
		if (hashedSeed.isPresent() && hashedSeed.getAsLong() == hash) {
			return;
		}
		hashedSeed = OptionalLong.of(hash);
		if (state != State.WAITING) {
			return;
		}
		KnownSeeds.forHash(hash).ifPresent(entry -> {
			long seed = Long.parseLong(entry.seed());
			result = new Result(seed, seed & JavaLcg.MASK, Crack.Confidence.CONFIRMED, List.of(), true);
			state = State.FOUND;
			say(minecraft, Component.translatable("seed-cracker.message.remembered", seedText(seed), entry.found()));
		});
	}

	// ------------------------------------------------------------------ text

	private static Component progressLine() {
		double progress = progress();
		double elapsed = (System.nanoTime() - startedNanos) / 1e9;
		if (progress < 0.02) {
			return Component.translatable("seed-cracker.progress.starting", percent(progress));
		}
		long left = Math.round(elapsed * (1 - progress) / progress);
		return Component.translatable("seed-cracker.progress", percent(progress), duration(left));
	}

	private static String elapsed() {
		return duration(Math.round((System.nanoTime() - startedNanos) / 1e9));
	}

	private static String duration(long seconds) {
		return seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s";
	}

	private static String percent(double progress) {
		return (int) (progress * 100) + "%";
	}

	private static Component surfaceName(Surface surface) {
		return Component.translatable("seed-cracker.surface." + surface.name().toLowerCase());
	}

	private static String chunkName(ChunkBedrock chunk) {
		return "x " + (chunk.chunkX() * 16 + 8) + ", z " + (chunk.chunkZ() * 16 + 8);
	}

	private static Component confidenceText(Result result) {
		return Component.translatable("seed-cracker.confidence." + result.confidence().name().toLowerCase());
	}

	/** The seed in green; click copies it. */
	static MutableComponent seedText(long seed) {
		String text = Long.toString(seed);
		return Component.literal(text).withStyle(ChatFormatting.GREEN).withStyle(style -> style
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(text))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("seed-cracker.message.click_to_copy"))));
	}

	private static String worldName(Minecraft minecraft) {
		ServerData server = minecraft.getCurrentServer();
		if (server != null) {
			return server.ip;
		}
		return minecraft.hasSingleplayerServer() ? "singleplayer" : "unknown";
	}

	private static void say(Minecraft minecraft, Component message) {
		if (minecraft.player != null) {
			// Siblings inherit their parent's style, so the gold prefix sits beside the message, not above it.
			minecraft.player.sendSystemMessage(Component.empty()
				.append(Component.translatable("seed-cracker.prefix").withStyle(ChatFormatting.GOLD))
				.append(" ")
				.append(message));
		}
	}
}
