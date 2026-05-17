package com.murdermystery.game;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class MurderMysteryCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                CommandManager.literal("mm")
                    .requires(src -> src.hasPermissionLevel(2)) // op only

                    // /mm start [1|2]
                    .then(CommandManager.literal("start")
                        .executes(ctx -> startGame(ctx.getSource(), 1))
                        .then(CommandManager.argument("murders", IntegerArgumentType.integer(1, 2))
                            .executes(ctx -> startGame(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "murders")))
                        )
                    )

                    // /mm stop
                    .then(CommandManager.literal("stop")
                        .executes(ctx -> {
                            MurderMysteryGame.stopGame(ctx.getSource().getServer());
                            ctx.getSource().sendFeedback(() -> Text.literal("§aGame stopped."), false);
                            return 1;
                        })
                    )

                    // /mm status
                    .then(CommandManager.literal("status")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("§7State: §e" + MurderMysteryGame.getState()), false
                            );
                            return 1;
                        })
                    )
            );
        });
    }

    private static int startGame(ServerCommandSource source, int murders) {
        boolean ok = MurderMysteryGame.startGame(source.getServer(), murders);
        if (!ok) {
            source.sendError(Text.literal("Cannot start: need " + MurderMysteryGame.MIN_PLAYERS + "+ players or game already running."));
            return 0;
        }
        return 1;
    }
}
