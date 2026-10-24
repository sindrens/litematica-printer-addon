package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

/**
 * Measures the server's ticks per second from its time updates (one a second). When the server lags, its answers
 * to clicks come late and the printer clicks spots again or with the wrong view, so placing waits until it recovers.
 */
public final class ServerLag {
    private static long lastGameTime = -1;
    private static long lastNanos;
    private static double tps = 20;
    private static boolean paused;

    private ServerLag() {
    }

    /** Called for every time update from the server. */
    public static void onTime(long gameTime) {
        long now = System.nanoTime();
        // A long silence (the server stuck): in the build log, to tell its "Timed out" from the add-on's doing
        if (lastGameTime >= 0 && now - lastNanos > 2_500_000_000L) {
            BuildLog.line("LAG", String.format(java.util.Locale.ROOT, "no word from the server for %.1f s (it ran at %.0f ticks per second before)",
                    (now - lastNanos) / 1e9, tps));
        }
        if (lastGameTime >= 0 && gameTime > lastGameTime) {
            double seconds = (now - lastNanos) / 1e9;
            if (seconds > 0.2) {
                double measured = Math.min(20, (gameTime - lastGameTime) / seconds);
                // Smooth over a few updates so one late packet doesn't pause anything
                tps = tps * 0.6 + measured * 0.4;
            }
        }
        lastGameTime = gameTime;
        lastNanos = now;
    }

    /** A round trip to the server in ticks, from the player's ping (at least 2). */
    public static int pingTicks(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            return 2;
        }
        net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 2 : Math.max(2, info.getLatency() / 50 + 1);
    }

    public static double tps() {
        return tps;
    }

    /** True while placing should wait for the server. */
    public static boolean shouldWait(Minecraft mc) {
        int below = Configs.Speed.LAG_PAUSE_TPS.getIntegerValue();
        if (below <= 0 || mc.level == null || mc.level.tickRateManager().isFrozen() || mc.hasSingleplayerServer()) {
            return false;
        }
        // No time update for 3 seconds: the server is stuck
        boolean silent = lastGameTime >= 0 && System.nanoTime() - lastNanos > 3_000_000_000L;
        boolean wait = silent || tps < below;
        if (wait != paused) {
            paused = wait;
            if (wait) {
                Chat.warnEvery("lag", 5 * 60_000L, String.format("The server lags (%.0f ticks per second): placing waits until it catches up.", silent ? 0 : tps));
            } else {
                Chat.quiet(String.format("The server is back to %.0f ticks per second: placing again.", tps));
            }
        }
        return wait;
    }

    /** The server's state just now, for the build log when the connection ends. */
    public static String describe() {
        if (lastGameTime < 0) {
            return "no time updates from the server yet";
        }
        return String.format(java.util.Locale.ROOT, "server at %.0f ticks per second, its last time update %.1f s before", tps,
                (System.nanoTime() - lastNanos) / 1e9);
    }

    public static void reset() {
        lastGameTime = -1;
        tps = 20;
        paused = false;
    }
}
