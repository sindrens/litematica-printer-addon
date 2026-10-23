package no.sindrens.printeraddon.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

public final class ServerName {
    private ServerName() {
    }

    /** The current server's address (or "singleplayer"), safe to use as a file name. */
    public static String current(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        String name = server != null ? server.ip : "singleplayer";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
