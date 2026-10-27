package no.sindrens.printeraddon;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Which build this is. "official" (published on Modrinth): never faster or farther than a vanilla player (flying at
 * most 0.55 blocks a tick, the vanilla reach, 5 ticks between mined blocks, at most one placement every 4 ticks).
 * "mythian" (the unofficial build for the Mythian server, not published): every speed is the player's own choice.
 * Set at build time (-Pedition=official), read from fabric.mod.json.
 */
public final class Edition {
    public static final boolean OFFICIAL = read().equals("official");

    private Edition() {
    }

    private static String read() {
        try {
            return FabricLoader.getInstance().getModContainer(PrinterAddon.MOD_ID)
                    .map(m -> m.getMetadata().getCustomValue("printer_addon:edition"))
                    .map(v -> v.getAsString())
                    .orElse("mythian");
        } catch (RuntimeException | LinkageError e) {
            return "mythian";
        }
    }

    public static String name() {
        return OFFICIAL ? "official" : "Mythian";
    }
}
