package com.errolmc.seedcracker.client.crack;

import com.errolmc.seedcracker.crack.SeedRecovery;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds cracked before, in {@code config/seed-cracker-seeds.json}. A world is recognised by the seed hash its server
 * sends, so the same world is not cracked twice, whatever its address.
 */
public final class KnownSeeds {
	private static final Logger LOGGER = LoggerFactory.getLogger("seed-cracker");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("seed-cracker-seeds.json");

	/** One cracked world. Seeds are stored as text: JSON numbers lose the low digits of a 64-bit long. */
	public record Entry(String seed, String world, String found) {
		long seedValue() {
			return Long.parseLong(seed);
		}
	}

	private static List<Entry> entries;

	private KnownSeeds() {
	}

	public static Optional<Entry> forHash(long hashedSeed) {
		for (Entry entry : entries()) {
			try {
				if (SeedRecovery.obfuscate(entry.seedValue()) == hashedSeed) {
					return Optional.of(entry);
				}
			} catch (NumberFormatException e) {
				LOGGER.warn("Ignoring a bad seed in {}: {}", FILE, entry.seed());
			}
		}
		return Optional.empty();
	}

	public static void add(long seed, String world) {
		List<Entry> list = entries();
		list.removeIf(entry -> entry.seed().equals(Long.toString(seed)));
		list.add(new Entry(Long.toString(seed), world, LocalDate.now().toString()));
		save();
	}

	private static List<Entry> entries() {
		if (entries == null) {
			entries = load();
		}
		return entries;
	}

	private static List<Entry> load() {
		if (!Files.exists(FILE)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			List<Entry> loaded = GSON.fromJson(reader, new TypeToken<List<Entry>>() { }.getType());
			return loaded != null ? new ArrayList<>(loaded) : new ArrayList<>();
		} catch (IOException | JsonParseException e) {
			LOGGER.error("Could not read {}", FILE, e);
			return new ArrayList<>();
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(entries, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", FILE, e);
		}
	}
}
