package no.sindrens.printeraddon.build;

import javax.annotation.Nullable;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides in which order the printer may place blocks, the way a person would build:
 * full blocks first, then other blocks, and last the blocks that sit on or hang off something
 * (rails, redstone, crops, torches...), each only once what it needs is really there.
 */
public final class PlacementOrder {
    private PlacementOrder() {
    }

    /** Lower goes first. */
    /** A block leaving the reach within this many ticks is placed first (see order). */
    private static final double URGENT_TICKS = 10;
    /** A block leaving the reach within this many ticks goes first whatever is in hand. */
    private static final double LAST_CHANCE_TICKS = 4;

    public static int tier(WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (needsSupport(target)) {
            return 2;
        }
        if (target.isCollisionShapeFullBlock(schematic, pos)) {
            return 0;
        }
        return 1;
    }

    /** Blocks that break or fall without something under or behind them. */
    private static boolean needsSupport(BlockState target) {
        if (target.getBlock() instanceof FallingBlock || target.getBlock() instanceof BaseRailBlock) {
            return true;
        }
        // Redstone, repeaters, carpets, crops, flowers, torches... are not solid themselves
        return !target.isSolid() && target.getFluidState().isEmpty();
    }

    /**
     * True when everything this block needs is in place: it can survive where it goes, an ascending rail has
     * the block it climbs onto, and an observer has the block it watches (so it doesn't fire while building).
     */
    public static boolean isReady(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        return whyNotReady(level, schematic, pos, target) == null;
    }

    /** Why the block can't be placed yet, in words, or null when it can. */
    @javax.annotation.Nullable
    public static String whyNotReady(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (needsSupport(target) && !target.canSurvive(level, pos)) {
            return "needs the block it stands on or hangs from first";
        }
        // Only tilling waits for water (its own, within 4 blocks): everything else goes in meanwhile. A rule that held
        // up every block within 8 to 16 blocks of water still to go in once had a whole layer of 9725 blocks wait
        BlockPos water = waterFirst(level, schematic, pos, target);
        if (water != null) {
            return "tilling waits for the water at " + water.toShortString();
        }
        // Over a water spot of the layers being built (two layers together): only once its water is in, so the bucket
        // still sees the spot from above
        BlockPos under = waterBelow(level, schematic, pos);
        if (under != null) {
            return "waits for the water under it at " + under.toShortString();
        }

        // Rails wait until they'll get the right shape (see RailHelper; only when the add-on places rails itself)
        RailHelper.Plan plan = no.sindrens.printeraddon.config.Configs.Layers.ADDON_PLACES.getBooleanValue()
                ? AutoBuild.INSTANCE.rails.plan(level, schematic, pos, target) : null;
        if (plan != null && plan.waitForRails()) {
            return "rail curve waits for the rails it joins";
        }
        if (plan != null && !plan.temps().isEmpty()) {
            return "rail waits for temporary rails at " + plan.temps().stream().map(BlockPos::toShortString).toList();
        }
        // Nothing goes where a temporary rail stands
        if (AutoBuild.INSTANCE.rails.isTemp(pos)) {
            return "a temporary rail stands here";
        }

        if (target.getBlock() instanceof BaseRailBlock rail) {
            RailShape shape = target.getValue(rail.getShapeProperty());
            Direction up = switch (shape) {
                case ASCENDING_NORTH -> Direction.NORTH;
                case ASCENDING_SOUTH -> Direction.SOUTH;
                case ASCENDING_EAST -> Direction.EAST;
                case ASCENDING_WEST -> Direction.WEST;
                default -> null;
            };
            if (up != null && !isDone(level, schematic, pos.relative(up))) {
                return "slope waits for the block it climbs onto at " + pos.relative(up).toShortString();
            }
            // A slope placed before the rail at its top lies flat and may join the rails beside it instead:
            // wait for that rail (often in the layer above; the slope is placed later then)
            if (up != null) {
                BlockPos top = pos.relative(up).above();
                if (schematic.getBlockState(top).getBlock() instanceof BaseRailBlock
                        && !(level.getBlockState(top).getBlock() instanceof BaseRailBlock)) {
                    return "slope waits for the rail at its top, " + top.toShortString();
                }
            }
        }

        if (target.getBlock() instanceof ObserverBlock) {
            BlockPos watched = pos.relative(target.getValue(ObserverBlock.FACING));
            if (!isDone(level, schematic, watched)) {
                return "observer waits for the block it watches, " + watched.toShortString();
            }
        }

        return null;
    }

    /**
     * A block that stands on the block under it and goes in right after it: seeds and crops, redstone dust, rails,
     * carpets, a cactus on its sand, and sand or gravel (the printer only places those once there's something under
     * them, else they'd fall).
     */
    public static boolean standsOnBelow(BlockState target) {
        return needsSupport(target) || target.getBlock() instanceof net.minecraft.world.level.block.CactusBlock;
    }

