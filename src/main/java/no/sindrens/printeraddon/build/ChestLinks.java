package no.sindrens.printeraddon.build;

import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.ServerName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The chests (barrels, shulker boxes) the player linked for taking materials from, per server, in
 * config/printer_addon/chests.txt. Only these are ever opened.
 */
public final class ChestLinks {
    private ChestLinks() {
    }

    private static Path file() {
        return FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("chests.txt");
    }

    /** The linked chests on this server. */
    public static Set<BlockPos> list(Minecraft mc) {
        Set<BlockPos> linked = new LinkedHashSet<>();
        String server = ServerName.current(mc);
        for (String line : lines()) {
            String[] parts = line.trim().split(" ");
            if (parts.length == 4 && parts[0].equals(server)) {
                try {
                    linked.add(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return linked;
    }

    /** Links the chest, or unlinks it if it was linked. Returns true if it's linked now. */
    public static boolean toggle(Minecraft mc, BlockPos pos) {
        String line = ServerName.current(mc) + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        List<String> lines = lines();
        boolean linked = !lines.remove(line);
        if (linked) {
            lines.add(line);
        }
        save(lines);
        return linked;
    }

    /** Unlinks every chest on this server. */
    public static void clear(Minecraft mc) {
        String server = ServerName.current(mc) + " ";
        List<String> lines = lines();
        lines.removeIf(line -> line.startsWith(server));
        save(lines);
    }

    private static List<String> lines() {
        try {
            return Files.isRegularFile(file()) ? new ArrayList<>(Files.readAllLines(file())) : new ArrayList<>();
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't read {}", file(), e);
            return new ArrayList<>();
        }
    }

    private static void save(List<String> lines) {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), lines);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't save {}", file(), e);
        }
    }
}
