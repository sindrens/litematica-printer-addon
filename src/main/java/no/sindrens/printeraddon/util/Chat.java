package no.sindrens.printeraddon.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;

public class Chat {
    private static final String PREFIX = "§b[Printer Addon]§r ";

    /** The last messages, for the status file. */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();

    public static synchronized java.util.List<String> recent() {
        return java.util.List.copyOf(RECENT);
    }

    private static synchronized void remember(String message) {
        String clean = message.replaceAll("\u00a7.", "");
        String time = java.time.LocalTime.now().withNano(0).toString();
        RECENT.addFirst(time + " " + clean);
        while (RECENT.size() > 8) {
            RECENT.removeLast();
        }
    }

    /** A step of auto build, in grey (only with "Detailed chat"). */
    public static void detail(String message) {
        remember(message);
        send("\u00a77" + message);
    }

    /** Status message, hidden when chat messages are turned off. */
    public static void info(String message) {
        remember(message);
        no.sindrens.printeraddon.build.BuildLog.line("info", message);
        PrinterAddon.LOGGER.info(message);
        if (Configs.Generic.CHAT_MESSAGES.getBooleanValue()) {
            send(message);
        }
    }

    /**
     * Routine progress (took items from storage, crafted, a block type's speed): in the build log, in chat only with
     * "Detailed chat". Chat is for what matters: what the build is doing now, and anything that needs the player.
     */
    public static void quiet(String message) {
        remember(message);
        no.sindrens.printeraddon.build.BuildLog.line("info", message);
        PrinterAddon.LOGGER.info(message);
        if (Configs.Generic.DETAILED_CHAT.getBooleanValue()) {
            send("\u00a77" + message);
        }
    }

    private static final java.util.Map<String, Long> LAST_SHOWN = new java.util.HashMap<>();

    /** A warning that can repeat (a lagging server, a missing bucket): in chat at most once per {@code everyMs}, always in the build log. */
    public static void warnEvery(String key, long everyMs, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_SHOWN.get(key);
        if (last != null && now - last < everyMs) {
            remember(message);
            no.sindrens.printeraddon.build.BuildLog.line("WARN", message);
            return;
        }
        LAST_SHOWN.put(key, now);
        warn(message);
    }

    /** Always shown: something stopped or needs the player's attention. */
    public static void warn(String message) {
        remember(message);
        no.sindrens.printeraddon.build.BuildLog.line("WARN", message);
        PrinterAddon.LOGGER.warn(message);
        send("§e" + message);
    }

    /**
     * Big text in the middle of the screen plus a sound, for things the player must notice even while looking
     * around (build finished, auto build stopped by itself).
     */
    public static void announce(String title, String subtitle, boolean good) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.gui.hud.setTimes(10, 70, 20);
        mc.gui.hud.setSubtitle(Component.literal(subtitle));
        mc.gui.hud.setTitle(Component.literal((good ? "§a" : "§e") + title));
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                good ? net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.value(), 1.0f));
    }

    public static void actionBar(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.hud.setOverlayMessage(Component.literal(message), false);
    }

    private static void send(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal(PREFIX + message));
        }
    }
}
