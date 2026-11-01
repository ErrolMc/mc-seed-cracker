package com.errolmc.seedcracker.client.config;

import java.util.Arrays;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Cloth Config settings screen. Only loaded when Cloth Config is installed. */
final class ConfigScreen {
	private ConfigScreen() {
	}

	static Screen create(Screen parent) {
		CrackerConfig config = CrackerConfig.get();
		CrackerConfig defaults = new CrackerConfig();

		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.translatable("seed-cracker.config.title"))
			.setSavingRunnable(CrackerConfig::save);
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory cracker = builder.getOrCreateCategory(Component.translatable("seed-cracker.config.category.cracker"));
		cracker.addEntry(entries.startTextDescription(Component.translatable("seed-cracker.config.commands_info")).build());
		cracker.addEntry(entries.startBooleanToggle(text("show_progress"), config.showProgress)
			.setDefaultValue(defaults.showProgress)
			.setTooltip(tooltip("show_progress"))
			.setSaveConsumer(value -> config.showProgress = value)
			.build());
		cracker.addEntry(entries.startIntSlider(text("threads"), config.threads, 0, CrackerConfig.MAX_THREADS)
			.setDefaultValue(defaults.threads)
			.setTextGetter(value -> value == 0
				? Component.translatable("seed-cracker.config.threads.auto", Math.max(1, CrackerConfig.MAX_THREADS - 1))
				: Component.literal(Integer.toString(value)))
			.setTooltip(tooltip("threads"))
			.setSaveConsumer(value -> config.threads = value)
			.build());

		return builder.build();
	}

	private static Component text(String key) {
		return Component.translatable("seed-cracker.config." + key);
	}

	/** Cloth Config does not wrap tooltips, so the translations put a newline where each line should end. */
	private static Component[] tooltip(String key) {
		String text = Component.translatable("seed-cracker.config." + key + ".tooltip").getString();
		return Arrays.stream(text.split("\n")).map(Component::literal).toArray(Component[]::new);
	}
}
