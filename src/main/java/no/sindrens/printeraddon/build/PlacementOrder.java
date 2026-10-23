package no.sindrens.printeraddon.build;

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
        if (needsSupport(target) && !target.canSurvive(level, pos)) {
            return false;
        }

        // Rails wait until they'll get the right shape (see RailHelper)
        if (!AutoBuild.INSTANCE.rails.isReady(level, schematic, pos, target)) {
            return false;
        }
        // Nothing goes where a temporary rail stands
        if (AutoBuild.INSTANCE.rails.isTemp(pos)) {
            return false;
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
                return false;
            }
            // A slope placed before the rail at its top lies flat and may join the rails beside it instead:
            // wait for that rail (often in the layer above; the slope is placed later then)
            if (up != null) {
                BlockPos top = pos.relative(up).above();
                if (schematic.getBlockState(top).getBlock() instanceof BaseRailBlock
                        && !(level.getBlockState(top).getBlock() instanceof BaseRailBlock)) {
                    return false;
                }
            }
        }

        if (target.getBlock() instanceof ObserverBlock) {
            BlockPos watched = pos.relative(target.getValue(ObserverBlock.FACING));
            if (!isDone(level, schematic, watched)) {
                return false;
            }
        }

        return true;
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

        ready.sort(Comparator
                .comparingInt((BlockPos pos) -> tier(schematic, pos, schematic.getBlockState(pos)))
                .thenComparingDouble(pos -> from.distanceToSqr(Vec3.atCenterOf(pos))));
        return ready;
    }
}
