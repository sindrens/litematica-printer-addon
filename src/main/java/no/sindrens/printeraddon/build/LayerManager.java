package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds bottom-up: sets Litematica to a single Y layer and moves to the next layer once it's done.
 */
public class LayerManager {
    /** How many positions to list in chat when a layer is stuck. */
    private static final int MAX_REPORTED = 12;

    private boolean active;
    private int layer;
    /** Top of the layers being built together: the same as {@link #layer}, or one more for farmland + crops. */
    private int layerTop;
    private int minY;
    private int maxY;
    private int ticksUntilCheck;
    private int completeChecks;
    private int lastRemaining = -1;
    private int ticksWithoutProgress;
    private int lastPlaced = -1;
    private int lastReportAt;
    private boolean reportedStuck;
    /** Layer and blocks left, for the status line. */
    private String status = "";
    private int waitingChecks;
    /** All layers are done; only the deferred blocks are left. */
    private boolean finishingDeferred;
    private boolean retriedSkipped;

    /** Litematica's layer settings before auto build, put back afterwards. */
    @Nullable
    private LayerMode previousMode;
    private Direction.Axis previousAxis;
    private int previousSingle;

    public boolean isActive() {
        return active;
    }

    /** Items the current layers still need (item -> count). */
    private java.util.Map<net.minecraft.world.item.Item, Integer> layerNeeds = java.util.Map.of();

    public java.util.Map<net.minecraft.world.item.Item, Integer> layerNeeds() {
        return layerNeeds;
    }

    /** The highest Y of the layers being built now. */
    public int getLayerTop() {
        return layerTop;
    }

    public int getLayer() {
        return layer;
    }

    /** Starts at the lowest unfinished layer. Returns false if there is no schematic placed. */
    public boolean start(Minecraft mc) {
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        int[] range = SchematicHelper.yRange(areas);
        WorldSchematic schematic = SchematicHelper.world();

        if (range == null || schematic == null || mc.level == null) {
            Chat.warn("No schematic placement found. Load and place a schematic in Litematica first.");
            return false;
        }

        minY = range[0];
        maxY = range[1];
        layer = minY;

        while (layer <= maxY && scan(mc.level, schematic, areas, layer, null) == 0) {
            layer++;
        }

        if (layer > maxY) {
            Chat.info("Every layer is already finished.");
            return false;
        }
        if (layer > minY) {
            Chat.info("Layers Y " + minY + " to " + (layer - 1) + " are already done: going on at Y " + layer + ".");
        }

        layerTop = topFor(mc.level, schematic, areas, layer);

        LayerRange layerRange = DataManager.getRenderLayerRange();
        previousMode = layerRange.getLayerMode();
        previousAxis = layerRange.getAxis();
        previousSingle = layerRange.getLayerSingle();

        active = true;
        applyLayer();
        resetProgress();
        Chat.info("Building layer by layer from Y " + layer + " (layers " + minY + " to " + maxY + ").");
        return true;
    }

    /** True when the current layer hasn't changed for this many ticks. */
    /** Check the layer again within a few ticks (e.g. after water went in). */
    public void checkSoon() {
        ticksUntilCheck = Math.min(ticksUntilCheck, 1);
    }

    /**
     * True while water still has to go in near this block of the current layer (waterlogged blocks, water, within
     * 8 to 16 blocks): the water blocks come first, everything around them after (farmland is wet from the start,
     * no water flows over placed blocks). Water far away doesn't hold anything up here.
     */
    public boolean waterFirst(BlockPos pos) {
        if (!active || pos.getY() < layer || pos.getY() > layerTop) {
            return false;
        }
        return waterPending < 0 || waterCells.contains(waterCell(pos.getX() >> 3, pos.getZ() >> 3));
    }

