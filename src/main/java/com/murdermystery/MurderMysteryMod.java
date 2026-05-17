package com.murdermystery;

import com.murdermystery.game.MurderMysteryCommand;
import com.murdermystery.game.MurderMysteryGame;
import com.murdermystery.game.GameState;
import com.murdermystery.game.PlayerRole;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MurderMysteryMod implements ModInitializer {

    public static final String MOD_ID = "murdermystery";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Murder Mystery Mod loaded!");

        // Block ALL chat during game (player chat & system messages to players)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (MurderMysteryGame.getState() == GameState.RUNNING ||
                MurderMysteryGame.getState() == GameState.STARTING) {
                sender.sendMessage(Text.literal("§7[Chat dinonaktifkan selama game berlangsung]"), true);
                return false; // cancel message
            }
            return true;
        });

        // Register commands
        MurderMysteryCommand.register();

        // Server tick → game timer
        ServerTickEvents.END_SERVER_TICK.register(MurderMysteryGame::onServerTick);

        // Player disconnect → check win
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MurderMysteryGame.onPlayerLeave(server, handler.player);
        });

        // Player kills another player
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (!(entity instanceof ServerPlayerEntity killer)) return;
            if (!(killedEntity instanceof ServerPlayerEntity victim)) return;

            MinecraftServer server = killer.getServer();
            if (server == null) return;

            // Let the game decide; if kill is cancelled by cooldown the entity already died
            // so we just do game logic here (spectator mode etc.)
            MurderMysteryGame.onPlayerKill(server, killer, victim);
        });

        // Prevent wrong kills during game:
        // Innocents cannot kill at all — handle via damage cancellation
        // We hook into the living entity damage to cancel PvP for non-combat roles.
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return net.minecraft.util.ActionResult.PASS;
            if (!(entity instanceof ServerPlayerEntity)) return net.minecraft.util.ActionResult.PASS;

            if (MurderMysteryGame.getState() != com.murdermystery.game.GameState.RUNNING)
                return net.minecraft.util.ActionResult.PASS;

            PlayerRole role = MurderMysteryGame.getRole(sp.getUuid());

            // Only Murder and Sheriff may attack players
            if (role == PlayerRole.INNOCENT) {
                sp.sendMessage(net.minecraft.text.Text.literal("§cInnocents cannot attack!"), true);
                return net.minecraft.util.ActionResult.FAIL;
            }

            return net.minecraft.util.ActionResult.PASS;
        });
    }
}
