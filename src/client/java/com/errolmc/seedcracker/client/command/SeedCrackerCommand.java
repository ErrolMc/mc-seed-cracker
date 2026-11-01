package com.errolmc.seedcracker.client.command;

import com.errolmc.seedcracker.client.crack.CrackController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

/**
 * {@code /seedcracker}: status; {@code start} and {@code stop} run it by hand; {@code check [seed]} checks a seed against
 * the Overworld bedrock. Client-side, so it works on any server.
 */
public final class SeedCrackerCommand {
	private SeedCrackerCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(ClientCommands.literal("seedcracker")
			.executes(context -> {
				context.getSource().sendFeedback(Component.translatable("seed-cracker.prefix").withStyle(ChatFormatting.GOLD));
				for (Component line : CrackController.status()) {
					context.getSource().sendFeedback(line);
				}
				return 1;
			})
			.then(ClientCommands.literal("start").executes(context -> {
				CrackController.startByCommand(context.getSource().getClient());
				return 1;
			}))
			.then(ClientCommands.literal("stop").executes(context -> {
				CrackController.stopByCommand(context.getSource().getClient());
				return 1;
			}))
			.then(ClientCommands.literal("check")
				.executes(context -> {
					CrackController.checkByCommand(context.getSource().getClient(), OptionalLong.empty());
					return 1;
				})
				.then(ClientCommands.argument("seed", LongArgumentType.longArg()).executes(context -> {
					CrackController.checkByCommand(context.getSource().getClient(), OptionalLong.of(LongArgumentType.getLong(context, "seed")));
					return 1;
				}))));
	}
}