    private static long waterCell(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** -1 until the layer was checked once; else the unfinished water blocks of the layer. */
    private int waterPending;
    /** 8x8 cells with unfinished water in or next to them. */
    private java.util.Set<Long> waterCells = java.util.Set.of();

    public boolean stalledFor(int ticks) {
        return active && ticksWithoutProgress >= ticks;
    }

    public void stop() {
        finishingDeferred = false;
        retriedSkipped = false;
        if (active) {
            restoreLayerRange();
        }
        active = false;
    }

    /** The player's own layer view from before auto build changed it, as text (for the resume file). */
    public String savedRange() {
        return previousMode == null ? "" : previousMode.name() + "|" + previousAxis.name() + "|" + previousSingle;
    }

    /**
     * After resuming: the view Litematica loaded on rejoining is the one auto build had set (it saved its settings
     * while building), so put back the player's own view from the resume file when the build ends.
     */
    public void useSavedRange(String text) {
        String[] parts = text.split("\\|");
        if (!active || parts.length != 3) {
            return;
        }
        try {
            previousMode = LayerMode.valueOf(parts[0]);
            previousAxis = Direction.Axis.valueOf(parts[1]);
            previousSingle = Integer.parseInt(parts[2]);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void restoreLayerRange() {
        if (previousMode == null) {
            return;
        }
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerSingle(previousSingle);
        if (range.getAxis() != previousAxis) {
            range.setAxis(previousAxis);
        }
        range.setLayerMode(previousMode, false);
        previousMode = null;
    }

    /** Runs one tick. Returns false when the whole build is finished. */
    public boolean tick(Minecraft mc) {
        if (!active || mc.level == null) {
            return true;
        }

        if (--ticksUntilCheck > 0) {
            return true;
        }
        // At least every 2 seconds: a check reads the whole layer
        int interval = Math.max(40, Configs.Layers.LAYER_CHECK_INTERVAL.getIntegerValue());
        ticksUntilCheck = interval;

        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return true;
        }

        List<SchematicHelper.Area> areas = SchematicHelper.areas();

        if (finishingDeferred) {
            return finishDeferred(mc, schematic, areas, interval);
        }

        Findings findings = new Findings();
        int remaining = scanLayers(mc.level, schematic, areas, null, findings);
        if (waterPending > 0 && findings.water == 0 && remaining > 0) {
            Chat.info("The water on " + describeLayers() + " is in: now the rest of the layer.");
        }
        waterPending = findings.water;
        waterCells = findings.waterCells;
        BuildLog.line("LAYER", describeLayers() + ": " + remaining + " unfinished (" + findings.waiting.size()
                + " waiting for something above, " + findings.water + " water still to go in) | conflicts "
                + AutoBuild.INSTANCE.conflicts.size() + " | still needs " + findings.needs);
        if (findings.needsHoe && mc.player != null && !StateRules.hasUsableHoe(mc.player.getInventory())) {
            findings.needs.put(net.minecraft.world.item.Items.DIAMOND_HOE, mc.player.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND_HOE) + 1);
        }
        updateShoppingList(mc, findings.needs);
        layerNeeds = findings.needs;

        // Everything left waits for something higher up (an ascending rail, an observer watching a block above):
        // don't hold up the layer, place those later
        if (remaining > 0 && remaining == findings.waiting.size()) {
            if (++waitingChecks >= 3) {
                AutoBuild.INSTANCE.deferred.addAll(findings.waiting);
                Chat.info(remaining + " block" + (remaining == 1 ? "" : "s") + " in " + describeLayers()
                        + " wait for blocks above; placed as soon as those are there.");
                remaining = 0;
                completeChecks = 2;
            }
        } else {
            waitingChecks = 0;
        }

        if (remaining == 0) {
            // Check twice in a row so a block the server hasn't confirmed yet doesn't count
            if (++completeChecks < 2) {
                return true;
            }

            Chat.info(describeLayers() + " done.");
            AutoBuild.INSTANCE.stats.layerDone(describeLayers());
            AutoBuild.INSTANCE.speed.newLayer();
            layer = layerTop;
            do {
                layer++;
            } while (layer <= maxY && scan(mc.level, schematic, areas, layer, null) == 0);

            if (layer > maxY) {
                if (!retriedSkipped && !AutoBuild.INSTANCE.couldNotPlace.isEmpty()) {
                    // One more go at what was skipped: with everything around it built, it often fits now
                    retriedSkipped = true;
                    Chat.info("Trying the " + AutoBuild.INSTANCE.couldNotPlace.size() + " skipped blocks again.");
                    AutoBuild.INSTANCE.deferred.addAll(AutoBuild.INSTANCE.couldNotPlace);
                    AutoBuild.INSTANCE.couldNotPlace.clear();
                }
                if (!unfinishedDeferred(mc.level, schematic).isEmpty()) {
                    // All layers done; place what waited, with the whole schematic visible
                    finishingDeferred = true;
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL, false);
                    resetProgress();
                    return true;
                }
                Chat.info("\u00a7aBuild finished!");
                Chat.announce("Build finished", "everything in the schematic is placed", true);
                Chat.info(AutoBuild.INSTANCE.stats.total());
                reportUnfinished(mc, schematic);
                stop();
                return false;
            }

            layerTop = topFor(mc.level, schematic, areas, layer);
            applyLayer();
            resetProgress();
            return true;
        }

        completeChecks = 0;

        int placed = AutoBuild.INSTANCE.stats.totalPlaced();
        if (remaining != lastRemaining || placed != lastPlaced) {
            lastRemaining = remaining;
            lastPlaced = placed;
            ticksWithoutProgress = 0;
            reportedStuck = false;
        } else {
            ticksWithoutProgress += interval;
        }

        status = describeLayers() + ": " + remaining + " left";

        // Report when nothing has happened for a while, and again every minute after that
        int reportAfter = Configs.Layers.STUCK_REPORT_SECONDS.getIntegerValue() * 20;
        if (ticksWithoutProgress >= reportAfter && (!reportedStuck || ticksWithoutProgress - lastReportAt >= 20 * 60)) {
            reportedStuck = true;
            lastReportAt = ticksWithoutProgress;
            reportStuck(mc, schematic, areas);
        }

        // No skipping: a layer is finished before the next one starts. When nothing happens, say exactly why.
        return true;
    }