    /**
     * The water spot of the layers being built, under this block or next to that, that's still to be filled; or null.
     * A block over it first would keep the bucket from seeing the spot (the bucket goes where the view hits).
     */
    @Nullable
    public static BlockPos waterBelow(Level level, WorldSchematic schematic, BlockPos pos) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        if (!layers.isActive() || pos.getY() - 1 < layers.getLayer() || pos.getY() - 1 > layers.getLayerTop()) {
            return null;
        }
        BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                near.set(pos.getX() + dx, pos.getY() - 1, pos.getZ() + dz);
                if (holdsWater(schematic.getBlockState(near)) && !level.getFluidState(near).isSource()) {
                    return near.immutable();
                }
            }
        }
        return null;
    }

    /** A block that holds a water source (water itself or a waterlogged block). */
    public static boolean holdsWater(BlockState target) {
        return target.getFluidState().isSource() && target.getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
    }

    /**
     * Farmland is tilled only once water that keeps it wet is in (a waterlogged block or water within 4 blocks, as the
     * game checks): tilled without water it dries back to dirt. One is enough (all of them in reach held up the rows
     * whose second water was in the next lane). The dirt itself may go in before. Not when the stem or crop on it is
     * built in the same go (the layers built together): planted right after, it keeps the farmland as it is. Waiting
     * there held up whole rows beside waterlogged leaves still to get their water (the bucket only comes while the
     * printer has nothing else to do): the lane flew on, came back for them, and some stayed dirt for good.
     * Returns the water to wait for, or null.
     */
    @Nullable
    public static BlockPos waterFirst(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (target.getBlock() != net.minecraft.world.level.block.Blocks.FARMLAND || level.getBlockState(pos).isAir()) {
            return null;
        }
        if (cropGoesOnNow(schematic, pos)) {
            return null;
        }
        BlockPos missing = null;
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(near).is(net.minecraft.tags.FluidTags.WATER)) {
                return null;
            }
            if (missing == null) {
                BlockState wanted = schematic.getBlockState(near);
                if (wanted.getFluidState().isSource() && wanted.getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
                    missing = near.immutable();
                }
            }
        }
        return missing;
    }

    /**
     * A stem or crop goes on this farmland in the layers being built now (or it's all visible: the last blocks), so it's
     * planted right after the tilling.
     */
    public static boolean cropGoesOnNow(WorldSchematic schematic, BlockPos farmland) {
        if (!schematic.getBlockState(farmland.above()).is(net.minecraft.tags.BlockTags.MAINTAINS_FARMLAND)) {
            return false;
        }
        LayerManager layers = AutoBuild.INSTANCE.layers;
        if (!layers.isActive() || layers.isFinishingDeferred()) {
            return true;
        }
        return farmland.getY() + 1 >= layers.getLayer() && farmland.getY() + 1 <= layers.getLayerTop();
    }

    /** A position is done when the schematic has nothing there or the world already matches it. */
    private static boolean isDone(Level level, WorldSchematic schematic, BlockPos pos) {
        if (schematic.getBlockState(pos).isAir()) {
            return true;
        }
        BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
        return result == BlockCheck.Result.OK || result == BlockCheck.Result.BUCKET;
    }

    /**
     * The printer's reachable positions, without the blocks that aren't ready yet, full blocks first.
     * The printer places the first position in the list that it can, so the order is its priority.
     */
    public static List<BlockPos> order(Level level, WorldSchematic schematic, List<BlockPos> positions, Vec3 from) {
        return order(level, schematic, positions, from, null);
    }

    /** Same, and among equally urgent blocks the ones placed with the item in hand come first (fewer item switches). */
    public static List<BlockPos> order(Level level, WorldSchematic schematic, List<BlockPos> positions, Vec3 from,
                                       @Nullable net.minecraft.world.item.Item inHand) {
        return order(level, schematic, positions, from, inHand, null);
    }

    /**
     * Same; while the player flies on placing (a lane, a glide), {@code ticksLeft} says how soon each block leaves the
     * reach: the ones about to go come first, soonest first, whatever is in hand. Else a block of another item than
     * the one in hand waited until it was out of reach (a piston among stone, left for a trip back at the end).
     */
    public static List<BlockPos> order(Level level, WorldSchematic schematic, List<BlockPos> positions, Vec3 from,
                                       @Nullable net.minecraft.world.item.Item inHand,
                                       @Nullable java.util.function.ToDoubleFunction<BlockPos> ticksLeft) {
        List<BlockPos> ready = new ArrayList<>(positions.size());

        for (BlockPos pos : positions) {
            BlockState target = schematic.getBlockState(pos);
            if (target.isAir()) {
                continue;
            }
            if (BlockCheck.check(level, schematic, pos) == BlockCheck.Result.OK) {
                continue;
            }
            if (!isReady(level, schematic, pos, target)) {
                continue;
            }
            ready.add(pos);
        }

        // Blocks that hold water first (waterlogged leaves): the bucket can follow at once, and the tilling around it
        // is done in the same visit instead of on a second round
        Comparator<BlockPos> usual = Comparator
                .comparingInt((BlockPos pos) -> holdsWater(schematic.getBlockState(pos)) ? -1 : tier(schematic, pos, schematic.getBlockState(pos)))
                .thenComparingInt(pos -> inHand != null && StateRules.itemFor(schematic.getBlockState(pos)).is(inHand) ? 0 : 1)
                .thenComparingDouble(pos -> from.distanceToSqr(Vec3.atCenterOf(pos)));
        if (ticksLeft == null) {
            ready.sort(usual);
            return ready;
        }
        java.util.Map<BlockPos, Double> left = new java.util.HashMap<>();
        for (BlockPos pos : ready) {
            left.put(pos, ticksLeft.applyAsDouble(pos));
        }
        // Of those about to go, the item in hand first unless one is all but gone: string and sand of a cactus farm
        // went string, sand, string, sand (3491 item switches for 9220 blocks)
        ready.sort(Comparator.<BlockPos>comparingInt(pos -> left.get(pos) < URGENT_TICKS ? 0 : 1)
                .thenComparingInt(pos -> left.get(pos) < LAST_CHANCE_TICKS ? 0 : 1)
                .thenComparingInt(pos -> left.get(pos) < URGENT_TICKS && inHand != null
                        && !StateRules.itemFor(schematic.getBlockState(pos)).is(inHand) ? 1 : 0)
                .thenComparingDouble(pos -> left.get(pos) < URGENT_TICKS ? left.get(pos) : 0)
                .thenComparing(usual));
        return ready;
    }
}
