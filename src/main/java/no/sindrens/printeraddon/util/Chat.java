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
        PrinterAddon.LOGGER.info(message);
        if (Configs.Generic.CHAT_MESSAGES.getBooleanValue()) {
            send(message);
        }
    }

    /** Always shown: something stopped or needs the player's attention. */
    public static void warn(String message) {
        remember(message);
        PrinterAddon.LOGGER.warn(message);
        send("§e" + message);
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