    private boolean finishDeferred(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas, int interval) {
        List<BlockPos> left = unfinishedDeferred(mc.level, schematic);
        if (left.isEmpty()) {
            Chat.info("\u00a7aBuild finished!");
                Chat.announce("Build finished", "everything in the schematic is placed", true);
            AutoBuild.INSTANCE.stats.layerDone("Last blocks");
            Chat.info(AutoBuild.INSTANCE.stats.total());
            reportUnfinished(mc, schematic);
            stop();
            return false;
        }

        if (left.size() != lastRemaining) {
            lastRemaining = left.size();
            ticksWithoutProgress = 0;
            reportedStuck = false;
        } else {
            ticksWithoutProgress += interval;
        }
        status = "Last blocks: " + left.size() + " left";

        if (!reportedStuck && ticksWithoutProgress >= Configs.Layers.STUCK_REPORT_SECONDS.getIntegerValue() * 20) {
            reportedStuck = true;
            Chat.warn("The last " + left.size() + " blocks haven't changed for a while, for example:");
            for (BlockPos pos : left.subList(0, Math.min(MAX_REPORTED, left.size()))) {
                BlockState target = schematic.getBlockState(pos);
                Chat.warn(" - " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": "
                        + describe(mc, BlockCheck.check(mc.level, schematic, pos), target));
            }
        }

        if (ticksWithoutProgress >= Configs.Layers.SKIP_STUCK_SECONDS.getIntegerValue() * 20 * 2) {
            // Nothing more happens: finish and list what's left
            AutoBuild.INSTANCE.deferred.removeAll(left);
            AutoBuild.INSTANCE.couldNotPlace.addAll(left);
            AutoBuild.INSTANCE.stats.layerDone("Last blocks");
            Chat.info(AutoBuild.INSTANCE.stats.total());
            Chat.warn("Build done, except " + left.size() + " block" + (left.size() == 1 ? "" : "s")
                    + " that couldn't be placed (listed below).");
            reportUnfinished(mc, schematic);
            stop();
            return false;
        }
        return true;
    }

