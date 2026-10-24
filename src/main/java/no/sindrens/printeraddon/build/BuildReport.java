package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.Chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One look at the whole schematic: how many spots are done, missing, wrong or not loaded, the nearest ones of each
 * in chat, and every one of them in config/printer_addon/report.txt with the reason (hotkey J+R).
 */
public final class BuildReport {
    private static final int IN_CHAT = 5;

    private BuildReport() {
    }

    public static void show(Minecraft mc) {
        WorldSchematic schematic = SchematicHelper.world();
        if (mc.player == null || mc.level == null || schematic == null || SchematicHelper.areas().isEmpty()) {
            Chat.warn("No schematic placement found.");
            return;
        }

        Map<BlockCheck.Result, List<BlockPos>> found = new EnumMap<>(BlockCheck.Result.class);
        int movingPistons = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState target = schematic.getBlockState(pos);
                        if (target.getBlock() == Blocks.MOVING_PISTON) {
                            movingPistons++;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result != BlockCheck.Result.OK && !(result == BlockCheck.Result.UNLOADED && target.isAir())) {
                            found.computeIfAbsent(result, r -> new ArrayList<>()).add(pos.immutable());
                        }
                    }
                }
            }
        }

        BlockPos me = mc.player.blockPosition();
        Comparator<BlockPos> nearest = Comparator.comparingDouble(p -> p.distSqr(me));
        if (found.isEmpty()) {
            Chat.info("Build report: everything matches the schematic.");
        } else {
            StringBuilder summary = new StringBuilder("Build report:");
            found.forEach((result, list) -> summary.append(' ').append(list.size()).append(' ').append(words(result)).append(','));
            summary.setLength(summary.length() - 1);
            Chat.info(summary.toString());
            for (Map.Entry<BlockCheck.Result, List<BlockPos>> entry : found.entrySet()) {
                if (entry.getKey() == BlockCheck.Result.UNLOADED) {
                    continue;
                }
                List<BlockPos> list = entry.getValue();
                list.sort(nearest);
                StringBuilder line = new StringBuilder(" " + words(entry.getKey()) + ", nearest:");
                for (int i = 0; i < Math.min(IN_CHAT, list.size()); i++) {
                    BlockPos p = list.get(i);
                    line.append(' ').append(Diagnose.BlockText.of(schematic.getBlockState(p)).replaceAll("\\[.*", ""))
                            .append(" at ").append(p.toShortString()).append(';');
                }
                Chat.info(line.toString());
            }
        }
        if (movingPistons > 0) {
            Chat.info(movingPistons + " spot" + (movingPistons == 1 ? " has" : "s have")
                    + " a moving piston in the schematic (saved mid-move). Nothing can be placed there, so they count as done,"
                    + " but Litematica shows them as missing.");
        }
        write(mc, schematic, found, movingPistons);
    }

    private static void write(Minecraft mc, WorldSchematic schematic, Map<BlockCheck.Result, List<BlockPos>> found, int movingPistons) {
        List<String> lines = new ArrayList<>();
        lines.add("Build report, " + java.time.LocalDateTime.now().withNano(0));
        lines.add("Moving pistons in the schematic (nothing to place): " + movingPistons);
        found.forEach((result, list) -> {
            lines.add("");
            lines.add(words(result) + ": " + list.size());
            if (result == BlockCheck.Result.UNLOADED) {
                lines.add("  (not loaded on your side: fly closer to check them)");
                return;
            }
            for (BlockPos p : list) {
                lines.add("  " + Diagnose.explain(mc, schematic, p));
            }
        });
        Path file = FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("report.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
            Chat.info("Every spot with the reason: config/printer_addon/report.txt");
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't write the build report", e);
        }
    }

    private static String words(BlockCheck.Result result) {
        return switch (result) {
            case MISSING -> "missing";
            case WRONG_BLOCK -> "wrong block";
            case WRONG_STATE -> "facing or state wrong";
            case PRINTER_FIXES -> "printer will fix by clicking";
            case EXTRA -> "extra block where air should be";
            case PROTECTED -> "wrong but protected";
            case UNLOADED -> "not loaded";
            case BUCKET -> "need a bucket";
            default -> result.name().toLowerCase().replace('_', ' ');
        };
    }
}
