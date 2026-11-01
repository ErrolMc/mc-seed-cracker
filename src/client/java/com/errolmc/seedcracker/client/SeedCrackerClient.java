package com.errolmc.seedcracker.client;

import com.errolmc.seedcracker.client.collect.BedrockCollector;
import com.errolmc.seedcracker.client.command.SeedCrackerCommand;
import com.errolmc.seedcracker.client.config.CrackerConfig;
import com.errolmc.seedcracker.client.crack.CrackController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class SeedCrackerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CrackerConfig.load();
		ClientChunkEvents.CHUNK_LOAD.register(BedrockCollector::onChunkLoad);
		ClientTickEvents.END_CLIENT_TICK.register(CrackController::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> CrackController.reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> CrackController.reset());
		ClientCommandRegistrationCallback.EVENT.register(SeedCrackerCommand::register);
	}
}
