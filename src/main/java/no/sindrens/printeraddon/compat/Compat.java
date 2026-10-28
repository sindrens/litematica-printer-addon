package no.sindrens.printeraddon.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.Chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks the mods around the add-on once, at the first world joined: the versions it was built and tested with, and
 * mods known to get in the way. Said in chat only when something is off; everything goes to the game log.
 */
public final class Compat {
    /** The versions this release was tested with (gradle.properties). */
    private static final String TESTED_PRINTER = "3.2.2";
    private static final String TESTED_LITEMATICA = "0.28.8";
    private static final String TESTED_MALILIB = "0.29.6";

    private static boolean checked;

    private Compat() {
    }

    public static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    public static boolean loaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    public static void checkOnce() {
        if (checked) {
            return;
        }
        checked = true;
        List<String> notes = new ArrayList<>();
        String printer = version("litematica_printer");
        String litematica = version("litematica");
        String malilib = version("malilib");
        PrinterAddon.LOGGER.info("[Printer Addon] with Litematica Printer {}, Litematica {}, MaLiLib {}, Sodium {}, Iris {}",
                printer, litematica, malilib, orDash(version("sodium")), orDash(version("iris")));
        if (!printer.isEmpty() && !printer.startsWith(TESTED_PRINTER)) {
            notes.add("Litematica Printer " + printer + " (tested with " + TESTED_PRINTER + "): if placing acts up, use " + TESTED_PRINTER);
        }
        if (!litematica.isEmpty() && !litematica.contains(TESTED_LITEMATICA)) {
            notes.add("Litematica " + litematica + " (tested with " + TESTED_LITEMATICA + ")");
        }
        if (!malilib.isEmpty() && !malilib.contains(TESTED_MALILIB)) {
            notes.add("MaLiLib " + malilib + " (tested with " + TESTED_MALILIB + ")");
        }
        // Mods that move the player or the camera themselves: both at once fight over the keys
        for (String[] other : new String[][]{{"baritone", "Baritone"}, {"freecam", "Freecam"}, {"freelook", "Freelook"},
                {"perspective", "Perspective"}}) {
            if (loaded(other[0])) {
                notes.add(other[1] + " is installed: don't use it while auto build runs (it moves the player or camera too)");
            }
        }
        // Tweakeroo's fast placement on top of the printer places too fast for most servers
        if (loaded("tweakeroo")) {
            PrinterAddon.LOGGER.info("[Printer Addon] Tweakeroo found: keep its fast/flexible block placement off while building");
        }
        if (loaded("sodium") || loaded("iris")) {
            // The add-on draws only through the game's own HUD and Litematica's overlay: nothing Sodium or Iris replace
            PrinterAddon.LOGGER.info("[Printer Addon] Sodium/Iris found: supported (the add-on draws nothing of its own in the world)");
        }
        for (String note : notes) {
            Chat.warn("Printer Addon: " + note + ".");
        }
    }

    private static String orDash(String s) {
        return s.isEmpty() ? "-" : s;
    }

    public static Optional<ModContainer> self() {
        return FabricLoader.getInstance().getModContainer(PrinterAddon.MOD_ID);
    }
}
