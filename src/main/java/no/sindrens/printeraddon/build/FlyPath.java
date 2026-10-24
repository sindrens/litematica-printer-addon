package no.sindrens.printeraddon.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * A flight path through the air, around or over whatever is in the way (buildings, trees, the farm itself), never
 * through it: A* over the blocks the player's body (2 blocks high) fits in, with diagonal steps only where both
 * corners are free. The path is then straightened (waypoints only where the direction has to change).
 */
public final class FlyPath {
    /** Most blocks looked at for one path (a few ms). */
    private static final int MAX_NODES = 24000;
    /** How far above the highest point of start and goal the path may climb. */
    private static final int MAX_CLIMB = 48;
    /**
     * The distance still to go counts this much more than the way so far: the search heads for the goal instead of
     * spreading out evenly (a 140 block flight past a building used up every step before and found nothing), at the
     * cost of a slightly longer way now and then.
     */
    private static final double GREED = 1.5;
    /** Going up costs more than going sideways: around a building rather than over it. */
    private static final double CLIMB_COST = 1.5;

    private FlyPath() {
    }

    /** Feet positions from start to goal (excluding the start), or null if there's no way within the limits. */
    @Nullable
    public static List<Vec3> find(Level level, Vec3 from, Vec3 to) {
        from = lift(level, from);
        BlockPos start = BlockPos.containing(from.x, from.y + 0.01, from.z);
        BlockPos goal = BlockPos.containing(to.x, to.y + 0.01, to.z);
        if (start.equals(goal)) {
            return List.of(to);
        }
        if (!fits(level, goal)) {
            return null;
        }
        List<BlockPos> blocks = search(level, start, goal.getY(), goal::equals, p -> distance(p, goal));
        return blocks == null ? null : straighten(level, from, blocks, to);
    }

    /**
     * A way to any spot the player fits in from where the block is in reach: the eye within {@code reach} of the
     * block's centre, but more than 1 block from the eye and the feet (the printer never places that close), and not
     * in a spot to {@code avoid} (where blocks still have to go). For when the planned spot can't be got to. The last
     * point is that spot (feet). Null if there's none within the limits.
     */
    @Nullable
    public static List<Vec3> findNear(Level level, Vec3 from, BlockPos block, double reach, double eyeHeight, Predicate<BlockPos> avoid) {
        from = lift(level, from);
        Vec3 center = Vec3.atCenterOf(block);
        BlockPos start = BlockPos.containing(from.x, from.y + 0.01, from.z);
        Predicate<BlockPos> goal = p -> reaches(p, center, reach, eyeHeight) && !avoid.test(p) && !avoid.test(p.above());
        if (goal.test(start) && fits(level, start)) {
            return List.of(feet(start));
        }
        List<BlockPos> blocks = search(level, start, block.getY(), goal,
                p -> Math.max(0, Math.sqrt(feet(p).add(0, eyeHeight, 0).distanceToSqr(center)) - reach));
        return blocks == null ? null : straighten(level, from, blocks, feet(blocks.getLast()));
    }

    /**
     * The first part of a long way: to a free spot about {@code length} blocks along the line, as high as the higher
     * end (or higher, if that's inside something). The rest is worked out from there. Null if there's none.
     */
    @Nullable
    public static List<Vec3> leg(Level level, Vec3 from, Vec3 to, double length) {
        Vec3 d = to.subtract(from);
        double total = d.length();
        if (total < 1) {
            return null;
        }
        Vec3 point = from.add(d.scale(Math.min(1, length / total)));
        int y = (int) Math.floor(Math.max(from.y, to.y) + 0.01);
        for (int up = 0; up <= 16; up += 8) {
            BlockPos goal = freeNear(level, BlockPos.containing(point.x, y + up, point.z), 3);
            if (goal == null) {
                continue;
            }
            List<Vec3> way = find(level, from, feet(goal));
            if (way != null) {
                return way;
            }
        }
        return null;
    }