    public String status() {
        return status;
    }

    public boolean isFinishingDeferred() {
        return finishingDeferred;
    }

    public void moveLayer(int amount) {
        if (!active) {
            LayerRange range = DataManager.getRenderLayerRange();
            range.setLayerMode(LayerMode.SINGLE_LAYER, false);
            range.setAxis(Direction.Axis.Y);
            range.setLayerSingle(range.getLayerSingle() + amount);
            Chat.actionBar("Layer Y " + range.getLayerSingle());
            return;
        }
        layer = Math.max(minY, Math.min(maxY, layer + amount));
        WorldSchematic schematic = SchematicHelper.world();
        Minecraft mc = Minecraft.getInstance();
        layerTop = schematic == null || mc.level == null ? layer : topFor(mc.level, schematic, SchematicHelper.areas(), layer);
        applyLayer();
        resetProgress();
        Chat.actionBar(describeLayers());
    }

    private String describeLayers() {
        return layerTop == layer ? "Layer Y " + layer : "Layers Y " + layer + "-" + layerTop;
    }

    private void applyLayer() {
        LayerRange range = DataManager.getRenderLayerRange();
        if (range.getAxis() != Direction.Axis.Y) {
            range.setAxis(Direction.Axis.Y);
        }

        if (layerTop == layer) {
            if (range.getLayerMode() != LayerMode.SINGLE_LAYER) {
                range.setLayerMode(LayerMode.SINGLE_LAYER, false);
            }
            range.setLayerSingle(layer);
            return;
        }

        if (range.getLayerMode() != LayerMode.LAYER_RANGE) {
            range.setLayerMode(LayerMode.LAYER_RANGE, false);
        }
        // Each setter is limited by the other end, so move the end in the direction we're going first
        if (layer > range.getLayerRangeMax()) {
            range.setLayerRangeMax(layerTop);
            range.setLayerRangeMin(layer);
        } else {
            range.setLayerRangeMin(layer);
            range.setLayerRangeMax(layerTop);
        }
    }

    /** One layer at a time, always (farmland stays wet from the water put in before it's tilled). */
    private int topFor(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y) {
        return y;
    }

    private int scanLayers(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas,
                           @Nullable List<BlockPos> examples) {
        return scanLayers(level, schematic, areas, examples, null);
    }

    private int scanLayers(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas,
                           @Nullable List<BlockPos> examples, @Nullable Findings findings) {
        int remaining = 0;
        for (int y = layer; y <= layerTop; y++) {
            remaining += scan(level, schematic, areas, y, examples, findings);
        }
        return remaining;
    }

