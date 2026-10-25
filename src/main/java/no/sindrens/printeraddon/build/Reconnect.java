package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Joins the server again by itself when it kicks you or restarts while auto build runs: 10 s after the
 * "Disconnected" screen shows, then 20 s, 30 s and every minute, for half an hour; {@link Resume} then goes on with
 * the build once you're back at it. Never after a ban, nor when you left yourself (there's no "Disconnected" screen
 * then); "Back to server list" on that screen cancels it. Kicked again 3 times within 10 minutes of joining again:
 * it stops trying.
 */
public final class Reconnect {
    private static final int[] DELAY_SECONDS = {10, 20, 30, 60};
    private static final long GIVE_UP_AFTER = 20L * 60 * 30;
    private static final long KICKS_WINDOW = 20L * 60 * 10;
    private static final int MAX_KICKS = 3;

    /** The server to join again (null: nothing to do). */
    @Nullable
    private static ServerData server;
    private static long endedAt;
    private static long nextTry;
    private static int tries;
    /** When joining again by itself worked lately: kicked again soon after, too often, it stops. */
    private static final Deque<Long> rejoins = new ArrayDeque<>();
    /** When it last joined again by itself (-1: not since the game started). */
    private static long joinedAt = -1;
    /** The server of the world the player is in (the game forgets it as the connection ends). */
    @Nullable
    private static ServerData current;

    /** Every tick in a world: the server to join again if the connection ends. */
    public static void remember(Minecraft mc) {
        if (mc.getCurrentServer() != null) {
            current = mc.getCurrentServer();
        }
    }

    /** A reason that isn't a kick: the connection or the server gave out (these don't count as kicked). */
    private static boolean connectionLost(String reason) {
        return reason.toLowerCase(Locale.ROOT).matches("(?s).*(timed out|timeout|connection (reset|lost|refused|closed)|server closed"
                + "|restart|end of stream|disconnected|\\?).*");
    }

    /** A join again is planned (or under way). */
    public static boolean planned() {
        return server != null;
    }

    private Reconnect() {
    }

    /** The connection ended while auto build ran (from the disconnect, before the game forgets the server). */
    public static void connectionEnded(Minecraft mc, String reason) {
        server = null;
        if (!Configs.Generic.RECONNECT.getBooleanValue() || mc.isLocalServer()) {
            return;
        }
        ServerData data = mc.getCurrentServer() != null ? mc.getCurrentServer() : current;
        if (data == null || data.ip == null || data.ip.isBlank()) {
            BuildLog.lineOrKeep("RECONNECT", "not joining again: the server's address isn't known");
            return;
        }
        if (reason.toLowerCase(Locale.ROOT).matches("(?s).*\\bban(ned)?\\b.*")) {
            BuildLog.lineOrKeep("RECONNECT", "not joining again: \"" + reason + "\" sounds like a ban");
            return;
        }
        long now = AutoBuild.clientTicks;
        rejoins.removeIf(at -> now - at > KICKS_WINDOW);
        if (connectionLost(reason)) {
            // Timed out (the server stuck for a while), not kicked: always joined again
            rejoins.clear();
        } else if (rejoins.size() >= MAX_KICKS) {
            BuildLog.lineOrKeep("RECONNECT", "not joining again: kicked " + rejoins.size() + " times within 10 minutes of joining again");
            return;
        }
        server = data;
        endedAt = now;
        tries = 0;
        nextTry = now + 20L * DELAY_SECONDS[0];
        BuildLog.lineOrKeep("RECONNECT", "joining " + data.ip + " again in " + DELAY_SECONDS[0] + " s (the connection ended: " + reason
                + "). 'Back to server list' cancels it");
    }

    /** Every tick while not in a world: the next try once it's time, on the "Disconnected" screen. */
    public static void whileOut(Minecraft mc) {
        if (server == null) {
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen instanceof ConnectScreen) {
            return;
        }
        if (screen instanceof JoinMultiplayerScreen || screen instanceof TitleScreen) {
            // Left the "Disconnected" screen yourself
            BuildLog.lineOrKeep("RECONNECT", "cancelled (back to the server list)");
            server = null;
            return;
        }
        long now = AutoBuild.clientTicks;
        if (now - endedAt > GIVE_UP_AFTER) {
            BuildLog.lineOrKeep("RECONNECT", "gave up after " + tries + " tries in 30 minutes");
            server = null;
            return;
        }
        if (!(screen instanceof DisconnectedScreen) || now < nextTry) {
            return;
        }
        tries++;
        nextTry = now + 20L * DELAY_SECONDS[Math.min(tries, DELAY_SECONDS.length - 1)];
        BuildLog.lineOrKeep("RECONNECT", "joining " + server.ip + " again (try " + tries + ")");
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(server.ip), server,
                false, null);
    }

    /** Every tick in a world: joined again by itself ({@link Resume} gets back to the build and goes on). */
    public static void whileIn(Minecraft mc) {
        if (server != null) {
            BuildLog.lineOrKeep("RECONNECT", "joined " + server.ip + " again (try " + tries + ")");
            Chat.info("Joined the server again: going on with auto build once back at the build.");
            no.sindrens.printeraddon.discord.DiscordLink.event("rejoined", "Joined the server again, going on with the build.");
            rejoins.addLast(AutoBuild.clientTicks);
            server = null;
            joinedAt = AutoBuild.clientTicks;
        }
    }

    /** J+Q: no joining again. */
    public static void cancel() {
        if (server != null) {
            BuildLog.lineOrKeep("RECONNECT", "cancelled (J+Q)");
            server = null;
        }
    }

    /** Ticks since it last joined again by itself, or -1. */
    public static long sinceJoinedAgain() {
        return joinedAt < 0 ? -1 : AutoBuild.clientTicks - joinedAt;
    }
}
