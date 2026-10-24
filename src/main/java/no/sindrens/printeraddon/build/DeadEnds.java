package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hoppers that can't go in the normal way: a hopper facing sideways is placed by clicking the block it faces, so a
 * row of them goes in from its end, one by one. A row whose end faces an empty spot (nothing there in the schematic,
 * nor in the world) can never go in. Such rows are found when a layer starts, left out of it (so the layer finishes
 * and nothing flies to them again and again), said once with the spot to fill, and built as soon as something is
 * at that spot, or tried again at the end of the build.
 */
public final class DeadEnds {
    /** The empty spot a row ends at, and the hoppers of that row (not placed yet). */
    private static final Map<BlockPos, List<BlockPos>> rows = new LinkedHashMap<>();
    private static long lastCheck;

    private DeadEnds() {
    }

    public static void reset() {
        rows.clear();
    }

    /** At the start of a layer (from y to top): its dead-end rows, left out of it and said once. */
    public static void findInLayers(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y, int top) {
        Map<BlockPos, List<BlockPos>> found = new LinkedHashMap<>();
        // Every hopper of a row has the same end: worked out once per row
        Map<BlockPos, BlockPos> known = new java.util.HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int layer = y; layer <= top; layer++) {
            for (SchematicHelper.Area area : areas) {
                if (layer < area.minY() || layer > area.maxY()) {
                    continue;
                }
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, layer, z);
                        BlockState want = schematic.getBlockState(pos);
                        if (!sideways(want) || level.getBlockState(pos).is(want.getBlock()) || !level.isLoaded(pos)) {
                            continue;
                        }
                        BlockPos end = deadEnd(level, schematic, pos.immutable(), known);
                        if (end != null) {
                            found.computeIfAbsent(end, k -> new ArrayList<>()).add(pos.immutable());
                        }
                    }
                }
            }
        }
        for (Map.Entry<BlockPos, List<BlockPos>> row : found.entrySet()) {
            if (rows.containsKey(row.getKey())) {
                continue;
            }
            rows.put(row.getKey(), row.getValue());
            AutoBuild.INSTANCE.couldNotPlace.addAll(row.getValue());
            BlockPos first = row.getValue().getFirst();
            String facing = schematic.getBlockState(first).getValue(HopperBlock.FACING).getName();
            BuildLog.line("DEADEND", row.getValue().size() + " hoppers (facing " + facing + ", from " + first.toShortString()
                    + ") end at an empty spot, " + row.getKey().toShortString() + ": left out until something is there");
            Chat.warn(row.getValue().size() + " hoppers can't be placed: their row ends at an empty spot, "
                    + row.getKey().getX() + " " + row.getKey().getY() + " " + row.getKey().getZ()
                    + " (the schematic has nothing there). Put a block there (a hopper, if the row should go on) and they go in;"
                    + " the layer goes on without them meanwhile.");
        }
    }

    /** Every few seconds: a row whose empty spot now has something in it is built now. */
    public static void tick(Level level) {
        if (rows.isEmpty() || AutoBuild.clientTicks - lastCheck < 20 * 3) {
            return;
        }
        lastCheck = AutoBuild.clientTicks;
        for (Iterator<Map.Entry<BlockPos, List<BlockPos>>> it = rows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, List<BlockPos>> row = it.next();
            if (level.isLoaded(row.getKey()) && !level.getBlockState(row.getKey()).canBeReplaced()) {
                it.remove();
                AutoBuild.INSTANCE.couldNotPlace.removeAll(row.getValue());
                BuildLog.line("DEADEND", "something is at " + row.getKey().toShortString() + " now: its " + row.getValue().size()
                        + " hoppers go in");
                Chat.info("Something is at " + row.getKey().getX() + " " + row.getKey().getY() + " " + row.getKey().getZ()
                        + " now: placing the " + row.getValue().size() + " hoppers of that row.");
            }
        }
    }

    private static boolean sideways(BlockState state) {
        return state.getBlock() instanceof HopperBlock && state.getValue(HopperBlock.FACING) != Direction.DOWN;
    }

    /** Marks a row's hoppers that end at something to click. */
    private static final BlockPos ALIVE = new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    /**
     * Where the row this hopper is in ends at an empty spot, or null when it ends at something to click (a block
     * already there, a hopper facing down, any other block of the schematic). {@code known}: what earlier calls found
     * for the hoppers they went past.
     */
    @Nullable
    static BlockPos deadEnd(Level level, WorldSchematic schematic, BlockPos start, Map<BlockPos, BlockPos> known) {
        List<BlockPos> path = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos pos = start;
        BlockPos end = ALIVE;
        for (int steps = 0; steps < 1024; steps++) {
            BlockPos already = known.get(pos);
            if (already != null) {
                end = already;
                break;
            }
            path.add(pos);
            BlockPos next = pos.relative(schematic.getBlockState(pos).getValue(HopperBlock.FACING));
            if (!level.isLoaded(next) || !level.getBlockState(next).canBeReplaced()) {
                // Something to click there already (or not known yet)
                break;
            }
            BlockState nextWant = schematic.getBlockState(next);
            if (nextWant.isAir()) {
                end = next;
                break;
            }
            if (!sideways(nextWant)) {
                break;
            }
            if (!seen.add(next)) {
                // A ring of hoppers with nothing to start from
                end = next;
                break;
            }
            pos = next;
        }
        for (BlockPos p : path) {
            known.put(p, end);
        }
        return end == ALIVE ? null : end;
    }
}
