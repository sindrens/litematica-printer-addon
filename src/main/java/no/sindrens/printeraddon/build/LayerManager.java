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
    private static final int MAX_REPORTED = 5;

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
    private boolean reportedStuck;
    /** Layer and blocks left, for the status line. */
    private String status = "";
    private int waitingChecks;
    /** All layers are done; only the deferred blocks are left. */
    private boolean finishingDeferred;

    /** Litematica's layer settings before auto build, put back afterwards. */
    @Nullable
    private LayerMode previousMode;
    private Direction.Axis previousAxis;
    private int previousSingle;

    public boolean isActive() {
        return active;
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

    public void stop() {
        finishingDeferred = false;
        if (active) {
            restoreLayerRange();
        }
        active = false;
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
        int interval = Configs.Layers.LAYER_CHECK_INTERVAL.getIntegerValue();
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
        if (findings.needsHoe && mc.player != null && !mc.player.getInventory().contains(net.minecraft.tags.ItemTags.HOES)) {
            findings.needs.put(net.minecraft.world.item.Items.IRON_HOE, 1);
        }
        updateShoppingList(mc, findings.needs);

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
            layer = layerTop;
            do {
                layer++;
            } while (layer <= maxY && scan(mc.level, schematic, areas, layer, null) == 0);

            if (layer > maxY) {
                if (!unfinishedDeferred(mc.level, schematic).isEmpty()) {
                    // All layers done; place what waited, with the whole schematic visible
                    finishingDeferred = true;
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL, false);
                    resetProgress();
                    return true;
                }
                Chat.info("\u00a7aBuild finished!");
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

        if (remaining != lastRemaining) {
            lastRemaining = remaining;
            ticksWithoutProgress = 0;
            reportedStuck = false;
        } else {
            ticksWithoutProgress += interval;
        }

        status = describeLayers() + ": " + remaining + " left";

        if (!reportedStuck && ticksWithoutProgress >= Configs.Layers.STUCK_REPORT_SECONDS.getIntegerValue() * 20) {
            reportedStuck = true;
            reportStuck(mc, schematic, areas);
        }

        if (ticksWithoutProgress >= Configs.Layers.SKIP_STUCK_SECONDS.getIntegerValue() * 20 && skipUnplaceable(mc, schematic, areas)) {
            completeChecks = 2;
            ticksUntilCheck = 0;
        }

        return true;
    }

    /**
     * Gives up on the blocks left in the current layers if every one of them has its item in the inventory (so the
     * printer just can't place it). Returns true if the layer can be left; false if something is waiting for items.
     */
    private boolean skipUnplaceable(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas) {
        List<BlockPos> left = new ArrayList<>();
        scanLayers(mc.level, schematic, areas, null, null);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : areas) {
            for (int y = Math.max(layer, area.minY()); y <= Math.min(layerTop, area.maxY()); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.OK || result == BlockCheck.Result.EXTRA) {
                            continue;
                        }
                        BlockState bucketTarget = schematic.getBlockState(pos);
                        if (result == BlockCheck.Result.BUCKET && Fluids.bucketFor(bucketTarget) != null
                                && !mc.player.getInventory().contains(s -> s.is(Fluids.bucketFor(bucketTarget)))) {
                            // Waiting for a bucket (to buy), not stuck
                            return false;
                        }
                        if (AutoBuild.INSTANCE.rails.isTemp(pos)) {
                            continue;
                        }
                        BlockState target = schematic.getBlockState(pos);
                        ItemStack needed = MaterialCache.getInstance().getRequiredBuildItemForState(target);
                        if (result == BlockCheck.Result.MISSING && !needed.isEmpty()
                                && !mc.player.getInventory().contains(s -> s.is(needed.getItem()))) {
                            return false;
                        }
                        left.add(pos.immutable());
                    }
                }
            }
        }

        AutoBuild.INSTANCE.couldNotPlace.addAll(left);
        Chat.warn("Skipping " + left.size() + " block" + (left.size() == 1 ? "" : "s") + " in " + describeLayers()
                + " the printer can't place; they're listed at the end.");
        return true;
    }

    private boolean finishDeferred(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas, int interval) {
        List<BlockPos> left = unfinishedDeferred(mc.level, schematic);
        if (left.isEmpty()) {
            Chat.info("\u00a7aBuild finished!");
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

    /** The farmland layer is built together with the layer above it (seeds, stems), if enabled. */
    private int topFor(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y) {
        if (!Configs.Layers.PLANT_WITH_TILLING.getBooleanValue() || y >= maxY) {
            return y;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : areas) {
            if (y < area.minY() || y > area.maxY()) {
                continue;
            }
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (schematic.getBlockState(pos).getBlock() == Blocks.FARMLAND) {
                        return y + 1;
                    }
                }
            }
        }
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
            AutoBuild.INSTANCE.shoppingList.clear();
            AutoBuild.INSTANCE.shoppingList.putAll(missing);
            if (!missing.isEmpty()) {
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
    }

    private void reportStuck(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas) {
        List<BlockPos> positions = new ArrayList<>();
        int remaining = scanLayers(mc.level, schematic, areas, positions);

        Chat.warn(describeLayers() + " hasn't changed for a while. " + remaining + " left, for example:");

        for (BlockPos pos : positions) {
            BlockState target = schematic.getBlockState(pos);
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            Chat.warn(" - " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": " + describe(mc, result, target));
        }
    }


    private static String describe(Minecraft mc, BlockCheck.Result result, BlockState target) {
        String name = target.getBlock().getName().getString();

        return switch (result) {
            case MISSING -> {
                ItemStack needed = MaterialCache.getInstance().getRequiredBuildItemForState(target);
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
                        default -> !AutoBuild.INSTANCE.couldNotPlace.contains(pos);
                    };

                    if (unfinished) {
                        remaining++;
                        if (examples != null && examples.size() < MAX_REPORTED) {
                            examples.add(pos.immutable());
                        }
                        if (findings != null && result == BlockCheck.Result.BUCKET && Fluids.bucketFor(schematic.getBlockState(pos)) != null) {
                            findings.needs.merge(Fluids.bucketFor(schematic.getBlockState(pos)), 1, Integer::sum);
                        }
                        if (findings != null && result == BlockCheck.Result.MISSING) {
                            BlockState target = schematic.getBlockState(pos);
                            if (!PlacementOrder.isReady(level, schematic, pos, target)) {
                                findings.waiting.add(pos.immutable());
                            }
                            ItemStack item = MaterialCache.getInstance().getRequiredBuildItemForState(target);
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
