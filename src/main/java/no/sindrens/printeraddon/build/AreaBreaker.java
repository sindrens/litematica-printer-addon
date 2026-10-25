package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CactusFlowerBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auto break: every block of Litematica's area selection is broken, the top layer first (nothing falls on the player,
 * sand doesn't drop into the gaps), each level in straight lanes (see layLanes), each block with the fastest tool,
 * standing where it can (the game breaks 5 times slower in the air, see BlockBreaker). Left alone: the block types
 * switched off in the Auto break tab, containers ("Never break containers"), liquids, what can't be broken, and what
 * holds back lava. A block that only stands on the one below it (cactus, sugar cane, bamboo, flowers, carpets) isn't
 * broken itself: it drops with the block under it (a cactus column on its sand is one break, not four). Drops: put away
 * in the vaults or linked chests when the inventory is full, or ignored ("Auto break: put drops away").
 */
public final class AreaBreaker {
    public static final AreaBreaker INSTANCE = new AreaBreaker();

    /** The selection's boxes when it started: minX, minY, minZ, maxX, maxY, maxZ. */
    private final List<int[]> boxes = new ArrayList<>();
    private boolean running;
    /** The level being broken now, and the lowest one. */
    private int layer;
    private int bottom;
    private long startedAt;
    private long layerStartedAt;
    private int brokenAtStart;
    private int layerBrokenAtStart;
    private int total;
    /** The current level's blocks still to break (loaded parts), and its parts not loaded yet (one spot each). */
    private final List<BlockPos> layerLeft = new ArrayList<>();
    private final List<BlockPos> layerUnloaded = new ArrayList<>();
    private long layerScannedAt = -1000;
    /** Blocks that couldn't be got to: left out until then (tick). */
    private final Map<BlockPos, Long> leftOut = new HashMap<>();
    @Nullable
    private BlockPos goal;
    private long goalSince;
    private long lastProgressAt;
    private int lastBroken;
    /** How many of each item the inventory had at the start: more of one than that came from breaking. */
    private final Map<Item, Integer> itemsAtStart = new HashMap<>();
    private long fullWarnedAt = -100000;
    private final Safety safety = new Safety();

    /**
     * Each level is broken in straight lanes 3 rows wide, back and forth along its longer side, from the edge and the
     * end nearest the player. The player stands on the next lane, still there, while breaking this one: standing
     * breaks are 5 times faster, and it never has to cross a hole. (The nearest block first broke a ring around the
     * player, who then had to get over the gap: "it mines in a circle around me".)
     */
    private static final int LANE_ROWS = 3;
    /** Lanes run along X (rows across them are Z), or along Z. */
    private boolean alongX = true;
    /** The first row of the first lane, and which way the lanes go across (+1 or -1). */
    private int acrossOrigin;
    private int acrossStep = 1;
    private int alongMin;
    private int alongMax;
    private int laneCount = 1;
    /** Lane 0 runs from alongMin up (else from alongMax down); every other lane the other way. */
    private boolean firstForward = true;
    /** The lane being broken: the later ones are left standing (the player stands on them). */
    private int currentLane;

    /** Leave a block out for this long when it can't be got to. */
    private static final int LEAVE_OUT_TICKS = 20 * 60;
    /** Given up on getting to a block after this long. */
    private static final int GOAL_TICKS = 20 * 20;
    /** Nothing broken for this long with blocks left: it stops, saying why. */
    private static final int NO_PROGRESS_TICKS = 20 * 120;
    /** Fewer free slots than this: the drops go into storage (with "Auto break: put drops away"). */
    private static final int FREE_SLOTS_MIN = 3;

    private AreaBreaker() {
    }

    public boolean isRunning() {
        return running;
    }

    // ---- the selection

