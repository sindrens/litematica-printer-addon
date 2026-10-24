package no.sindrens.printeraddon.build;

import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.ServerName;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What's done of a build, on disk per server and schematic placement: the finished layers, the blocks left out (no
 * way to them) and the blocks waiting for something above. After a restart auto build goes on at the first layer
 * that isn't finished, without reading every finished layer first and without going back for blocks it had already
 * left out; the finished layers are checked again in the background while it builds (see LayerManager#checked).
 * A different schematic, placement or server is a different file.
 */
public final class BuildProgress {
    /** Positions kept at most per list (more than this and the list isn't worth keeping). */
    private static final int MAX_POSITIONS = 20000;

    private BuildProgress() {
    }

    public record Saved(Set<Integer> done, List<BlockPos> skipped, List<BlockPos> deferred) {
    }

    private static String key(Minecraft mc) {
        return ServerName.current(mc) + "|" + SchematicHelper.fingerprint();
    }

    private static Path file(Minecraft mc) {
        String key = key(mc);
        return FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("progress")
                .resolve(Integer.toHexString(key.hashCode()) + "-" + Integer.toHexString(key.length()) + ".txt");
    }

    /** The saved progress for what's placed now, or null if there's none (or it's for something else). */
    @Nullable
    public static Saved load(Minecraft mc) {
        Path file = file(mc);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals("key=" + key(mc))) {
                return null;
            }
            Set<Integer> done = new TreeSet<>();
            List<BlockPos> skipped = new ArrayList<>();
            List<BlockPos> deferred = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("done=")) {
                    for (String y : line.substring(5).split(",")) {
                        if (!y.isBlank()) {
                            done.add(Integer.parseInt(y.trim()));
                        }
                    }
                } else if (line.startsWith("skipped=")) {
                    positions(line.substring(8), skipped);
                } else if (line.startsWith("deferred=")) {
                    positions(line.substring(9), deferred);
                }
            }
            return new Saved(done, skipped, deferred);
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("Couldn't read the build progress {}", file, e);
            return null;
        }
    }

    public static void save(Minecraft mc, Collection<Integer> done, Collection<BlockPos> skipped, Collection<BlockPos> deferred) {
        Path file = file(mc);
        StringBuilder sb = new StringBuilder("key=").append(key(mc)).append('\n');
        sb.append("saved=").append(System.currentTimeMillis()).append('\n');
        sb.append("done=");
        new TreeSet<>(done).forEach(y -> sb.append(y).append(','));
        sb.append('\n').append("skipped=");
        append(sb, skipped);
        sb.append('\n').append("deferred=");
        append(sb, deferred);
        sb.append('\n');
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't save the build progress", e);
        }
    }

    /** The build is finished: next time everything is read again from the bottom. */
    public static void forget(Minecraft mc) {
        try {
            Files.deleteIfExists(file(mc));
        } catch (IOException ignored) {
        }
    }

    private static void append(StringBuilder sb, Collection<BlockPos> positions) {
        int n = 0;
        for (BlockPos pos : positions) {
            if (n++ >= MAX_POSITIONS) {
                break;
            }
            sb.append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append(';');
        }
    }

    private static void positions(String text, List<BlockPos> out) {
        for (String part : text.split(";")) {
            String[] xyz = part.trim().split(" ");
            if (xyz.length == 3) {
                out.add(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
            }
        }
    }
}
