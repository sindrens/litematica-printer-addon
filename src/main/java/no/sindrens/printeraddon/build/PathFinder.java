package no.sindrens.printeraddon.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Small A* pathfinder for walking: flat moves (also diagonal), stepping up one block, and dropping down at most
 * three blocks (no fall damage). It never digs or places blocks.
 */
public final class PathFinder {
    private static final int MAX_DROP = 3;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final Set<Block> DANGEROUS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE,
            Blocks.POINTED_DRIPSTONE, Blocks.COBWEB);

    private PathFinder() {
    }

    public interface Goal {
        boolean reached(BlockPos feet);

        /** Estimated remaining cost; must not overestimate. */
        double estimate(BlockPos feet);
    }

    private record Node(BlockPos pos, double cost, double total) {
    }

    /**
     * Finds a walking route from {@code start} (feet position) to a spot where {@code goal} is reached.
     *
     * @param blocked spots the player must not stand in (blocks still to be placed there)
     * @return the feet positions to walk through, not including the start, or null if there is no route
     */
    @Nullable
    public static List<BlockPos> find(Level level, BlockPos start, Goal goal, Predicate<BlockPos> blocked,
                                      int maxDistance, int maxNodes) {
        if (goal.reached(start)) {
            return List.of();
        }

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.total(), b.total()));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();

        open.add(new Node(start, 0, goal.estimate(start)));
        bestCost.put(start, 0.0);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node node = open.poll();
            if (node.cost() > bestCost.getOrDefault(node.pos(), Double.MAX_VALUE)) {
                continue;
            }
            if (goal.reached(node.pos())) {
                return rebuild(cameFrom, node.pos(), start);
            }
            expanded++;

            for (int[] dir : DIRECTIONS) {
                Step step = step(level, node.pos(), dir[0], dir[1], blocked);
                if (step == null) {
                    continue;
                }
                BlockPos next = step.pos();
                if (Math.abs(next.getX() - start.getX()) > maxDistance || Math.abs(next.getZ() - start.getZ()) > maxDistance) {
                    continue;
                }

                double cost = node.cost() + step.cost();
                if (cost < bestCost.getOrDefault(next, Double.MAX_VALUE)) {
                    bestCost.put(next, cost);
                    cameFrom.put(next, node.pos());
                    open.add(new Node(next, cost, cost + goal.estimate(next)));
                }
            }
        }

        return null;
    }

    private record Step(BlockPos pos, double cost) {
    }

    @Nullable
    private static Step step(Level level, BlockPos from, int dx, int dz, Predicate<BlockPos> blocked) {
        boolean diagonal = dx != 0 && dz != 0;
        BlockPos to = from.offset(dx, 0, dz);

        if (diagonal) {
            // Don't cut corners
            if (!isOpenColumn(level, from.offset(dx, 0, 0)) || !isOpenColumn(level, from.offset(0, 0, dz))) {
                return null;
            }
            return canStand(level, to, blocked) ? new Step(to, 1.414) : null;
        }

        if (canStand(level, to, blocked)) {
            return new Step(to, 1);
        }

        // Step up one block (needs head room for the jump)
        BlockPos up = to.above();
        if (canStand(level, up, blocked) && isPassable(level, from.above(2))) {
            return new Step(up, 2);
        }

        // Drop down
        if (!isOpenColumn(level, to)) {
            return null;
        }
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = to.below(drop);
            if (canStand(level, down, blocked)) {
                return new Step(down, 1 + drop * 0.5);
            }
            if (!isPassable(level, down)) {
                return null;
            }
        }
        return null;
    }

    /** True if the player can stand with their feet at {@code feet}. */
    public static boolean canStand(Level level, BlockPos feet, Predicate<BlockPos> blocked) {
        return isOpenColumn(level, feet) && isSupport(level, feet.below())
                && !blocked.test(feet) && !blocked.test(feet.above());
    }

    private static boolean isOpenColumn(Level level, BlockPos feet) {
        return isPassable(level, feet) && isPassable(level, feet.above());
    }

    public static boolean isPassable(Level level, BlockPos pos) {
        if (!level.isLoaded(pos) || !insideBorder(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !DANGEROUS.contains(state.getBlock());
    }

    /**
     * The whole block is inside the world border. On the player's skyblock server that's the island's edge: the player
     * can't go past it, and a lane ending one block beyond it had the player fly up along it for good, taking it for a
     * wall.
     */
    public static boolean insideBorder(Level level, BlockPos pos) {
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        return pos.getX() >= border.getMinX() && pos.getX() + 1 <= border.getMaxX()
                && pos.getZ() >= border.getMinZ() && pos.getZ() + 1 <= border.getMaxZ();
    }

    private static boolean isSupport(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (DANGEROUS.contains(state.getBlock())) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        // Something to stand on, but not fences/walls (taller than a block)
        return !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) <= 1.0;
    }

    private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> cameFrom, BlockPos end, BlockPos start) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos pos = end;
        while (!pos.equals(start)) {
            path.add(pos);
            pos = cameFrom.get(pos);
        }
        Collections.reverse(path);
        return path;
    }
}
