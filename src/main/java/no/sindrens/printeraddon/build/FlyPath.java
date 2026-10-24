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

    private FlyPath() {
    }

    /** Feet positions from start to goal (excluding the start), or null if there's no way within the limits. */
    @Nullable
    public static List<Vec3> find(Level level, Vec3 from, Vec3 to) {
        BlockPos start = BlockPos.containing(from.x, from.y + 0.01, from.z);
        BlockPos goal = BlockPos.containing(to.x, to.y + 0.01, to.z);
        if (start.equals(goal)) {
            return List.of(to);
        }
        if (!fits(level, goal)) {
            return null;
        }
        int maxY = Math.max(start.getY(), goal.getY()) + MAX_CLIMB;
        int minY = level.getMinY() + 1;

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[1], (double) b[1]));
        cost.put(start, 0.0);
        open.add(new Object[]{start, distance(start, goal)});
        int looked = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (!open.isEmpty() && looked++ < MAX_NODES) {
            BlockPos current = (BlockPos) open.poll()[0];
            if (current.equals(goal)) {
                return straighten(level, from, rebuild(cameFrom, current), to);
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
                        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double total = here + step;
                        BlockPos immutable = next.immutable();
                        Double known = cost.get(immutable);
                        if (known != null && known <= total) {
                            continue;
                        }
                        cost.put(immutable, total);
                        cameFrom.put(immutable, current);
                        open.add(new Object[]{immutable, total + distance(immutable, goal)});
                    }
                }
            }
        }
        return null;
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
            points.add(new Vec3(b.getX() + 0.5, b.getY(), b.getZ() + 0.5));
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
                        if (!PathFinder.isPassable(level, pos)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
