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
                Chat.warn(String.format("The server lags (%.0f ticks per second): placing waits until it catches up.", silent ? 0 : tps));
            } else {
                Chat.info(String.format("The server is back to %.0f ticks per second: placing again.", tps));
            }
        }
        return wait;
    }

    public static void reset() {
        lastGameTime = -1;
        tps = 20;
        paused = false;
    }
}
