package com.murdermystery.game;

import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.*;

public class MurderMysteryGame {

    // ── Config ──────────────────────────────────────────────────────────────
    public static final int MIN_PLAYERS       = 4;
    public static final int ROUND_SECONDS     = 600;   // 10 minutes
    public static final int MURDER_KILL_CD    = 60;    // seconds
    public static final int COUNTDOWN_SECONDS = 10;

    // ── State ───────────────────────────────────────────────────────────────
    private static GameState        state       = GameState.WAITING;
    private static Map<UUID, PlayerRole> roles  = new HashMap<>();
    private static Map<UUID, Long>  killCooldown = new HashMap<>();   // epoch ms
    private static int              roundTimer  = ROUND_SECONDS;
    private static int              countdown   = COUNTDOWN_SECONDS;
    private static int              murderCount = 1;

    // Tick counters (20 ticks = 1 second)
    private static int tickAccum = 0;

    // ── Public API ──────────────────────────────────────────────────────────

    public static GameState getState() { return state; }
    public static PlayerRole getRole(UUID uuid) { return roles.getOrDefault(uuid, PlayerRole.INNOCENT); }

    /** Called by command /mm start [murders=1|2] */
    public static boolean startGame(MinecraftServer server, int murders) {
        if (state != GameState.WAITING) return false;
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.size() < MIN_PLAYERS) return false;

        murderCount = Math.max(1, Math.min(2, murders));
        state = GameState.STARTING;
        countdown = COUNTDOWN_SECONDS;

