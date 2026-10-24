package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;
import no.sindrens.printeraddon.util.ServerName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Picks auto build up again after logging out or losing the connection: while it runs, the server and the schematic
 * placement are written to a file; after rejoining the same server with the same placement it starts again by
 * itself (from the first unfinished layer). Stopping it on purpose (J+B, J+Q, build finished) removes the file.
 */
public final class Resume {
    private static final int WAIT_AFTER_JOIN = 20 * 10;
    private static int joinedTicks;
    private static int saveTicks;

    private Resume() {
    }

    private static Path file() {
        return FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("resume.txt");
    }

    private static String key(Minecraft mc) {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        String place = placement == null ? "-" : placement.getName() + "@" + placement.getOrigin().toShortString();
        return ServerName.current(mc) + "|" + place;
    }

    /** While auto build runs: remember what's being built. */
    public static void whileRunning(Minecraft mc) {
        if (++saveTicks % 100 != 1) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), key(mc), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't save the resume file", e);
        }
    }

    /** Stopped on purpose: don't resume. */
    public static void forget() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException ignored) {
        }
    }

    public static void onDisconnect() {
        joinedTicks = 0;
        saveTicks = 0;
    }

    /** In game and not building: start again if this is what was being built before. */
    public static void whileIdle(Minecraft mc) {
        if (!Configs.Generic.RESUME_AFTER_REJOIN.getBooleanValue() || ++joinedTicks != WAIT_AFTER_JOIN || !Files.isRegularFile(file())) {
            return;
        }
        try {
            String saved = Files.readString(file(), StandardCharsets.UTF_8).trim();
            if (saved.equals(key(mc))) {
                Chat.info("Picking auto build up again where it stopped (J+B to stop).");
                AutoBuild.INSTANCE.start(mc);
            }
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't read the resume file", e);
        }
    }
}
