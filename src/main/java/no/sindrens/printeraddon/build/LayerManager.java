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
    private int minY;
    private int maxY;
    private int ticksUntilCheck;
    private int completeChecks;
    private int lastRemaining = -1;
    private int ticksWithoutProgress;
    private boolean reportedStuck;

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
        int remaining = scan(mc.level, schematic, areas, layer, null);

        if (remaining == 0) {
            // Check twice in a row so a block the server hasn't confirmed yet doesn't count
            if (++completeChecks < 2) {
                return true;
            }

            Chat.info("Layer Y " + layer + " done.");
            do {
                layer++;
            } while (layer <= maxY && scan(mc.level, schematic, areas, layer, null) == 0);

            if (layer > maxY) {
                Chat.info("§aBuild finished!");
                stop();
                return false;
            }

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

        Chat.actionBar("Layer Y " + layer + ": " + remaining + " block" + (remaining == 1 ? "" : "s") + " left");

        if (!reportedStuck && ticksWithoutProgress >= Configs.Layers.STUCK_REPORT_SECONDS.getIntegerValue() * 20) {
            reportedStuck = true;
            reportStuck(mc, schematic, areas);
        }

        return true;
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
        applyLayer();
        resetProgress();
        Chat.actionBar("Layer Y " + layer);
    }

    private void applyLayer() {
        LayerRange range = DataManager.getRenderLayerRange();
        if (range.getLayerMode() != LayerMode.SINGLE_LAYER) {
            range.setLayerMode(LayerMode.SINGLE_LAYER, false);
        }
        if (range.getAxis() != Direction.Axis.Y) {
            range.setAxis(Direction.Axis.Y);
        }
        range.setLayerSingle(layer);
    }

    private void resetProgress() {
        completeChecks = 0;
        lastRemaining = -1;
        ticksWithoutProgress = 0;
        reportedStuck = false;
        ticksUntilCheck = 0;
    }

    private void reportStuck(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas) {
        List<BlockPos> positions = new ArrayList<>();
        int remaining = scan(mc.level, schematic, areas, layer, positions);

        Chat.warn("Layer Y " + layer + " hasn't changed for a while. " + remaining + " left, for example:");

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
            case OK -> "ok";
        };
    }

    /**
     * Counts unfinished positions in one layer. If {@code examples} is given, the first few are added to it.
     */
    private static int scan(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y,
                            @Nullable List<BlockPos> examples) {
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
                        default -> true;
                    };

                    if (unfinished) {
                        remaining++;
                        if (examples != null && examples.size() < MAX_REPORTED) {
                            examples.add(pos.immutable());
                        }
                    }
                }
            }
        }

        return remaining;
    }
}
