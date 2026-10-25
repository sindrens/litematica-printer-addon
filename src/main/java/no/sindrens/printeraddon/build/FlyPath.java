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
    /**
     * Flying sideways higher than both ends costs this much more per block of height: up late (right before what's in
     * the way) and down early, not up a long ramp from far away (the printer can't reach the layer from up there).
     */
    private static final double HIGH_COST = 0.08;
    /** Along a straight line: higher than the base height, and lower (down into the layer), per block flown. */
    private static final double LINE_HIGH_COST = 0.6;
    private static final double LINE_LOW_COST = 3;
    /** A straight shortcut between two points of a way: only level, or this short (no ramps). */
    private static final double SHORTCUT_RAMP = 2.5;
    /**
     * A step to a spot right beside (or right over) a cactus or anything else that hurts costs this much more: past it
     * with room where there is room. Between two lanes the way went right between two grown cactus columns of the
     * player's tower, and the player touched one.
     */
    private static final double HARM_COST = 3;

    private FlyPath() {
    }

    /** Feet positions from start to goal (excluding the start), or null if there's no way within the limits. */
    @Nullable
    public static List<Vec3> find(Level level, Vec3 from, Vec3 to) {
        return find(level, from, to, p -> true);
    }

    /**
     * Same, only through the spots that are {@code allowed} (a lane's line: over what's in the way, not around it).
     */
    @Nullable
    public static List<Vec3> find(Level level, Vec3 from, Vec3 to, Predicate<BlockPos> allowed) {
        from = lift(level, from);
        BlockPos start = BlockPos.containing(from.x, from.y + 0.01, from.z);
        BlockPos goal = BlockPos.containing(to.x, to.y + 0.01, to.z);
        if (start.equals(goal)) {
            return List.of(to);
        }
        if (!fits(level, goal)) {
            return null;
        }
        List<BlockPos> blocks = search(level, start, goal.getY(), goal::equals, p -> distance(p, goal), allowed);
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
                p -> Math.max(0, Math.sqrt(feet(p).add(0, eyeHeight, 0).distanceToSqr(center)) - reach), p -> true);
        return blocks == null ? null : straighten(level, from, blocks, feet(blocks.getLast()));
    }

    /**
     * The first part of a long way: to a free spot about {@code length} blocks along the line, as high as the higher
     * end (or higher, if that's inside something). The rest is worked out from there. Null if there's none.
     */
    @Nullable
    public static List<Vec3> leg(Level level, Vec3 from, Vec3 to, double length) {
        return leg(level, from, to, length, p -> true);
    }

    /** Same, only through the spots that are {@code allowed}. */
    @Nullable
    public static List<Vec3> leg(Level level, Vec3 from, Vec3 to, double length, Predicate<BlockPos> allowed) {
        Vec3 d = to.subtract(from);
        double total = d.length();
        if (total < 1) {
            return null;
        }
        Vec3 point = from.add(d.scale(Math.min(1, length / total)));
        int y = (int) Math.floor(Math.max(from.y, to.y) + 0.01);
        for (int up = 0; up <= 16; up += 8) {
            BlockPos goal = freeNear(level, BlockPos.containing(point.x, y + up, point.z), 3, allowed);
            if (goal == null) {
                continue;
            }
            List<Vec3> way = find(level, from, feet(goal), allowed);
            if (way != null) {
                return way;
            }
        }
        return null;
    }

    /** The nearest spot the player fits in, within {@code radius} blocks of this one (null if none). */
    @Nullable
    public static BlockPos freeNear(Level level, BlockPos center, int radius) {
        return freeNear(level, center, radius, p -> true);
    }

    @Nullable
    private static BlockPos freeNear(Level level, BlockPos center, int radius, Predicate<BlockPos> allowed) {
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
                        if (distance < bestDistance && allowed.test(pos) && fits(level, pos)) {
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
                                         ToDoubleFunction<BlockPos> estimate, Predicate<BlockPos> allowed) {
        int maxY = Math.max(start.getY(), goalY) + MAX_CLIMB;
        int minY = level.getMinY() + 1;
        int baseY = Math.max(start.getY(), goalY);

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        Map<Long, Boolean> harm = new HashMap<>();
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
                        if (!allowed.test(next) || !fits(level, next) || !cornersFree(level, current, dx, dy, dz)) {
                            continue;
                        }
                        double step = Math.sqrt(dx * dx + dy * dy + dz * dz) * (dy > 0 ? CLIMB_COST : 1);
                        if (dy == 0 && next.getY() > baseY) {
                            step += HIGH_COST * (next.getY() - baseY);
                        }
                        if (harm.computeIfAbsent(next.asLong(), k -> besideHarm(level, BlockPos.of(k)))) {
                            step += HARM_COST;
                        }
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

    /**
     * Something that hurts (a cactus...) beside the body at these feet. Not under them: over a layer of cactus the
     * player hovers a little higher anyway (see LayerManager#hoverLift), and those spots aren't to be avoided.
     */
    private static boolean besideHarm(Level level, BlockPos feet) {
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (hurtsAt(level, feet.relative(side)) || hurtsAt(level, feet.above().relative(side))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hurtsAt(Level level, BlockPos pos) {
        return level.isLoaded(pos) && PathFinder.hurts(level.getBlockState(pos));
    }

    /**
     * Flying straight from a to b doesn't bring the body nearer a cactus (or anything else that hurts) than the safety
     * room (see Mover#harmNear) anywhere between them: a shortcut mustn't cut past one the way itself keeps away from
     * (where it can't, the way's own points are kept).
     */
    private static boolean harmFree(Level level, Vec3 a, Vec3 b, Vec3 to) {
        // The way at the goal's height is flown that little higher (see Mover#withLift)
        int goalY = (int) Math.floor(to.y + 0.01);
        double lift = to.y - goalY;
        Vec3 d = b.subtract(a);
        int steps = Math.max(1, (int) Math.ceil(d.length() / 0.3));
        for (int s = 1; s < steps; s++) {
            Vec3 p = a.add(d.scale((double) s / steps));
            if (Mover.harmNear(level, Math.abs(p.y - goalY) < 0.01 ? p.add(0, lift, 0) : p) != null) {
                return false;
            }
        }
        return true;
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
                if (shortcutOk(at, points.get(j)) && clear(level, at, points.get(j)) && harmFree(level, at, points.get(j), to)) {
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

    /**
     * A straight line from a to b is fine as a shortcut: level, or short. A long slope would climb far before what's in
     * the way (a 30 block ramp up over a farm's walls) or come down far after it, out of the printer's reach.
     */
    public static boolean shortcutOk(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.abs(b.y - a.y) < 0.5 || dx * dx + dz * dz <= SHORTCUT_RAMP * SHORTCUT_RAMP;
    }

    /**
     * The way along the straight line from {@code from} to {@code to} (seen from above): at {@code baseY} wherever the
     * player fits there, straight up right before what's in the way and straight down right after it, also between two
     * walls; at the end down (or up) to {@code to}. A lane climbed a 30 block ramp to fly over the walls of hoppers in
     * the middle of the player's farm, stayed up over the whole middle and placed nothing there. Only the points where
     * the way turns, to be flown as they are (no shortcuts). Parts not loaded yet count as free when
     * {@code unloadedIsFree}. Null if there's no such way (then one around what's in the way is looked for).
     */
    @Nullable
    public static List<Vec3> lineWay(Level level, Vec3 from, Vec3 to, int baseY, boolean unloadedIsFree) {
        from = lift(level, from);
        double hx = to.x - from.x;
        double hz = to.z - from.z;
        double length = Math.sqrt(hx * hx + hz * hz);
        int samples = Math.max(1, (int) Math.ceil(length / 0.5)) + 1;
        int y0 = (int) Math.floor(from.y + 0.01);
        int goalY = (int) Math.floor(to.y + 0.01);
        // The way at the goal's height is flown that little higher (see Mover#withLift)
        double lift = to.y - goalY;
        int minY = Math.min(Math.min(y0, goalY), baseY);
        int maxY = Math.max(Math.max(y0, goalY), baseY) + MAX_CLIMB;
        int height = maxY - minY + 1;
        // Per sample and height: 0 not looked at, 1 fits, 2 doesn't
        byte[] fit = new byte[samples * height];
        double[] sx = new double[samples];
        double[] sz = new double[samples];
        for (int s = 0; s < samples; s++) {
            double t = samples == 1 ? 1 : (double) s / (samples - 1);
            sx[s] = from.x + hx * t;
            sz[s] = from.z + hz * t;
        }
        java.util.function.IntBinaryOperator fits = (s, y) -> {
            int i = s * height + (y - minY);
            if (fit[i] == 0) {
                boolean ok = true;
                // The columns the player's box (0.6 wide) is in there
                for (int x = (int) Math.floor(sx[s] - 0.3); x <= (int) Math.floor(sx[s] + 0.3) && ok; x++) {
                    for (int z = (int) Math.floor(sz[s] - 0.3); z <= (int) Math.floor(sz[s] + 0.3) && ok; z++) {
                        for (int dy = 0; dy <= 1 && ok; dy++) {
                            BlockPos pos = new BlockPos(x, y + dy, z);
                            ok = PathFinder.insideBorder(level, pos) && (level.isLoaded(pos) ? PathFinder.isPassable(level, pos) : unloadedIsFree);
                        }
                    }
                }
                // On the way (not where it starts or ends) also the safety room from a cactus: down right after a grown
                // cactus column, the player was pushed up by the cactus and pulled down by the way, and hung there 25 s
                if (ok && s > 0 && s < samples - 1) {
                    ok = Mover.harmNear(level, new Vec3(sx[s], y + (y == goalY ? lift : 0), sz[s])) == null;
                }
                fit[i] = (byte) (ok ? 1 : 2);
            }
            return fit[i];
        };
        if (fits.applyAsInt(0, y0) != 1 || fits.applyAsInt(samples - 1, goalY) != 1) {
            return null;
        }
        double step = length / Math.max(1, samples - 1);
        double[] cost = new double[samples * height];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        int[] came = new int[samples * height];
        java.util.Arrays.fill(came, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        int startIndex = y0 - minY;
        int goalIndex = (samples - 1) * height + (goalY - minY);
        cost[startIndex] = 0;
        open.add(new double[]{(samples - 1) * step, startIndex});
        while (!open.isEmpty()) {
            int node = (int) open.poll()[1];
            if (node == goalIndex) {
                break;
            }
            int s = node / height;
            int y = node % height + minY;
            double here = cost[node];
            for (int move = 0; move < 4; move++) {
                int ns = s + (move == 0 ? 1 : move == 1 ? -1 : 0);
                int ny = y + (move == 2 ? 1 : move == 3 ? -1 : 0);
                if (ns < 0 || ns >= samples || ny < minY || ny > maxY || fits.applyAsInt(ns, ny) != 1) {
                    continue;
                }
                double add;
                if (move < 2) {
                    add = step * (1 + (ny > baseY ? LINE_HIGH_COST * (ny - baseY) : 0) + (ny < baseY ? LINE_LOW_COST * (baseY - ny) : 0));
                } else {
                    add = move == 2 ? 1 : 0.6;
                }
                int next = ns * height + (ny - minY);
                if (here + add < cost[next]) {
                    cost[next] = here + add;
                    came[next] = node;
                    open.add(new double[]{here + add + (samples - 1 - ns) * step, next});
                }
            }
        }
        if (came[goalIndex] < 0 && goalIndex != startIndex) {
            return null;
        }
        // Back from the goal: only the points where the way turns (along to up or down, and back)
        List<int[]> nodes = new ArrayList<>();
        for (int node = goalIndex; node >= 0; node = came[node]) {
            nodes.add(new int[]{node / height, node % height + minY});
            if (node == startIndex) {
                break;
            }
        }
        Collections.reverse(nodes);
        // A little room from what's in the way: straight up half a block before the last place the player fits in
        // front of it, and down half a block after it, where there's room (going up from right in front of a building
        // the way along carried the player on into it, 10 bumps)
        for (int i = 1; i < nodes.size(); ) {
            if (nodes.get(i)[0] != nodes.get(i - 1)[0]) {
                i++;
                continue;
            }
            int start = i - 1;
            int end = i;
            while (end + 1 < nodes.size() && nodes.get(end + 1)[0] == nodes.get(start)[0]) {
                end++;
            }
            int s = nodes.get(start)[0];
            int yA = nodes.get(start)[1];
            int yB = nodes.get(end)[1];
            boolean alongBefore = start > 0 && nodes.get(start - 1)[0] == s - 1 && nodes.get(start - 1)[1] == yA;
            boolean alongAfter = end + 1 < nodes.size() && nodes.get(end + 1)[0] == s + 1 && nodes.get(end + 1)[1] == yB;
            int shift = yB > yA && alongBefore ? -1 : yB < yA && alongAfter ? 1 : 0;
            int moved = s + shift;
            boolean room = shift != 0 && moved >= 0 && moved < samples;
            for (int y = Math.min(yA, yB); y <= Math.max(yA, yB) && room; y++) {
                room = fits.applyAsInt(moved, y) == 1;
            }
            if (room) {
                for (int k = start; k <= end; k++) {
                    nodes.get(k)[0] = moved;
                }
            }
            i = end + 1;
        }
        List<Vec3> way = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            int[] at = nodes.get(i);
            boolean last = i == nodes.size() - 1;
            if (!last) {
                int[] before = nodes.get(i - 1);
                int[] after = nodes.get(i + 1);
                boolean alongIn = before[1] == at[1];
                boolean alongOut = after[1] == at[1];
                if (alongIn == alongOut) {
                    continue;
                }
            }
            way.add(last ? to : new Vec3(sx[at[0]], at[1], sz[at[0]]));
        }
        return way.isEmpty() ? List.of(to) : way;
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
                        if (!PathFinder.insideBorder(level, pos)
                                || !PathFinder.isPassable(level, pos) && !(unloadedIsFree && !level.isLoaded(pos))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
