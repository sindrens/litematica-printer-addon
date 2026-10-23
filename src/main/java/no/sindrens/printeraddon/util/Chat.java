package no.sindrens.printeraddon.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;

public class Chat {
    private static final String PREFIX = "§b[Printer Addon]§r ";

    /** Status message, hidden when chat messages are turned off. */
    public static void info(String message) {
        PrinterAddon.LOGGER.info(message);
        if (Configs.Generic.CHAT_MESSAGES.getBooleanValue()) {
            send(message);
        }
    }

    /** Always shown: something stopped or needs the player's attention. */
    public static void warn(String message) {
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
