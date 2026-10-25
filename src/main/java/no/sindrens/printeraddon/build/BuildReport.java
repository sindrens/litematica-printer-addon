package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.Chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One look at the whole schematic (hotkey J+R): how far the build is, then what's wrong in the part that's built, in
 * plain words with the block that's really there: holes, wrong blocks, blocks too many, your own blocks in the way.
 * The nearest of each in chat, every one of them with the reason in config/printer_addon/report.txt. The blocks of the
 * layers not built yet are only counted (they were all "missing", 156,498 of them, next to 12 real holes).
 */
public final class BuildReport {
    private static final int IN_CHAT = 4;
    /** A layer with at least this share of its blocks in place counts as built: what's missing there is a hole. */
    private static final double BUILT_SHARE = 0.5;

    private BuildReport() {
    }

    /** What's wrong at a spot of the built part, in plain words. */
    private enum Kind {
        HOLE("holes (a block missing in a built layer)"),
        WRONG("wrong blocks (another block, or turned the wrong way)"),
        EXTRA("blocks too many (where the schematic has air)"),
        YOURS("your own blocks in the way (left alone)"),
        BUCKET("water still to go in"),
        FIX("still to click (tilling, a repeater's delay...)");

        final String words;

        Kind(String words) {
            this.words = words;
        }
    }

