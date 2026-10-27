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
import no.sindrens.printeraddon.util.Chat;

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
    /** This many clicks in the air with none kept: the server doesn't allow it. */
    private static final int REFUSED_AFTER = 12;

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
    /** Clicks in the air since the build started, and how many of them the server kept. */
    private int airClicks;
    private int airKept;
    /** The server took none of the first clicks in the air: no more air place this build. */
    private boolean refused;

    public void reset() {
        anchor = null;
        verifyIn = 0;
        idleTicks = 0;
        before.clear();
        skipped.clear();
        misplaced.clear();
        airClicks = 0;
        airKept = 0;
        refused = false;
    }

    /** Air place may be used: switched on, and the server hasn't thrown out every block placed in the air so far. */
    public boolean allowed() {
        return Configs.Layers.USE_AIR_PLACE.getBooleanValue() && !refused;
    }

    /**
     * What came of a printer click on a floating spot while air place was on: the block stayed, or nothing was
     * there after the server's answer. None of the first ones stayed: the server (its anticheat) cancels blocks placed
     * in the air, so it's stopped for this build instead of clicking on for nothing.
     */
    public void judged(BlockPos pos, boolean kept) {
        airClicks++;
        if (kept) {
            airKept++;
        }
        if (!refused && airKept == 0 && airClicks >= REFUSED_AFTER) {
            refused = true;
            stop();
            BuildLog.line("AIRPLACE", "the server kept none of " + airClicks + " blocks placed in the air (the last at "
                    + pos.toShortString() + "): air place is off for this build");
            Chat.warn("The server doesn't keep blocks placed in the air (" + airClicks + " tries, none stayed). Air place is off"
                    + " for this build: floating blocks (nothing beside or under them) are left for later.");
        }
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

        // The whole layer is air placed (see LayerManager#airLayer): nothing to do one block at a time
        if (!allowed() || AutoBuild.INSTANCE.layers.airLayer() || breakerBusy || verifyIn > 0 || !PrinterBridge.isIdle()) {
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
        // A floor that floats as a whole (a farm's concrete floor one block over the ground with air under it): each
        // block has the others beside it in the schematic, so none floats "for good", yet none of them can be placed
        // against anything: 9 minutes of "the printer can't place them from where it can stand" and a layer with 0
        // placed. One of them is air placed (the lowest, nearest), and the rest are placed against it as usual
        BlockPos seed = null;
        double seedScore = Double.MAX_VALUE;

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
            if (!needsAirPlace(mc.level, schematic, pos, target)) {
                if (!(target.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) && airSafe(target)
                        && !hasSupport(mc.level, pos)) {
                    double score = (pos.getY() - eye.y) * 100 + distance;
                    if (score < seedScore) {
                        seedScore = score;
                        seed = pos.immutable();
                    }
                }
                continue;
            }

            // Prefer blocks without a direction: they can't end up facing the wrong way
            double score = distance + (target.getProperties().isEmpty() ? 0 : 100);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }

        if (best == null && seed != null) {
            BuildLog.line("AIR-SEED", seed.toShortString() + ": nothing in reach can be placed against anything (it floats as a whole):"
                    + " this one is air placed, the rest go against it");
            return seed;
        }
        return best;
    }

    /**
     * Blocks air place can do right: blocks that float (nothing next to them to place them against), and pillar
     * blocks like chains, whose direction air place sets from the schematic. Never hoppers: their direction comes
     * from the face that's clicked, and air place would make them point down.
     */
    public static boolean isCandidate(Level level, BlockPos pos, BlockState target) {
        return !hasSupport(level, pos);
    }

    /**
     * Nothing beside or under it to place it against, now or later in the schematic (a block above doesn't count: in a
     * cactus farm that's the sand that needs this string under it first). Only air place can put it in.
     */
    public static boolean floatsForGood(Level level, WorldSchematic schematic, BlockPos pos) {
        if (hasSupport(level, pos)) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            if (direction == Direction.UP) {
                continue;
            }
            BlockState planned = schematic.getBlockState(pos.relative(direction));
            if (!planned.isAir() && !planned.canBeReplaced() && planned.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Only air place can put it in: it floats for good (see floatsForGood), and it isn't sand or the like over nothing
     * (that would fall; the printer waits for the block under it). Sand waiting for the string under it in a cactus
     * farm was air placed one at a time: 2 s of nothing each time, the printer can't place it.
     */
    public static boolean needsAirPlace(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        return !(target.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) && floatsForGood(level, schematic, pos);
    }

    /**
     * Comes out right placed in the air whatever face the printer says it clicked: nothing that faces a way, lies along
     * an axis, sits in a half of the block or turns (string's connections, a cactus's or sugar cane's age don't count).
     */
    public static boolean airSafe(BlockState target) {
        net.minecraft.world.level.block.Block block = target.getBlock();
        return target.getProperties().isEmpty() || block instanceof net.minecraft.world.level.block.TripWireBlock
                || block instanceof net.minecraft.world.level.block.CactusBlock
                || block instanceof net.minecraft.world.level.block.SugarCaneBlock;
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