        broadcast(server, Text.literal("§c§lMURDER MYSTERY §r§7- Game starting in " + countdown + "s! (" + murderCount + " murderer(s))"));
        return true;
    }

    /** Called every server tick from MurderMysteryMod */
    public static void onServerTick(MinecraftServer server) {
        if (state == GameState.WAITING || state == GameState.ENDED) return;

        tickAccum++;
        if (tickAccum < 20) return;
        tickAccum = 0;

        // ── STARTING countdown ───────────────────────────────────────────
        if (state == GameState.STARTING) {
            countdown--;
            if (countdown <= 0) {
                assignRolesAndBegin(server);
            } else {
                broadcast(server, Text.literal("§e" + countdown + "..."));
            }
            return;
        }

        // ── RUNNING timer ────────────────────────────────────────────────
        if (state == GameState.RUNNING) {
            roundTimer--;
            updateScoreboard(server);

            if (roundTimer <= 0) {
                endGame(server, null); // time out -> innocents win
                return;
            }

            // Every 30 s announce remaining time
            if (roundTimer % 30 == 0) {
                broadcast(server, Text.literal("§7" + roundTimer + " seconds remaining!"));
            }
        }
    }

    /** Called when a player kills another */
    public static boolean onPlayerKill(MinecraftServer server, ServerPlayerEntity killer, ServerPlayerEntity victim) {
        if (state != GameState.RUNNING) return false;

        PlayerRole killerRole = roles.getOrDefault(killer.getUuid(), PlayerRole.INNOCENT);
        PlayerRole victimRole = roles.getOrDefault(victim.getUuid(), PlayerRole.INNOCENT);

        // ── Murder kills someone ─────────────────────────────────────────
        if (killerRole == PlayerRole.MURDER) {
            long now = System.currentTimeMillis();
            long cd  = killCooldown.getOrDefault(killer.getUuid(), 0L);
            if (now < cd) {
                int remaining = (int)((cd - now) / 1000);
                killer.sendMessage(Text.literal("§cKill on cooldown! " + remaining + "s remaining."), true);
                return false; // cancel the kill
            }
            // Apply cooldown
            killCooldown.put(killer.getUuid(), now + MURDER_KILL_CD * 1000L);
            eliminatePlayer(server, victim, killer.getName().getString() + " (Murder)");
            checkWinCondition(server);
            return true;
        }

        // ── Sheriff shoots ───────────────────────────────────────────────
        if (killerRole == PlayerRole.SHERIFF) {
            if (victimRole == PlayerRole.MURDER) {
                // Sheriff wins!
                broadcast(server, Text.literal("§a§l" + killer.getName().getString() + " §r§ashot the murderer! §6§lINNOCENTS WIN!"));
                endGame(server, "innocents");
            } else {
                // Sheriff shot wrong person -> sheriff dies
                broadcast(server, Text.literal("§c" + killer.getName().getString() + " §7(Sheriff) shot the wrong person and died!"));
                eliminatePlayer(server, killer, "friendly fire");
                checkWinCondition(server);
            }
            return true;
        }

        return false;
    }

    /** Called when a player leaves mid-game */
    public static void onPlayerLeave(MinecraftServer server, ServerPlayerEntity player) {
        if (state != GameState.RUNNING) return;
        roles.remove(player.getUuid());
        checkWinCondition(server);
    }

    /** /mm stop */
    public static void stopGame(MinecraftServer server) {
        broadcast(server, Text.literal("§cGame forcefully stopped."));
        resetState(server);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static void assignRolesAndBegin(MinecraftServer server) {
        roles.clear();
        killCooldown.clear();
        roundTimer = ROUND_SECONDS;

        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        Collections.shuffle(players);

        // Assign murders
        for (int i = 0; i < murderCount && i < players.size(); i++) {
            roles.put(players.get(i).getUuid(), PlayerRole.MURDER);
        }
        // Assign sheriff (next player after murders)
        int sheriffIdx = murderCount;
        if (sheriffIdx < players.size()) {
            roles.put(players.get(sheriffIdx).getUuid(), PlayerRole.SHERIFF);
        }
        // Rest are innocent
        for (int i = sheriffIdx + 1; i < players.size(); i++) {
            roles.put(players.get(i).getUuid(), PlayerRole.INNOCENT);
        }

        // Give items & notify via title screen
        for (ServerPlayerEntity p : players) {
            p.getInventory().clear();
            p.changeGameMode(GameMode.SURVIVAL);
            PlayerRole role = roles.getOrDefault(p.getUuid(), PlayerRole.INNOCENT);

            // Clear any previous title first
            p.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
            // Fade: 20 ticks in, 60 ticks stay, 40 ticks out
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 40));

            switch (role) {
                case MURDER -> {
                    ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
                    sword.addEnchantment(Enchantments.SHARPNESS, 1);
                    p.getInventory().insertStack(sword);
                    p.networkHandler.sendPacket(new TitleS2CPacket(
                        Text.literal("☠ MURDERER").formatted(Formatting.RED, Formatting.BOLD)
                    ));
                    p.networkHandler.sendPacket(new SubtitleS2CPacket(
                        Text.literal("Bunuh semua orang tanpa ketahuan!").formatted(Formatting.GRAY)
                    ));
                }
                case SHERIFF -> {
                    ItemStack bow    = new ItemStack(Items.BOW);
                    ItemStack arrows = new ItemStack(Items.ARROW, 1);
                    bow.addEnchantment(Enchantments.POWER, 1);
                    p.getInventory().insertStack(bow);
                    p.getInventory().insertStack(arrows);
                    p.networkHandler.sendPacket(new TitleS2CPacket(
                        Text.literal("⭐ SHERIFF").formatted(Formatting.GOLD, Formatting.BOLD)
                    ));
                    p.networkHandler.sendPacket(new SubtitleS2CPacket(
                        Text.literal("Tembak si pembunuh!").formatted(Formatting.GRAY)
                    ));
                }
                case INNOCENT -> {
                    p.networkHandler.sendPacket(new TitleS2CPacket(
                        Text.literal("✔ INNOCENT").formatted(Formatting.GREEN, Formatting.BOLD)
                    ));
                    p.networkHandler.sendPacket(new SubtitleS2CPacket(
                        Text.literal("Bertahan dan bantu temukan pembunuh!").formatted(Formatting.GRAY)
                    ));
                }
            }
        }

        state = GameState.RUNNING;
        broadcast(server, Text.literal("§c§l⚔ MURDER MYSTERY BEGINS! ⚔ §r§7(" + ROUND_SECONDS + "s round)"));
        updateScoreboard(server);
    }

    private static void eliminatePlayer(MinecraftServer server, ServerPlayerEntity player, String killedBy) {
        roles.put(player.getUuid(), PlayerRole.INNOCENT); // neutralise role
        player.changeGameMode(GameMode.SPECTATOR);
        player.getInventory().clear();
        broadcast(server, Text.literal("§8" + player.getName().getString() + " §7was eliminated by §8" + killedBy));
    }

    private static void checkWinCondition(MinecraftServer server) {
        if (state != GameState.RUNNING) return;

        List<ServerPlayerEntity> alive = getAlivePlayers(server);

        long murders   = alive.stream().filter(p -> roles.getOrDefault(p.getUuid(), PlayerRole.INNOCENT) == PlayerRole.MURDER).count();
        long innocents = alive.stream().filter(p -> roles.getOrDefault(p.getUuid(), PlayerRole.INNOCENT) != PlayerRole.MURDER).count();

        if (murders == 0) {
            broadcast(server, Text.literal("§6§lAll murderers eliminated! INNOCENTS WIN!"));
            endGame(server, "innocents");
        } else if (innocents == 0) {
            broadcast(server, Text.literal("§c§lMurderer eliminated everyone! MURDER WINS!"));
            endGame(server, "murder");
        }
    }

    private static void endGame(MinecraftServer server, String winner) {
        state = GameState.ENDED;
        // Reveal roles
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.changeGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
        }
        if (winner != null) {
            broadcast(server, Text.literal("§e§l--- GAME OVER --- §r§7Winner: §b" + winner));
        }
        // Auto-reset after 10 s
        // (handled by next tick cycle; state=ENDED stops ticking)
        resetState(server);
    }

    private static void resetState(MinecraftServer server) {
        roles.clear();
        killCooldown.clear();
        roundTimer = ROUND_SECONDS;
        tickAccum = 0;
        state = GameState.WAITING;
        // Remove scoreboard sidebar
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.changeGameMode(GameMode.SURVIVAL);
        }
    }

    private static void updateScoreboard(MinecraftServer server) {
        int min = roundTimer / 60;
        int sec = roundTimer % 60;
        String timeStr = String.format("%02d:%02d", min, sec);

        long murderAlive   = getAlivePlayers(server).stream().filter(p -> roles.getOrDefault(p.getUuid(), PlayerRole.INNOCENT) == PlayerRole.MURDER).count();
        long innocentAlive = getAlivePlayers(server).stream().filter(p -> roles.getOrDefault(p.getUuid(), PlayerRole.INNOCENT) != PlayerRole.MURDER).count();

        // Send actionbar to all (simple scoreboard replacement)
        Text bar = Text.literal(
            "§c☠ Murderer§7: " + murderAlive + "  §a✔ Alive§7: " + innocentAlive + "  §e⏱ " + timeStr
        );
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(bar, true); // actionbar
        }
    }

    private static List<ServerPlayerEntity> getAlivePlayers(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().stream()
            .filter(p -> p.interactionManager.getGameMode() != GameMode.SPECTATOR)
            .toList();
    }

    private static void broadcast(MinecraftServer server, Text msg) {
        server.getPlayerManager().broadcast(msg, false);
    }
}
