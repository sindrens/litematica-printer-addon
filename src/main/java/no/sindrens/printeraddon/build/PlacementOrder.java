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

    /** A block that holds a water source (water itself or a waterlogged block). */
    public static boolean holdsWater(BlockState target) {
        return target.getFluidState().isSource() && target.getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
    }

    /**
     * Farmland is tilled only once the water that keeps it wet is in (a waterlogged block or water within 4 blocks,
     * as the game checks): tilled without water it dries back to dirt. The dirt itself may go in before.
     * Returns the water still missing, or null.
     */
    @Nullable
    public static BlockPos waterFirst(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (target.getBlock() != net.minecraft.world.level.block.Blocks.FARMLAND || level.getBlockState(pos).isAir()) {
            return null;
        }
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            BlockState wanted = schematic.getBlockState(near);
            if (wanted.getFluidState().isSource() && wanted.getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER)
                    && !level.getFluidState(near).isSource()) {
                return near.immutable();
            }
        }
        return null;
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
        ready.sort(Comparator
                .comparingInt((BlockPos pos) -> holdsWater(schematic.getBlockState(pos)) ? -1 : tier(schematic, pos, schematic.getBlockState(pos)))
                .thenComparingInt(pos -> inHand != null && StateRules.itemFor(schematic.getBlockState(pos)).is(inHand) ? 0 : 1)
                .thenComparingDouble(pos -> from.distanceToSqr(Vec3.atCenterOf(pos))));
        return ready;
    }
}