    /** The boxes of Litematica's current area selection (minX, minY, minZ, maxX, maxY, maxZ), empty when there's none. */
    public static List<int[]> selection() {
        List<int[]> list = new ArrayList<>();
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) {
            return list;
        }
        for (Box box : selection.getAllSubRegionBoxes()) {
            BlockPos a = box.getPos1();
            BlockPos b = box.getPos2();
            if (a == null || b == null) {
                continue;
            }
            list.add(new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())});
        }
        return list;
    }

    /** "16 x 15 x 16" (one box) or "3 boxes", for the menu. */
    public static String describe(List<int[]> boxes) {
        if (boxes.isEmpty()) {
            return "none";
        }
        if (boxes.size() > 1) {
            return boxes.size() + " boxes";
        }
        int[] b = boxes.getFirst();
        return (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + " at " + b[0] + " " + b[1] + " " + b[2];
    }

    /** What the block types in the selection were counted for, and the counts (see blocksIn). */
    private static String countedFor = "";
    private static Map<Block, Integer> counted = Map.of();
    private static boolean countedAll = true;

    /**
     * How many of each block type the selection has where it's loaded (air and liquids not counted), most first.
     * Counted again when the selection changed or on {@code refresh}. A huge selection is counted up to 4 million spots.
     */
    public static Map<Block, Integer> blocksIn(Minecraft mc, List<int[]> boxes, boolean refresh) {
        StringBuilder key = new StringBuilder();
        boxes.forEach(b -> key.append(java.util.Arrays.toString(b)));
        if (!refresh && key.toString().equals(countedFor) || mc.level == null) {
            return counted;
        }
        Map<Block, Integer> counts = new HashMap<>();
        long seen = 0;
        boolean all = true;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        outer:
        for (int[] b : boxes) {
            for (int y = b[4]; y >= b[1]; y--) {
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (++seen > 4_000_000) {
                            all = false;
                            break outer;
                        }
                        pos.set(x, y, z);
                        if (!mc.level.isLoaded(pos)) {
                            continue;
                        }
                        BlockState state = mc.level.getBlockState(pos);
                        if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
                            counts.merge(state.getBlock(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        Map<Block, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        counted = sorted;
        countedFor = key.toString();
        countedAll = all;
        return counted;
    }

    /** False when the last count stopped at 4 million spots. */
    public static boolean countedAll() {
        return countedAll;
    }

    // ---- block types not to break

    private static String skipText;
    private static Set<Block> skipBlocks = Set.of();

    /** One of the block types switched off for Auto break (see Configs.Breaking.AUTO_BREAK_SKIP). */
    public static boolean skipped(Block block) {
        String text = Configs.Breaking.AUTO_BREAK_SKIP.getStringValue();
        if (!text.equals(skipText)) {
            skipText = text;
            Set<Block> blocks = new HashSet<>();
            for (String part : text.split("[,;\\s]+")) {
                String id = part.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) {
                    continue;
                }
                net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (key != null) {
                    BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
                }
            }
            skipBlocks = blocks;
        }
        return skipBlocks.contains(block);
    }

    /** Switches a block type between broken and left alone (the Auto break tab), and saves it. */
    public static void toggleSkip(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString().replace("minecraft:", "");
        List<String> ids = new ArrayList<>();
        for (String part : Configs.Breaking.AUTO_BREAK_SKIP.getStringValue().split("[,;\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty() && !p.replace("minecraft:", "").equals(id)) {
                ids.add(p);
            }
        }
        if (!skipped(block)) {
            ids.add(id);
        }
        Configs.Breaking.AUTO_BREAK_SKIP.setValueFromString(String.join(",", ids));
        Configs.saveToFile();
    }

    // ---- what is broken

    private boolean inBoxes(BlockPos pos) {
        for (int[] b : boxes) {
            if (pos.getX() >= b[0] && pos.getX() <= b[3] && pos.getY() >= b[1] && pos.getY() <= b[4]
                    && pos.getZ() >= b[2] && pos.getZ() <= b[5]) {
                return true;
            }
        }
        return false;
    }

    /** Something there that a break would take away (not air, not a liquid). */
    static boolean stillThere(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    /** Broken by Auto break (see the class): in the area, breakable, not left alone for any reason. */
    boolean wants(Level level, BlockPos pos) {
        if (!inBoxes(pos) || !level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return breakable(level, pos, state) && !dropsWithBelow(level, pos);
    }

    private boolean breakable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getDestroySpeed(level, pos) < 0 || skipped(state.getBlock())) {
            return false;
        }
        if (Configs.Breaking.SKIP_CONTAINERS.getBooleanValue() && level.getBlockEntity(pos) instanceof Container) {
            return false;
        }
        // Lava next to it would flow out
        for (Direction direction : Direction.values()) {
            if (level.getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }

    /** Stands on the block below and drops with it: cactus, sugar cane, bamboo, flowers and other plants, carpets. */
    private static boolean standsOnBelow(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CactusBlock || block instanceof SugarCaneBlock || block instanceof BambooStalkBlock
                || block instanceof CactusFlowerBlock || block instanceof VegetationBlock || block instanceof CarpetBlock;
    }

    /**
     * Drops by itself once the block under it is broken: it stands on the one below (see standsOnBelow), and down its
     * column the first block that doesn't is broken by Auto break, or is gone already. The lowest of such a column on a
     * block that stays is broken (the rest drops with it).
     */
    private boolean dropsWithBelow(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!standsOnBelow(state)) {
            return false;
        }
        // What held it was just broken: the server takes it along in a moment. It was broken by hand meanwhile, from
        // the air 3 seconds a cactus (more than half the time of a cactus farm)
        if (level.getBlockState(pos.below()).canBeReplaced() && !state.canSurvive(level, pos)) {
            return true;
        }
        BlockPos below = pos.below();
        for (int i = 0; i < 64 && standsOnBelow(level.getBlockState(below)); i++) {
            below = below.below();
        }
        // The column's base is broken: all of it drops then
        BlockPos base = below;
        if (inBoxes(base) && breakable(level, base, level.getBlockState(base))) {
            return true;
        }
        // The base stays: only the lowest of the column is broken
        return !pos.below().equals(base);
    }

    /**
     * For BlockBreaker (Mode.AREA): the block to break next within reach: the highest first (of any level: from one spot
     * it digs down as far as it reaches), then in lane order, then the nearest. Only up to the current lane: the later
     * lanes stay, the player stands on them. Only one with nothing still to break right above it, so each column still
     * goes from the top down. Never the block under the feet while standing, nor one left out or that failed a moment ago.
     */
    @Nullable
    BlockPos nextInReach(Minecraft mc, LocalPlayer player, double range) {
        if (!running || mc.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        BlockPos underFeet = BlockPos.containing(player.getX(), player.getY() - 0.1, player.getZ());
        // The block under the feet only when really in the air (flying, and not on the ground: the game ends flying
        // there after the tick's move, and the player falls into the hole)
        boolean flying = player.getAbilities().flying && !player.onGround();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        long bestOrder = Long.MAX_VALUE;
        int r = (int) Math.ceil(range);
        BlockPos center = BlockPos.containing(eye);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (pos.getY() < bottom || pos.getY() > layer || laneOf(pos) > currentLane) {
                continue;
            }
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance > range * range) {
                continue;
            }
            long order = orderOf(pos);
            if (best != null && (pos.getY() < best.getY()
                    || pos.getY() == best.getY() && (order > bestOrder || order == bestOrder && distance >= bestDistance))) {
                continue;
            }
            if (!flying && pos.equals(underFeet) || leftOut.containsKey(pos) || AutoBuild.INSTANCE.breaker.failedRecently(pos)
                    || !wants(mc.level, pos) || pos.getY() < layer && wants(mc.level, pos.above())) {
                continue;
            }
            best = pos.immutable();
            bestDistance = distance;
            bestOrder = order;
        }
        return best;
    }

    // ---- lanes

    /** Lays this level's lanes: along its longer side, from the edge and the end nearest the player. */
    private void layLanes(Vec3 player) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] b : boxes) {
            if (layer >= b[1] && layer <= b[4]) {
                minX = Math.min(minX, b[0]);
                minZ = Math.min(minZ, b[2]);
                maxX = Math.max(maxX, b[3]);
                maxZ = Math.max(maxZ, b[5]);
            }
        }
        if (minX > maxX) {
            return;
        }
        alongX = maxX - minX >= maxZ - minZ;
        int acrossMin = alongX ? minZ : minX;
        int acrossMax = alongX ? maxZ : maxX;
        alongMin = alongX ? minX : minZ;
        alongMax = alongX ? maxX : maxZ;
        double across = alongX ? player.z : player.x;
        double along = alongX ? player.x : player.z;
        acrossStep = Math.abs(across - acrossMin) <= Math.abs(across - acrossMax) ? 1 : -1;
        acrossOrigin = acrossStep > 0 ? acrossMin : acrossMax;
        laneCount = (acrossMax - acrossMin) / LANE_ROWS + 1;
        firstForward = Math.abs(along - alongMin) <= Math.abs(along - alongMax);
        currentLane = 0;
    }

    private int laneOf(BlockPos pos) {
        int across = alongX ? pos.getZ() : pos.getX();
        return Math.max(0, (across - acrossOrigin) * acrossStep / LANE_ROWS);
    }

    /** Lane by lane, and along each lane the way it runs: the order a level is broken in. */
    private long orderOf(BlockPos pos) {
        int lane = laneOf(pos);
        int along = alongX ? pos.getX() : pos.getZ();
        boolean forward = (lane % 2 == 0) == firstForward;
        return (long) lane * 1_000_000L + (forward ? along - alongMin : alongMax - along);
    }

    /**
     * Where to stand while breaking this block: on the next lane's first row beside it (or the row after), on a block
     * that's still there, with nothing that hurts near, the block in reach. After the last lane that's the ground
     * outside the area, if there is any. Null when there's no such spot (then from the air, see hoverFor).
     */
    @Nullable
    private Vec3 standBeside(Minecraft mc, LocalPlayer player, BlockPos pos) {
        double reach = player.blockInteractionRange() - 0.4;
        Vec3 center = Vec3.atCenterOf(pos);
        int nextRow = acrossOrigin + acrossStep * LANE_ROWS * (laneOf(pos) + 1);
        int along = alongX ? pos.getX() : pos.getZ();
        for (int extra = 0; extra <= 1; extra++) {
            for (int shift : new int[]{0, 1, -1}) {
                int row = nextRow + acrossStep * extra;
                BlockPos ground = alongX ? new BlockPos(along + shift, pos.getY(), row) : new BlockPos(row, pos.getY(), along + shift);
                BlockPos feet = ground.above();
                net.minecraft.world.phys.shapes.VoxelShape shape = mc.level.getBlockState(ground).getCollisionShape(mc.level, ground);
                if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.999 || !FlyPath.fits(mc.level, feet)) {
                    continue;
                }
                Vec3 spot = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                if (Mover.harmNear(mc.level, spot) == null && spot.add(0, player.getEyeHeight(), 0).distanceToSqr(center) <= reach * reach) {
                    return spot;
                }
            }
        }
        return null;
    }

    // ---- running

    /** Starts breaking the selected area. False (and why in chat) when it can't. */
    public boolean start(Minecraft mc) {
        return start(mc, selection());
    }

    /** Starts breaking these boxes (minX, minY, minZ, maxX, maxY, maxZ; the test gives its own). */
    public boolean start(Minecraft mc, List<int[]> selected) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            return false;
        }
        if (AutoBuild.INSTANCE.isRunning()) {
            Chat.warn("Auto build is running: stop it first (J+B), then start Auto break.");
            return false;
        }
        if (selected.isEmpty()) {
            Chat.warn("No area selected: select one with Litematica's area selection tool first (corner 1 and corner 2).");
            return false;
        }
        boxes.clear();
        boxes.addAll(selected);
        int top = Integer.MIN_VALUE;
        bottom = Integer.MAX_VALUE;
        for (int[] b : boxes) {
            top = Math.max(top, b[4]);
            bottom = Math.min(bottom, b[1]);
        }
        layer = top;
        layLanes(player.position());
        total = 0;
        for (Map.Entry<Block, Integer> entry : blocksIn(mc, boxes, true).entrySet()) {
            if (!skipped(entry.getKey())) {
                total += entry.getValue();
            }
        }
        if (total == 0) {
            Chat.warn("Nothing to break in the selected area (" + describe(boxes) + ")" + (skipBlocks.isEmpty() ? "." : ": every block type in it is switched off."));
            return false;
        }
        running = true;
        leftOut.clear();
        layerLeft.clear();
        layerUnloaded.clear();
        layerScannedAt = -1000;
        goal = null;
        startedAt = AutoBuild.clientTicks;
        layerStartedAt = startedAt;
        lastProgressAt = startedAt;
        brokenAtStart = AutoBuild.INSTANCE.breaker.getBrokenCount();
        layerBrokenAtStart = brokenAtStart;
        lastBroken = brokenAtStart;
        itemsAtStart.clear();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                itemsAtStart.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        safety.reset();
        AutoBuild.INSTANCE.breaker.reset();
        BuildLog.open("auto break");
        RunLog.start(mc);
        RunLog.what("Auto break: " + describe(boxes) + ", Y " + bottom + " to " + top + ", about " + total + " blocks");
        BuildLog.line("AREA", "start: " + describe(boxes) + ", Y " + bottom + " to " + top + ", about " + total
                + " blocks | skipped types: " + (Configs.Breaking.AUTO_BREAK_SKIP.getStringValue().isBlank() ? "-" : Configs.Breaking.AUTO_BREAK_SKIP.getStringValue())
                + " | drops " + (Configs.Breaking.PICK_UP_ITEMS.getBooleanValue() ? "put away when the inventory is full" : "ignored")
                + " | player at " + player.blockPosition().toShortString());
        Chat.info("Auto break: " + total + " blocks in " + describe(boxes) + ", from the top (Y " + top + ") down. Stop: J+B or J+Q.");
        return true;
    }

    public void stop(Minecraft mc, @Nullable String why) {
        if (!running) {
            return;
        }
        running = false;
        AutoBuild.INSTANCE.breaker.stop(mc);
        AutoBuild.INSTANCE.mover.goTo(null);
        if (AutoBuild.INSTANCE.fetcher.isBusy()) {
            AutoBuild.INSTANCE.fetcher.reset();
        }
        int broken = AutoBuild.INSTANCE.breaker.getBrokenCount() - brokenAtStart;
        double minutes = Math.max(1, AutoBuild.clientTicks - startedAt) / 20.0 / 60.0;
        BuildLog.line("AREA", String.format(Locale.ROOT, "end: %s | broken %d in %.1f min (%.0f a minute) | left out %d | %s",
                why == null ? "stopped" : why, broken, minutes, broken / minutes, leftOut.size(), AutoBuild.INSTANCE.breaker.summary()));
        Chat.info("Auto break " + (why == null ? "stopped" : why) + ": " + broken + " blocks broken"
                + String.format(Locale.ROOT, " in %.1f min.", minutes));
        RunLog.end(mc, why == null ? "stopped" : why, !"the connection ended".equals(why) && !"left the world".equals(why));
        BuildLog.close();
    }

    /** One tick: safety, drops, breaking what's in reach, flying to the next block of the level, then down a level. */
    public void tick(Minecraft mc) {
        if (!running) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            stop(mc, "left the world");
            return;
        }
        RunLog.tick(mc);
        String unsafe = safety.check(mc);
        if (unsafe != null) {
            stop(mc, "stopped for safety: " + unsafe);
            return;
        }
        ChestFetcher fetcher = AutoBuild.INSTANCE.fetcher;
        BlockBreaker breaker = AutoBuild.INSTANCE.breaker;
        Mover mover = AutoBuild.INSTANCE.mover;
        if (fetcher.isBusy()) {
            fetcher.tick(mc, mover);
            return;
        }
        int broken = breaker.getBrokenCount();
        if (broken != lastBroken) {
            lastBroken = broken;
            lastProgressAt = AutoBuild.clientTicks;
        }
        if (!breaker.isBusy() && putDropsAway(mc, player)) {
            return;
        }
        mover.keepClear(mc);
        if (breaker.isBusy()) {
            holdStill(player);
            breaker.tick(mc, BlockBreaker.Mode.AREA);
            return;
        }
        if (AutoBuild.clientTicks - layerScannedAt >= 20) {
            scanLayer(mc);
        }
        // This level done (as far as it's loaded): down to the next one with something to break
        while (layerLeft.isEmpty() && layerUnloaded.isEmpty()) {
            if (layer <= bottom) {
                stop(mc, "done");
                return;
            }
            int layerBroken = breaker.getBrokenCount() - layerBrokenAtStart;
            if (layerBroken > 0) {
                BuildLog.line("AREA", String.format(Locale.ROOT, "Y %d done: %d broken in %.1fs", layer, layerBroken,
                        (AutoBuild.clientTicks - layerStartedAt) / 20.0));
            }
            layer--;
            layerStartedAt = AutoBuild.clientTicks;
            layerBrokenAtStart = breaker.getBrokenCount();
            // The next level's lanes start where the player is now: back the way it came, no flight across the area
            layLanes(player.position());
            scanLayer(mc);
        }
        // What's in reach first
        breaker.tick(mc, BlockBreaker.Mode.AREA);
        if (breaker.isBusy()) {
            goal = null;
            mover.goTo(null);
            holdStill(player);
            return;
        }
        if (AutoBuild.clientTicks - lastProgressAt > NO_PROGRESS_TICKS) {
            stop(mc, "stopped: nothing could be broken for " + NO_PROGRESS_TICKS / 20 / 60 + " minutes (" + layerLeft.size()
                    + " blocks left on Y " + layer + ", see the build log)");
            return;
        }
        // Then to the level's next block in lane order (the nearest part not loaded yet when nothing loaded is left)
        Vec3 at = player.position();
        BlockPos next = null;
        double first = Double.MAX_VALUE;
        // Broken since the last look (or dropped with the block under it): not flown to (it flew to blocks already gone
        // and waited there up to a second)
        layerLeft.removeIf(pos -> !wants(mc.level, pos));
        boolean loaded = !layerLeft.isEmpty();
        for (BlockPos pos : loaded ? layerLeft : layerUnloaded) {
            if (leftOut.containsKey(pos) || breaker.failedRecently(pos)) {
                continue;
            }
            double key = loaded ? orderOf(pos) : at.distanceToSqr(Vec3.atCenterOf(pos));
            if (key < first) {
                first = key;
                next = pos;
            }
        }
        if (next != null && loaded) {
            currentLane = laneOf(next);
        }
        if (next == null) {
            // Everything left is left out for now: wait for that to pass (or for the no-progress stop)
            mover.goTo(null);
            leftOut.values().removeIf(until -> until < AutoBuild.clientTicks);
            return;
        }
        if (!next.equals(goal)) {
            goal = next;
            goalSince = AutoBuild.clientTicks;
        } else if (AutoBuild.clientTicks - goalSince > GOAL_TICKS) {
            BuildLog.line("AREA", "couldn't get to " + goal.toShortString().replace(" ", "") + " in " + GOAL_TICKS / 20 + "s: left out for a minute");
            leftOut.put(goal, AutoBuild.clientTicks + LEAVE_OUT_TICKS);
            goal = null;
            mover.goTo(null);
            return;
        }
        // Standing on the next lane beside it, else a free spot in the air it can be broken from: right above it may be
        // a cactus (sand under a cactus that drops with it)
        if (goalSpot == null || !next.equals(goalSpotFor)) {
            goalSpotFor = next;
            goalSpot = standBeside(mc, player, next);
            if (goalSpot == null) {
                goalSpot = hoverFor(mc, player, next);
            }
            if (goalSpot == null) {
                BuildLog.line("AREA", "no free spot to break " + next.toShortString().replace(" ", "") + " from: left out for a minute");
                leftOut.put(next, AutoBuild.clientTicks + LEAVE_OUT_TICKS);
                goal = null;
                return;
            }
        }
        mover.goTo(goal, goalSpot);
        mover.tick(mc);
    }

    @Nullable
    private Vec3 goalSpot;
    @Nullable
    private BlockPos goalSpotFor;

    /** Breaking: no drifting on (a block left the reach halfway and was given up for a minute), only rising from harm. */
    private static void holdStill(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        // Not on the hop to a spot to stand at (the breaker moves the player there)
        if (player.getAbilities().flying && !AutoBuild.INSTANCE.breaker.isStanding()) {
            player.setDeltaMovement(motion.x * 0.3, Math.max(0, motion.y) * 0.5, motion.z * 0.3);
        }
    }

    /**
     * Where to fly to break this block: a spot the player fits in, 1 to 4 blocks above it and up to 3 to the side,
     * with nothing that hurts near the body and the block within reach from the eyes there (with a little room for
     * drifting: 0.8 left only spots over cactus rows for sand under a cactus, all refused). The nearest to the player,
     * low ones first. Null when there's none.
     */
    @Nullable
    private static Vec3 hoverFor(Minecraft mc, LocalPlayer player, BlockPos pos) {
        double reach = player.blockInteractionRange() - 0.4;
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = 1; dy <= 4; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos feet = pos.offset(dx, dy, dz);
                    Vec3 spot = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (spot.add(0, player.getEyeHeight(), 0).distanceToSqr(center) > reach * reach || !FlyPath.fits(mc.level, feet)
                            || Mover.harmNear(mc.level, spot) != null) {
                        continue;
                    }
                    // Low over the level first: from there the most blocks are in reach (from 3 above, those 3 to the side)
                    double distance = spot.distanceToSqr(player.position()) + dy * dy * 4;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = spot;
                    }
                }
            }
        }
        return best;
    }

    /** The current level's blocks to break where it's loaded, and one spot per part of it not loaded yet. */
    private void scanLayer(Minecraft mc) {
        layerScannedAt = AutoBuild.clientTicks;
        layerLeft.clear();
        layerUnloaded.clear();
        leftOut.values().removeIf(until -> until < AutoBuild.clientTicks);
        Set<Long> unloadedChunks = new HashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            if (layer < b[1] || layer > b[4]) {
                continue;
            }
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    pos.set(x, layer, z);
                    if (!mc.level.isLoaded(pos)) {
                        if (unloadedChunks.add(((long) (z >> 4) << 32) | ((x >> 4) & 0xFFFFFFFFL))) {
                            layerUnloaded.add(pos.immutable());
                        }
                        continue;
                    }
                    if (wants(mc.level, pos)) {
                        layerLeft.add(pos.immutable());
                    }
                }
            }
        }
    }

    /**
     * With "Auto break: put drops away", a full inventory empties its drops (more of an item than at the start, never
     * tools) into the vaults or linked chests. Without storage it says so (once in 5 minutes) and breaks on.
     */
    private boolean putDropsAway(Minecraft mc, LocalPlayer player) {
        if (!Configs.Breaking.PICK_UP_ITEMS.getBooleanValue() || InventoryCleaner.freeSlots(player.getInventory()) >= FREE_SLOTS_MIN) {
            return false;
        }
        Inventory inventory = player.getInventory();
        Set<Item> drops = new HashSet<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !stack.isDamageableItem() && InventoryCleaner.plain(stack)
                    && inventory.countItem(stack.getItem()) > itemsAtStart.getOrDefault(stack.getItem(), 0)) {
                drops.add(stack.getItem());
            }
        }
        if (!drops.isEmpty() && ChestFetcher.hasStorage(mc) && AutoBuild.INSTANCE.fetcher.startStore(mc, drops)) {
            BuildLog.line("AREA", "inventory full: putting the drops away " + drops.stream().map(i -> new ItemStack(i).getHoverName().getString()).toList());
            // Those are put away: the next ones count from what stays
            drops.forEach(item -> itemsAtStart.put(item, 0));
            return true;
        }
        if (AutoBuild.clientTicks - fullWarnedAt > 20 * 60 * 5) {
            fullWarnedAt = AutoBuild.clientTicks;
            Chat.warn("Auto break: the inventory is full and there's nowhere to put the drops. Link a chest (J+B) or set your"
                    + " vaults, or switch off 'Auto break: put drops away' to ignore them; breaking goes on.");
        }
        return false;
    }

    /** For the HUD and the menu. */
    public String status() {
        int broken = AutoBuild.INSTANCE.breaker.getBrokenCount() - brokenAtStart;
        return "Auto break: Y " + layer + ", lane " + (currentLane + 1) + " of " + laneCount + " (" + layerLeft.size() + " left on the level), "
                + broken + " of about " + total + " broken";
    }
}