    /** The nearest spot the player fits in, within {@code radius} blocks of this one (null if none). */
    @Nullable
    public static BlockPos freeNear(Level level, BlockPos center, int radius) {
        for (int r = 0; r <= radius; r++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
                            continue;
                        }
                        BlockPos pos = center.offset(dx, dy, dz);
                        double distance = dx * dx + dy * dy + dz * dz;
                        if (distance < bestDistance && fits(level, pos)) {
                            bestDistance = distance;
                            best = pos;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** A* from start to the first block that's a goal, or null when there's none within the limits. */
    @Nullable
    private static List<BlockPos> search(Level level, BlockPos start, int goalY, Predicate<BlockPos> isGoal,
                                         ToDoubleFunction<BlockPos> estimate) {
        int maxY = Math.max(start.getY(), goalY) + MAX_CLIMB;
        int minY = level.getMinY() + 1;

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[1], (double) b[1]));
        cost.put(start, 0.0);
        open.add(new Object[]{start, GREED * estimate.applyAsDouble(start)});
        int looked = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (!open.isEmpty() && looked++ < MAX_NODES) {
            BlockPos current = (BlockPos) open.poll()[0];
            if (isGoal.test(current)) {
                return rebuild(cameFrom, current);
            }
            double here = cost.get(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        next.set(current.getX() + dx, current.getY() + dy, current.getZ() + dz);
                        if (next.getY() > maxY || next.getY() < minY || cost.containsKey(next) && cost.get(next) <= here + 1) {
                            continue;
                        }
                        if (!fits(level, next) || !cornersFree(level, current, dx, dy, dz)) {
                            continue;
                        }
                        double step = Math.sqrt(dx * dx + dy * dy + dz * dz) * (dy > 0 ? CLIMB_COST : 1);
                        double total = here + step;
                        BlockPos immutable = next.immutable();
                        Double known = cost.get(immutable);
                        if (known != null && known <= total) {
                            continue;
                        }
                        cost.put(immutable, total);
                        cameFrom.put(immutable, current);
                        open.add(new Object[]{immutable, total + GREED * estimate.applyAsDouble(immutable)});
                    }
                }
            }
        }
        return null;
    }

    /** From these feet the block is in the printer's reach, and not too close to be placed. */
    private static boolean reaches(BlockPos feet, Vec3 center, double reach, double eyeHeight) {
        Vec3 at = feet(feet);
        double d = at.add(0, eyeHeight, 0).distanceToSqr(center);
        return d <= reach * reach && d > 1 && at.distanceToSqr(center) > 1;
    }

    private static Vec3 feet(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** The player (2 blocks high) fits at these feet. */
    public static boolean fits(Level level, BlockPos feet) {
        return PathFinder.isPassable(level, feet) && PathFinder.isPassable(level, feet.above());
    }

    /** A diagonal step is only allowed when the straight steps it's made of are free too (no corner cutting). */
    private static boolean cornersFree(Level level, BlockPos from, int dx, int dy, int dz) {
        int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (axes < 2) {
            return true;
        }
        return (dx == 0 || fits(level, from.offset(dx, 0, 0)))
                && (dy == 0 || fits(level, from.offset(0, dy, 0)))
                && (dz == 0 || fits(level, from.offset(0, 0, dz)));
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos at = end; at != null; at = cameFrom.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return path;
    }

    /** Only the points where the way has to bend: from each point, the furthest one still in a straight free line. */
    private static List<Vec3> straighten(Level level, Vec3 from, List<BlockPos> blocks, Vec3 to) {
        List<Vec3> points = new ArrayList<>();
        for (BlockPos b : blocks) {
            points.add(feet(b));
        }
        points.set(points.size() - 1, to);
        List<Vec3> out = new ArrayList<>();
        Vec3 at = from;
        int i = 0;
        while (i < points.size()) {
            int furthest = i;
            for (int j = points.size() - 1; j > i; j--) {
                if (clear(level, at, points.get(j))) {
                    furthest = j;
                    break;
                }
            }
            at = points.get(furthest);
            out.add(at);
            i = furthest + 1;
        }
        return out;
    }

    /** The player's body can fly straight from a to b without touching a block (checked every 0.3 blocks). */
    public static boolean clear(Level level, Vec3 a, Vec3 b) {
        return clear(level, a, b, false);
    }

    /** Like clear, but parts that aren't loaded count as free (nothing is known to be in the way there). */
    public static boolean clearAsFarAsKnown(Level level, Vec3 a, Vec3 b) {
        return clear(level, a, b, true);
    }

    /**
     * The player resting on a block lower than a whole one (farmland, mud, soul sand, a path) has the feet a little
     * inside that block's space: for the way from there, the feet count as on top of it. Without this every way from
     * there started by going down into the block, and the player stood still for minutes (seen in a test on farmland).
     */
    static Vec3 lift(Level level, Vec3 from) {
        double floor = Math.floor(from.y + 0.01);
        if (from.y + 0.01 - floor > 0.5 && !PathFinder.isPassable(level, BlockPos.containing(from.x, from.y + 0.01, from.z))
                && PathFinder.isPassable(level, BlockPos.containing(from.x, floor + 1.01, from.z))) {
            return new Vec3(from.x, floor + 1, from.z);
        }
        return from;
    }

    private static boolean clear(Level level, Vec3 a, Vec3 b, boolean unloadedIsFree) {
        a = lift(level, a);
        Vec3 d = b.subtract(a);
        double length = d.length();
        int steps = Math.max(1, (int) Math.ceil(length / 0.3));
        for (int s = 0; s <= steps; s++) {
            Vec3 p = a.add(d.scale((double) s / steps));
            // The corners of the player's box (0.6 wide, 1.8 high)
            for (double ox : new double[]{-0.3, 0.3}) {
                for (double oz : new double[]{-0.3, 0.3}) {
                    for (double oy : new double[]{0.0, 0.9, 1.8}) {
                        BlockPos pos = BlockPos.containing(p.x + ox, p.y + oy, p.z + oz);
                        if (!PathFinder.isPassable(level, pos) && !(unloadedIsFree && !level.isLoaded(pos))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
