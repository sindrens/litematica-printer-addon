package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses the printer's air place for as little as possible: only when every missing block within reach floats
 * (nothing next to it to place it against), only for one block, and switched off again as soon as the printer has
 * queued it. Air place can put blocks in the wrong spot, so the spots around it are checked afterwards and
 * anything that appeared where the schematic has air is broken again.
 */
public class AirPlace {
    /** The printer must have been idle this long (nothing it can place normally) before air place is used. */
    private static final int IDLE_TICKS_BEFORE = 10;
    /** Give up on the block if the printer hasn't placed it after this long. */
    private static final int ANCHOR_TIMEOUT = 40;
    /** Check for misplaced blocks this long after placing. */
    private static final int VERIFY_AFTER = 10;
    private static final int SKIP_TICKS = 20 * 30;

    @Nullable
    private BlockPos anchor;
    private int anchorTicks;
    private int idleTicks;
    private int verifyIn;
    private long tick;
    private final Map<BlockPos, BlockState> before = new HashMap<>();
    private final Map<BlockPos, Long> skipped = new HashMap<>();
    /** Blocks air place put where the schematic has air: to be broken. */
    private final Set<BlockPos> misplaced = new HashSet<>();

    public void reset() {
        anchor = null;
        verifyIn = 0;
        idleTicks = 0;
        before.clear();
        skipped.clear();
        misplaced.clear();
    }

    public void stop() {
        if (anchor != null) {
            PrinterBridge.setAirPlace(false);
        }
        anchor = null;
    }

    public boolean isActive() {
        return anchor != null || verifyIn > 0;
    }

    public boolean hasAnchor() {
        return anchor != null;
    }

    public Set<BlockPos> misplaced() {
        return misplaced;
    }

    /** While air place is on, the printer may only look at this one position. */
    public List<BlockPos> filterPrinterPositions(List<BlockPos> positions) {
        if (anchor == null) {
            return positions;
        }
        return positions.contains(anchor) ? List.of(anchor) : List.of();
    }

    /** Called by the printer mixin when the printer queued a placement. */
    public void onPrinterQueued() {
        if (anchor != null) {
            PrinterAddon.debug("Air placed {}, air place off again", anchor.toShortString());
            PrinterBridge.setAirPlace(false);
            anchor = null;
            verifyIn = VERIFY_AFTER;
        }
    }

    public void tick(Minecraft mc, boolean breakerBusy) {
        tick++;
        skipped.values().removeIf(until -> until < tick);
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || schematic == null) {
            return;
        }

        if (verifyIn > 0 && --verifyIn == 0) {
            verify(mc.level, schematic);
        }

        if (anchor != null) {
            if (++anchorTicks > ANCHOR_TIMEOUT) {
                PrinterAddon.debug("Printer didn't air place {}, giving up on it for now", anchor.toShortString());
                skipped.put(anchor, tick + SKIP_TICKS);
                PrinterBridge.setAirPlace(false);
                anchor = null;
            }
            return;
        }

        if (!Configs.Layers.USE_AIR_PLACE.getBooleanValue() || breakerBusy || verifyIn > 0 || !PrinterBridge.isIdle()) {
            idleTicks = 0;
            return;
        }
        if (++idleTicks < IDLE_TICKS_BEFORE) {
            return;
        }
        idleTicks = 0;

        BlockPos chosen = findFloating(mc, player, schematic);
        if (chosen == null) {
            return;
        }

        before.clear();
        for (BlockPos pos : BlockPos.betweenClosed(chosen.offset(-1, -1, -1), chosen.offset(1, 1, 1))) {
            before.put(pos.immutable(), mc.level.getBlockState(pos));
        }
        anchor = chosen;
        anchorTicks = 0;
        PrinterBridge.setAirPlace(true);
        PrinterAddon.debug("Nothing within reach can be placed normally; air placing {}", chosen.toShortString());
    }

    /**
     * The nearest missing block within reach the printer isn't placing. Simple full blocks are preferred over blocks
     * that face a direction.
     */
    @Nullable
    private BlockPos findFloating(Minecraft mc, LocalPlayer player, WorldSchematic schematic) {
        double range = Math.min(me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue(),
                player.blockInteractionRange());
        int r = (int) Math.ceil(range);
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        List<SchematicHelper.Area> areas = SchematicHelper.areas();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance > range * range || distance <= 1 || skipped.containsKey(pos)) {
                continue;
            }
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos) || !SchematicHelper.isInAnyArea(areas, pos)) {
                continue;
            }
            if (BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.MISSING) {
                continue;
            }
            BlockState target = schematic.getBlockState(pos);
            if (!player.getAbilities().instabuild && !hasItemFor(player, target)) {
                continue;
            }
            if (!PlacementOrder.isReady(mc.level, schematic, pos, target)) {
                continue;
            }
            if (!isCandidate(mc.level, pos, target)) {
                continue;
            }

            // Prefer blocks without a direction: they can't end up facing the wrong way
            double score = distance + (target.getProperties().isEmpty() ? 0 : 100);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }

        return best;
    }

    /**
     * Blocks air place can do right: blocks that float (nothing next to them to place them against), and pillar
     * blocks like chains, whose direction air place sets from the schematic. Never hoppers: their direction comes
     * from the face that's clicked, and air place would make them point down.
     */
    public static boolean isCandidate(Level level, BlockPos pos, BlockState target) {
        // Hoppers, logs, chains: FacePlace clicks the right face for those, air place could turn them wrong
        if (FacePlace.faceFor(target) != null) {
            return false;
        }
        return !hasSupport(level, pos);
    }

    private static boolean hasSupport(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (!neighbor.isAir() && !neighbor.canBeReplaced() && neighbor.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Anything that appeared next to the air placed block where the schematic has air was misplaced. */
    private void verify(Level level, WorldSchematic schematic) {
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState now = level.getBlockState(pos);
            if (entry.getValue().isAir() && !now.isAir() && BlockCheck.check(level, schematic, pos) == BlockCheck.Result.EXTRA) {
                PrinterAddon.debug("Air place put {} at {} where the schematic has air; breaking it", now, pos.toShortString());
                misplaced.add(pos);
            }
        }
        before.clear();
    }

    private static boolean hasItemFor(LocalPlayer player, BlockState state) {
        ItemStack needed = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        return !needed.isEmpty() && player.getInventory().contains(stack -> stack.is(needed.getItem()));
    }
}
