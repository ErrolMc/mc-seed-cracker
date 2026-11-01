package com.errolmc.seedcracker.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings, stored as {@code config/seed-cracker.json}. */
public final class CrackerConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("seed-cracker");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("seed-cracker.json");

	public static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();

	private static CrackerConfig instance = new CrackerConfig();

	/** Show progress above the hotbar while it runs. */
	public boolean showProgress = true;
	/** Search threads; 0 picks one less than the number of CPU threads, so the game keeps one. */
	public int threads = 0;

	public static CrackerConfig get() {
		return instance;
	}

	public int threadCount() {
		return threads > 0 ? threads : Math.max(1, MAX_THREADS - 1);
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			instance = new CrackerConfig();
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			CrackerConfig loaded = GSON.fromJson(reader, CrackerConfig.class);
			instance = loaded != null ? loaded.sanitized() : new CrackerConfig();
		} catch (IOException | JsonParseException e) {
			LOGGER.error("Could not read {}, using defaults", FILE, e);
			instance = new CrackerConfig();
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", FILE, e);
		}
	}

	private CrackerConfig sanitized() {
		threads = Mth.clamp(threads, 0, MAX_THREADS);
		return this;
	}
}
