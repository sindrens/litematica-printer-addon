package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import no.sindrens.printeraddon.util.Chat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where the player may build ends: the island's edge on a skyblock server, learned from the clicks the server refuses
 * there. A 200x200 platform on a 125-wide island had the lanes fly to the edge again and again, click the row past it
 * 4 times each (given up, tried again 30 s later), the speed slowed to interval 10 by those refusals, and the lane
 * stopped 3 times "without getting closer" against the invisible wall. Once 3 blocks in a line past placed blocks are
 * given up on (refused close by, against a block that's there), everything past that line is left out of the build:
 * not counted, not bought for, not flown to, not clicked. Kept for the server until the game closes or the world changes.
 * Blocks outside the world border (as the server checks it) are left out the same way.
 */
public final class Edge {
    private static final int VOTES = 3;
    /** First and last coordinate that can be built on (null: no edge learned on that side). */
    private static Integer minX;
    private static Integer maxX;
    private static Integer minZ;
    private static Integer maxZ;
    private static final Map<String, Set<Integer>> votes = new HashMap<>();
    private static String world = "";
    private static int leftOut;

    private Edge() {
    }

    /** Called when a run starts: the edge stays for the same server and dimension, forgotten for another. */
    public static void start(Minecraft mc) {
        String now = no.sindrens.printeraddon.util.ServerName.current(mc) + "|" + (mc.level == null ? "" : mc.level.dimension().toString());
        if (!now.equals(world)) {
            world = now;
            minX = maxX = minZ = maxZ = null;
            votes.clear();
        }
        leftOut = 0;
        if (known()) {
            BuildLog.line("EDGE", "building stops at the edge learned before: " + describe());
        }
    }

    public static boolean known() {
        return minX != null || maxX != null || minZ != null || maxZ != null;
    }

    /** "x -3062 to ?, z ? to 62" for the log. */
    public static String describe() {
        return "x " + (minX == null ? "?" : minX) + " to " + (maxX == null ? "?" : maxX)
                + ", z " + (minZ == null ? "?" : minZ) + " to " + (maxZ == null ? "?" : maxZ);
    }

    /** True for a block that can't be built here: past the learned edge, or outside the world border. */
    public static boolean outside(Level level, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        if (minX != null && x < minX || maxX != null && x > maxX || minZ != null && z < minZ || maxZ != null && z > maxZ) {
            return true;
        }
        return level != null && !level.getWorldBorder().isWithinBounds(pos);
    }

    /**
     * The printer gave up on {@code pos} (clicked 4 times, nothing changed). Counted as a sign of the edge when it's
     * still empty, close to the player (so not the reach), with a block beside it on one side and nothing on the other:
     * the click went against the inner block and was refused.
     */
    public static void gaveUp(Minecraft mc, BlockPos pos) {
        if (mc.level == null || mc.player == null || !mc.level.getBlockState(pos).canBeReplaced()) {
            return;
        }
        if (mc.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 4.0) {
            return;
        }
        for (Direction in : Direction.Plane.HORIZONTAL) {
            if (mc.level.getBlockState(pos.relative(in)).canBeReplaced() || !mc.level.getBlockState(pos.relative(in.getOpposite())).canBeReplaced()) {
                continue;
            }
            Direction out = in.getOpposite();
            int line = out.getAxis() == Direction.Axis.X ? pos.getX() : pos.getZ();
            int along = out.getAxis() == Direction.Axis.X ? pos.getZ() : pos.getX();
            Set<Integer> seen = votes.computeIfAbsent(out + ":" + line, k -> new HashSet<>());
            seen.add(along);
            if (seen.size() >= VOTES) {
                learn(out, line);
            }
        }
    }

    private static void learn(Direction out, int line) {
        String before = describe();
        switch (out) {
            case WEST -> minX = minX == null ? line + 1 : Math.max(minX, line + 1);
            case EAST -> maxX = maxX == null ? line - 1 : Math.min(maxX, line - 1);
            case NORTH -> minZ = minZ == null ? line + 1 : Math.max(minZ, line + 1);
            case SOUTH -> maxZ = maxZ == null ? line - 1 : Math.min(maxZ, line - 1);
            default -> {
                return;
            }
        }
        if (before.equals(describe())) {
            return;
        }
        String axis = out.getAxis() == Direction.Axis.X ? "x" : "z";
        BuildLog.line("EDGE", "the server refuses blocks at " + axis + " " + line + " (3 or more in a line, close by, against a block that's there):"
                + " that's the edge of where you can build, everything past it is left out | now " + describe());
        Chat.warn("The server refuses blocks past " + axis + " " + (out.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? line + 1 : line - 1)
                + " (your island's edge?): the schematic goes past it there, so that part is left out of the build.");
    }

    /** Counted by BlockCheck for the summary. */
    static void countedOut() {
        leftOut++;
    }

    public static int leftOut() {
        return leftOut;
    }
}