    public static void show(Minecraft mc) {
        WorldSchematic schematic = SchematicHelper.world();
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        if (mc.player == null || mc.level == null || schematic == null || areas.isEmpty()) {
            Chat.warn("No schematic placement found.");
            return;
        }
        int[] range = SchematicHelper.yRange(areas);
        Set<Block> kinds = SchematicHelper.blockKinds();

        // Per layer: its blocks, those in place, and what's wrong where
        Map<Integer, int[]> layers = new LinkedHashMap<>();
        Map<Integer, Map<Kind, List<BlockPos>>> wrong = new LinkedHashMap<>();
        Map<Integer, Integer> missingByLayer = new LinkedHashMap<>();
        int unloaded = 0;
        int movingPistons = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = range[0]; y <= range[1]; y++) {
            int[] counts = new int[2];
            for (SchematicHelper.Area area : areas) {
                if (y < area.minY() || y > area.maxY()) {
                    continue;
                }
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState target = schematic.getBlockState(pos);
                        if (target.getBlock() == Blocks.MOVING_PISTON) {
                            movingPistons++;
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.UNLOADED) {
                            if (!target.isAir()) {
                                unloaded++;
                            }
                            continue;
                        }
                        if (!target.isAir()) {
                            counts[0]++;
                            if (result == BlockCheck.Result.OK) {
                                counts[1]++;
                            }
                        }
                        Kind kind = switch (result) {
                            case MISSING -> Kind.HOLE;
                            case WRONG_BLOCK -> kinds.contains(mc.level.getBlockState(pos).getBlock()) ? Kind.WRONG : Kind.YOURS;
                            case WRONG_STATE -> Kind.WRONG;
                            case PROTECTED -> Kind.YOURS;
                            case EXTRA -> Kind.EXTRA;
                            case BUCKET -> Kind.BUCKET;
                            case PRINTER_FIXES -> Kind.FIX;
                            default -> null;
                        };
                        if (kind != null) {
                            wrong.computeIfAbsent(y, k -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new ArrayList<>()).add(pos.immutable());
                            if (kind == Kind.HOLE) {
                                missingByLayer.merge(y, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            layers.put(y, counts);
        }

        // The built part: up to the highest layer with most of its blocks in place
        int top = Integer.MIN_VALUE;
        int built = 0;
        int withBlocks = 0;
        long blocksAll = 0;
        long blocksPlaced = 0;
        for (Map.Entry<Integer, int[]> entry : layers.entrySet()) {
            int[] c = entry.getValue();
            blocksAll += c[0];
            blocksPlaced += c[1];
            if (c[0] > 0) {
                withBlocks++;
                if (c[1] >= c[0] * BUILT_SHARE) {
                    top = entry.getKey();
                }
            }
        }
        for (Map.Entry<Integer, int[]> entry : layers.entrySet()) {
            if (entry.getKey() <= top && entry.getValue()[0] > 0) {
                built++;
            }
        }
        long toBuild = 0;
        Map<Kind, List<BlockPos>> problems = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            problems.put(kind, new ArrayList<>());
        }
        for (Map.Entry<Integer, Map<Kind, List<BlockPos>>> entry : wrong.entrySet()) {
            int y = entry.getKey();
            for (Map.Entry<Kind, List<BlockPos>> found : entry.getValue().entrySet()) {
                if (y > top && found.getKey() == Kind.HOLE) {
                    // Not a hole: a layer not built yet
                    toBuild += found.getValue().size();
                    continue;
                }
                problems.get(found.getKey()).addAll(found.getValue());
            }
        }

        double percent = blocksAll == 0 ? 100 : blocksPlaced * 100.0 / blocksAll;
        Chat.info(String.format(Locale.ROOT, "§lBuild report:§r %.0f%% done, %,d of %,d blocks in place", percent, blocksPlaced, blocksAll));
        Chat.info(top == Integer.MIN_VALUE ? " Nothing built yet."
                : String.format(Locale.ROOT, " Built: %d of %d layers (Y %d to %d)%s", built, withBlocks, range[0], top,
                toBuild > 0 ? String.format(Locale.ROOT, ", still to build: %,d blocks above Y %d", toBuild, top) : ""));
        BlockPos me = mc.player.blockPosition();
        Comparator<BlockPos> nearest = Comparator.comparingDouble(p -> p.distSqr(me));
        boolean any = false;
        for (Map.Entry<Kind, List<BlockPos>> entry : problems.entrySet()) {
            List<BlockPos> list = entry.getValue();
            if (list.isEmpty()) {
                continue;
            }
            any = true;
            list.sort(nearest);
            StringBuilder line = new StringBuilder(" " + (entry.getKey() == Kind.YOURS || entry.getKey() == Kind.EXTRA ? "§e" : "§c")
                    + String.format(Locale.ROOT, "%,d", list.size()) + "§r " + entry.getKey().words + ", nearest:");
            for (int i = 0; i < Math.min(IN_CHAT, list.size()); i++) {
                line.append(i == 0 ? " " : "; ").append(describe(mc, schematic, list.get(i), entry.getKey()));
            }
            Chat.info(line.toString());
        }
        if (!any && top != Integer.MIN_VALUE) {
            Chat.info(" §aNo holes or wrong blocks in the built part.");
        }
        if (unloaded > 0) {
            Chat.info(String.format(Locale.ROOT, " %,d blocks are too far away to check (fly closer).", unloaded));
        }
        if (movingPistons > 0) {
            Chat.info(" " + movingPistons + " spot" + (movingPistons == 1 ? " has" : "s have")
                    + " a moving piston in the schematic (saved mid-move): nothing to place there.");
        }
        write(mc, schematic, problems, missingByLayer, top, percent, blocksPlaced, blocksAll, toBuild);
    }

    /** "piston at -1747 18 1232", "pumpkin where pumpkin_stem goes at ...", "pumpkin_stem at ... (should be air)". */
    private static String describe(Minecraft mc, WorldSchematic schematic, BlockPos p, Kind kind) {
        String at = " at " + p.getX() + " " + p.getY() + " " + p.getZ();
        String there = LayerManager.blockName(mc.level.getBlockState(p));
        String wanted = LayerManager.blockName(schematic.getBlockState(p));
        return switch (kind) {
            case HOLE, BUCKET, FIX -> wanted + at;
            case EXTRA -> there + at + " (should be air)";
            case WRONG, YOURS -> there.equals(wanted) ? wanted + " turned wrong" + at : there + " where " + wanted + " goes" + at;
        };
    }

    private static void write(Minecraft mc, WorldSchematic schematic, Map<Kind, List<BlockPos>> problems, Map<Integer, Integer> missingByLayer,
                              int top, double percent, long placed, long all, long toBuild) {
        List<String> lines = new ArrayList<>();
        lines.add("Build report, " + java.time.LocalDateTime.now().withNano(0));
        lines.add(String.format(Locale.ROOT, "%.1f%% done: %,d of %,d blocks in place. Built up to Y %s, %,d blocks still to build above it.",
                percent, placed, all, top == Integer.MIN_VALUE ? "-" : String.valueOf(top), toBuild));
        StringBuilder holes = new StringBuilder();
        missingByLayer.forEach((y, count) -> {
            if (y <= top) {
                holes.append(holes.isEmpty() ? "" : ", ").append("Y ").append(y).append(": ").append(count);
            }
        });
        lines.add("Holes per built layer: " + (holes.isEmpty() ? "none" : holes));
        problems.forEach((kind, list) -> {
            if (list.isEmpty()) {
                return;
            }
            lines.add("");
            lines.add(kind.words + ": " + list.size());
            for (BlockPos p : list) {
                lines.add("  " + describe(mc, schematic, p, kind) + " | " + Diagnose.explain(mc, schematic, p));
            }
        });
        Path file = FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("report.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
            Chat.info(" Every spot with the reason: config/printer_addon/report.txt");
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't write the build report", e);
        }
    }
}