    /** Deferred blocks that still aren't right. */
    private static List<BlockPos> unfinishedDeferred(Level level, WorldSchematic schematic) {
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos pos : AutoBuild.INSTANCE.deferred) {
            BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
            if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                left.add(pos);
            }
        }
        return left;
    }

    /**
     * The shopping list: items the current layers still need that aren't in the inventory. Said in chat when it
     * changes; later used to buy from the shop.
     */
    private long lastNeedsMessage = -100000;

        private void updateShoppingList(Minecraft mc, java.util.Map<net.minecraft.world.item.Item, Integer> needs) {
        if (mc.player == null || mc.player.getAbilities().instabuild) {
            return;
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> missing = new java.util.LinkedHashMap<>();
        for (var entry : needs.entrySet()) {
            int have = mc.player.getInventory().countItem(entry.getKey());
            if (have < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - have);
            }
        }

        if (!missing.equals(AutoBuild.INSTANCE.shoppingList)) {
            // Counts change with every block placed: say it when another item is missing, or once a minute
            boolean newItems = !AutoBuild.INSTANCE.shoppingList.keySet().containsAll(missing.keySet());
            AutoBuild.INSTANCE.shoppingList.clear();
            AutoBuild.INSTANCE.shoppingList.putAll(missing);
            if (!missing.isEmpty() && (newItems || AutoBuild.clientTicks - lastNeedsMessage > 20 * 60)) {
                lastNeedsMessage = AutoBuild.clientTicks;
                StringBuilder sb = new StringBuilder(describeLayers() + " still needs: ");
                missing.forEach((item, count) -> sb.append(count).append(' ')
                        .append(new ItemStack(item).getHoverName().getString()).append(", "));
                Chat.warn(sb.substring(0, sb.length() - 2));
            }
        }
    }

    /** At the end: say if anything couldn't be finished (with where), so nothing is silently left out. */
    private void reportUnfinished(Minecraft mc, WorldSchematic schematic) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        List<String> examples = new ArrayList<>();

        for (BlockPos skipped : AutoBuild.INSTANCE.couldNotPlace) {
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, skipped);
            if (result != BlockCheck.Result.OK) {
                String name = schematic.getBlockState(skipped).getBlock().getName().getString();
                counts.merge(name, 1, Integer::sum);
                if (examples.size() < MAX_REPORTED * 2) {
                    examples.add(name + " at " + skipped.getX() + " " + skipped.getY() + " " + skipped.getZ());
                }
            }
        }

        if (counts.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("Couldn't finish: ");
        counts.forEach((name, count) -> sb.append(count).append(' ').append(name).append(", "));
        Chat.warn(sb.substring(0, sb.length() - 2));
        for (String example : examples) {
            Chat.warn(" - " + example);
        }
    }

    private void resetProgress() {
        completeChecks = 0;
        waitingChecks = 0;
        lastRemaining = -1;
        ticksWithoutProgress = 0;
        reportedStuck = false;
        ticksUntilCheck = 0;
        // Not known yet whether the new layer has water: nothing else goes in until the first check says
        waterPending = -1;
    }

    private void reportStuck(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas) {
        List<BlockPos> positions = new ArrayList<>();
        int remaining = scanLayers(mc.level, schematic, areas, positions);

        Chat.warn(describeLayers() + ": nothing placed for " + ticksWithoutProgress / 20 + " s. " + remaining
                + " left. Player at " + mc.player.blockPosition().toShortString() + ", doing: " + AutoBuild.INSTANCE.doing());
        PrinterAddon.LOGGER.warn("[stuck] {}: nothing placed for {} s, {} left, player at {}, doing: {}", describeLayers(),
                ticksWithoutProgress / 20, remaining, mc.player.blockPosition().toShortString(), AutoBuild.INSTANCE.doing());
        int shown = 0;
        for (BlockPos pos : positions) {
            String why = Diagnose.explain(mc, schematic, pos);
            PrinterAddon.LOGGER.warn("[stuck]   {}", why);
            BuildLog.line("STUCK", why);
            if (shown++ < 4) {
                Chat.warn(" - " + why);
            }
        }
    }

    private static String describe(Minecraft mc, BlockCheck.Result result, BlockState target) {
        String name = target.getBlock().getName().getString();

        return switch (result) {
            case MISSING -> {
                ItemStack needed = StateRules.itemFor(target);
                boolean have = mc.player != null && !needed.isEmpty()
                        && mc.player.getInventory().contains(s -> s.is(needed.getItem()));
                yield "needs " + name + (have ? " (in inventory, move closer?)" : " (§cnot in inventory§e)");
            }
            case WRONG_BLOCK, WRONG_STATE -> "wrong block, should be " + name + " (out of reach, or can't be broken?)";
            case PRINTER_FIXES -> "should be " + name + ", waiting for the printer to click it";
            case PROTECTED -> "wrong, but it's a container or unbreakable, fix it by hand";
            case EXTRA -> "should be air (use 'Clear extra blocks')";
            case UNLOADED -> "chunk not loaded, move closer";
            case BUCKET -> "needs a bucket of lava or water" + (Fluids.bucketFor(target) != null
                    && mc.player != null && mc.player.getInventory().contains(s -> s.is(Fluids.bucketFor(target)))
                    ? " (in inventory, move closer?)" : " (\u00a7cnot in inventory\u00a7e)");
            case OK -> "ok";
        };
    }

    /** What a scan found besides the count. */
    private static final class Findings {
        /** Missing blocks that can't be placed yet (they need something that isn't there). */
        final List<BlockPos> waiting = new ArrayList<>();
        /** Items the missing blocks need. */
        final java.util.Map<net.minecraft.world.item.Item, Integer> needs = new java.util.LinkedHashMap<>();
        /** Farmland is placed as dirt and tilled: that needs a hoe. */
        boolean needsHoe;
        /** Unfinished blocks that hold water (waterlogged or water): they go first. */
        int water;
        final java.util.Set<Long> waterCells = new java.util.HashSet<>();
    }

    /**
     * Counts unfinished positions in one layer. If {@code examples} is given, the first few are added to it;
     * if {@code findings} is given, blocks that have to wait and the items needed are collected in it.
     */
    private static int scan(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y,
                            @Nullable List<BlockPos> examples) {
        return scan(level, schematic, areas, y, examples, null);
    }

    private static int scan(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y,
                            @Nullable List<BlockPos> examples, @Nullable Findings findings) {
        boolean extraCounts = Configs.Layers.EXTRA_BLOCKS_BLOCK_LAYER.getBooleanValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int remaining = 0;

        for (SchematicHelper.Area area : areas) {
            if (y < area.minY() || y > area.maxY()) {
                continue;
            }

            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    pos.set(x, y, z);
                    BlockCheck.Result result = BlockCheck.check(level, schematic, pos);

                    boolean unfinished = switch (result) {
                        case OK -> false;
                        case EXTRA -> extraCounts;
                        case WRONG_BLOCK -> !AutoBuild.INSTANCE.isConflict(pos, result, level.getBlockState(pos))
                                && !AutoBuild.INSTANCE.couldNotPlace.contains(pos);
                        default -> !AutoBuild.INSTANCE.couldNotPlace.contains(pos);
                    };

                    if (unfinished) {
                        remaining++;
                        if (findings != null && PlacementOrder.holdsWater(schematic.getBlockState(pos))) {
                            findings.water++;
                            if (result != BlockCheck.Result.UNLOADED) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    for (int dz = -1; dz <= 1; dz++) {
                                        findings.waterCells.add(waterCell((x >> 3) + dx, (z >> 3) + dz));
                                    }
                                }
                            }
                        }
                        if (examples != null && examples.size() < MAX_REPORTED) {
                            examples.add(pos.immutable());
                        }
                        if (findings != null && result == BlockCheck.Result.BUCKET && Fluids.bucketFor(schematic.getBlockState(pos)) != null) {
                            // One bucket does them all (an infinite water bucket, or refilled from water nearby)
                            findings.needs.put(Fluids.bucketFor(schematic.getBlockState(pos)), 1);
                        }
                        if (findings != null && result == BlockCheck.Result.MISSING) {
                            BlockState target = schematic.getBlockState(pos);
                            // Blocks waiting for the layer's water aren't waiting for something above
                            if (!PlacementOrder.isReady(level, schematic, pos, target)
                                    && PlacementOrder.waterFirst(level, schematic, pos, target) == null
                                    && !AutoBuild.INSTANCE.layers.waterFirst(pos)) {
                                findings.waiting.add(pos.immutable());
                            }
                            ItemStack item = StateRules.itemFor(target);
                            if (!item.isEmpty()) {
                                findings.needs.merge(item.getItem(), 1, Integer::sum);
                            }
                            if (target.getBlock() == Blocks.FARMLAND) {
                                findings.needsHoe = true;
                            }
                        }
                    }
                }
            }
        }

        return remaining;
    }
}
