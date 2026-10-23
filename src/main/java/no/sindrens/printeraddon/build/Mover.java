package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.MoveMode;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes the player to the unfinished blocks of the current layer. Stands still while the printer and the
 * breaker have work within reach, then walks (or flies, when walking can't get there) to the nearest
 * unfinished block.
 */
public class Mover {
    private static final int SCAN_INTERVAL = 10;
    /** Blocks that couldn't be done are skipped for this long before trying again. */
    private static final int SKIP_TICKS = 20 * 30;
    private static final int MAX_PATH_NODES = 6000;
    private static final int STUCK_TICKS = 40;
    private static final double JUMP_VELOCITY = 0.42;

    private enum State { IDLE, WORKING, WALKING, FLYING }

    private State state = State.IDLE;
    private long tick;
    private int ticksUntilScan;

    /** Unfinished positions the player can do something about (items available, not skipped). */
    private final List<BlockPos> work = new ArrayList<>();
    /** Parts of the layer too far away to be loaded yet: flown to when nothing else is left. */
    private final List<BlockPos> unloaded = new ArrayList<>();
    /** Unfinished positions the player must not stand in. */
    private final Set<BlockPos> reserved = new HashSet<>();
    private final Map<BlockPos, Long> skipped = new HashMap<>();

    @Nullable
    private BlockPos target;
    @Nullable
    private List<BlockPos> path;
    private int pathIndex;
    @Nullable
    private Vec3 flyTarget;

    private int workTicksWithoutProgress;
    private int lastInReach = -1;
    private int stuckTicks;
    private double lastDistance = Double.MAX_VALUE;
    private int replans;

    private boolean flyCommandSent;
    private int flyCommandWait;
    private boolean flyUnavailable;

    /** What the mover is doing, in words, for the status line. */
    public String describe() {
        return switch (state) {
            case WALKING -> target != null ? "walking to " + target.toShortString() : "walking";
            case FLYING -> target != null ? "flying to " + target.toShortString() : "flying";
            case WORKING -> "placing within reach";
            case IDLE -> work.isEmpty() ? "waiting" : "looking for the next block";
        };
    }

    /** True for a block the printer was given time for in reach, but nothing happened. */
    public boolean wasSkipped(BlockPos pos) {
        return skipped.containsKey(pos);
    }

    /** What the player is doing now, for the build stats. */
    public BuildStats.Activity activity() {
        return switch (state) {
            case WORKING -> BuildStats.Activity.PLACING;
            case FLYING -> BuildStats.Activity.FLYING;
            case WALKING -> BuildStats.Activity.WALKING;
            case IDLE -> work.isEmpty() ? BuildStats.Activity.IDLE : BuildStats.Activity.WAITING;
        };
    }

    public void reset() {
        state = State.IDLE;
        work.clear();
        reserved.clear();
        skipped.clear();
        target = null;
        path = null;
        flyTarget = null;
        ticksUntilScan = 0;
        flyCommandSent = false;
        flyUnavailable = false;
        workTicksWithoutProgress = 0;
        lastInReach = -1;
    }

    /** Stops moving but stays in the air if flying, so the player doesn't fall. */
    public void stop(Minecraft mc) {
        if (mc.player != null && state != State.IDLE && state != State.WORKING) {
            halt(mc.player);
        }
        state = State.IDLE;
        path = null;
        flyTarget = null;
        target = null;
    }

    public void tick(Minecraft mc) {
        tick++;
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        MoveMode mode = Configs.Movement.mode();

        if (player == null || mc.level == null || schematic == null || mode == MoveMode.OFF) {
            return;
        }

        skipped.values().removeIf(until -> until < tick);

        if (--ticksUntilScan <= 0) {
            ticksUntilScan = SCAN_INTERVAL;
            scan(mc, schematic);
        }

        double reach = reach(player);
        BlockPos feet = player.blockPosition();

        // Standing where a block still has to go: step aside first
        if (reserved.contains(feet) || reserved.contains(feet.above())) {
            if (state != State.WALKING && state != State.FLYING) {
                PrinterAddon.debug("Standing in an unfinished spot {}, moving away", feet.toShortString());
                plan(mc, player, feet, mode, reach);
            }
            move(mc, player, mode);
            return;
        }

        // Only count blocks that can be done right now (the printer can place it, or it's for the breaker/bucket)
        List<BlockPos> inReach = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        for (BlockPos pos : work) {
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) <= (reach - 0.25) * (reach - 0.25) && canDoNow(mc, schematic, pos)) {
                inReach.add(pos);
            }
        }

        // On the way to a chosen spot, keep going (the printer places what's in reach meanwhile)
        if (state == State.FLYING && flyTarget != null && mode.canFly()) {
            move(mc, player, mode);
            return;
        }

        if (!inReach.isEmpty()) {
            if (state == State.WALKING || state == State.FLYING) {
                halt(player);
            }
            state = State.WORKING;
            path = null;
            flyTarget = null;
            waitForWork(player, inReach);
            return;
        }

        if (work.isEmpty() && !unloaded.isEmpty()) {
            // The rest of the layer is too far away to be loaded: go there so it loads
            BlockPos far = nearest(player, unloaded);
            if (target == null || !target.equals(far) || (state != State.WALKING && state != State.FLYING)) {
                target = far;
                PrinterAddon.debug("Going to {}, that part isn't loaded yet", far.toShortString());
                plan(mc, player, far, mode, reach);
            }
            move(mc, player, mode);
            return;
        }
        if (work.isEmpty()) {
            if (state == State.WALKING || state == State.FLYING) {
                halt(player);
            }
            state = State.IDLE;
            return;
        }

        // Flying: go where the most blocks can be placed at once; walking: to the nearest one
        BlockPos nearest = mode.canFly() && (!mode.canWalk() || flying(player))
                ? bestSpot(mc, player, schematic, reach) : nearestDoable(mc, player, schematic);
        if (nearest == null) {
            // Nothing can be done right now (waiting for items, or for other blocks first)
            if (state == State.WALKING || state == State.FLYING) {
                halt(player);
            }
            state = State.IDLE;
            return;
        }
        if (target == null || !target.equals(nearest) || (state != State.WALKING && state != State.FLYING)) {
            target = nearest;
            replans = 0;
            plan(mc, player, nearest, mode, reach);
        }

        move(mc, player, mode);
    }

    /** How many of the nearest unfinished blocks are asked "can this be done now?" when choosing where to go. */
    private static final int CANDIDATES_TO_CHECK = 40;
    private final me.aleksilassila.litematica.printer.guides.Guides printerGuides = new me.aleksilassila.litematica.printer.guides.Guides();
    private final Map<BlockPos, Boolean> doableCache = new HashMap<>();
    private long doableCacheTick;

    /**
     * True if something can be done at this spot right now: the printer can place it (asked through its own
     * placement guides), or it's for the breaker or a bucket. A hopper facing north, for example, can only be
     * placed once the block north of it is there.
     */
    private boolean canDoNow(Minecraft mc, WorldSchematic schematic, BlockPos pos) {
        if (tick - doableCacheTick > 5) {
            doableCache.clear();
            doableCacheTick = tick;
        }
        return doableCache.computeIfAbsent(pos.immutable(), p -> {
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, p);
            if (result.needsBreaking() || result == BlockCheck.Result.PROTECTED) {
                return true;
            }
            if (result == BlockCheck.Result.BUCKET) {
                return Fluids.canDo(mc, schematic, p, schematic.getBlockState(p));
            }
            if (result != BlockCheck.Result.MISSING && result != BlockCheck.Result.PRINTER_FIXES) {
                return false;
            }
            me.aleksilassila.litematica.printer.SchematicBlockState state =
                    new me.aleksilassila.litematica.printer.SchematicBlockState(mc.level, schematic, p);
            for (me.aleksilassila.litematica.printer.guides.Guide guide : printerGuides.getInteractionGuides(state)) {
                if (guide.canExecute(mc.player)) {
                    return true;
                }
                if (guide.skipOtherGuides()) {
                    break;
                }
            }
            // Rails the printer won't place but the add-on can (they get the right shape)
            if (result == BlockCheck.Result.MISSING && AutoBuild.INSTANCE.rails.selfPlaceFacing(mc.level, schematic, p, schematic.getBlockState(p)) != null) {
                return true;
            }
            // Blocks with nothing to place them against: the add-on clicks the right face itself
            if (result == BlockCheck.Result.MISSING && QuickPlace.isCandidate(mc, p, schematic.getBlockState(p))) {
                return true;
            }
            // Air place does floating blocks and chains the printer can't do the normal way
            return Configs.Layers.USE_AIR_PLACE.getBooleanValue() && result == BlockCheck.Result.MISSING
                    && AirPlace.isCandidate(mc.level, p, schematic.getBlockState(p));
        });
    }

    /** The nearest unfinished block that can be done right now, checking the closest ones first. */
    @Nullable
    private BlockPos nearestDoable(Minecraft mc, LocalPlayer player, WorldSchematic schematic) {
        Vec3 from = player.position();
        List<BlockPos> sorted = new ArrayList<>(work);
        sorted.sort(java.util.Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));
        int checked = 0;
        for (BlockPos pos : sorted) {
            if (checked++ >= CANDIDATES_TO_CHECK) {
                break;
            }
            if (canDoNow(mc, schematic, pos)) {
                return pos;
            }
        }
        // None of the nearest: fall back to the nearest one at all, so building moves on
        return sorted.isEmpty() ? null : sorted.getFirst();
    }

    /** How many unfinished blocks near the player are looked at when choosing where to fly. */
    private static final int NEARBY_FOR_SPOT = 400;

    /**
     * The block to fly to: among the nearest blocks that can be done now, the one whose hover spot has the most
     * unfinished blocks within reach, a little less for spots further away. Fewer, better placed stops.
     */
    @Nullable
    private BlockPos bestSpot(Minecraft mc, LocalPlayer player, WorldSchematic schematic, double reach) {
        Vec3 from = player.position();
        List<BlockPos> sorted = new ArrayList<>(work);
        sorted.sort(java.util.Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));
        List<BlockPos> near = sorted.subList(0, Math.min(NEARBY_FOR_SPOT, sorted.size()));
        // Sweep the layer from north to south: only spots near the most northern unfinished row, so no holes are left
        int northRow = Integer.MAX_VALUE;
        for (BlockPos pos : work) {
            northRow = Math.min(northRow, pos.getZ());
        }
        int bandEnd = northRow + 3;
        double r = reach - 0.25;
        double eyeHeight = player.getEyeHeight();

        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        int candidates = 0;
        List<BlockPos> band = new ArrayList<>();
        for (BlockPos pos : sorted) {
            if (pos.getZ() <= bandEnd) {
                band.add(pos);
            }
        }
        if (band.isEmpty()) {
            band = sorted;
        }
        near = band.subList(0, Math.min(NEARBY_FOR_SPOT, band.size()));
        for (int i = 0; i < near.size() && i < CANDIDATES_TO_CHECK * 2 && candidates < CANDIDATES_TO_CHECK; i++) {
            BlockPos candidate = near.get(i);
            if (candidate.getZ() > bandEnd || !canDoNow(mc, schematic, candidate)) {
                continue;
            }
            candidates++;
            Vec3 eye = hoverPoint(mc, candidate).add(0, eyeHeight, 0);
            int inReach = 0;
            for (BlockPos pos : near) {
                if (eye.distanceToSqr(Vec3.atCenterOf(pos)) <= r * r) {
                    inReach++;
                }
            }
            double score = inReach - 0.25 * from.distanceTo(eye);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best != null ? best : (sorted.isEmpty() ? null : sorted.getFirst());
    }

    /** Waits while the printer and breaker work; skips the blocks in reach if nothing happens for a while. */
    private void waitForWork(LocalPlayer player, List<BlockPos> inReach) {
        boolean busy = AutoBuild.INSTANCE.breaker.isBusy() || !PrinterBridge.isIdle();
        if (busy || inReach.size() < lastInReach) {
            workTicksWithoutProgress = 0;
        } else {
            workTicksWithoutProgress++;
        }
        lastInReach = inReach.size();

        if (flying(player)) {
            player.setDeltaMovement(Vec3.ZERO);
        }

        if (workTicksWithoutProgress > Configs.Movement.WORK_WAIT_SECONDS.getIntegerValue() * 20) {
            PrinterAddon.debug("Nothing happened to {} blocks in reach, skipping them for now", inReach.size());
            for (BlockPos pos : inReach) {
                skipped.put(pos, tick + SKIP_TICKS);
            }
            work.removeAll(inReach);
            workTicksWithoutProgress = 0;
            lastInReach = -1;
        }
    }

    @Nullable
    private BlockPos lastGoal;
    private int samePlans;

    private void plan(Minecraft mc, LocalPlayer player, BlockPos goalBlock, MoveMode mode, double reach) {
        path = null;
        flyTarget = null;
        stuckTicks = 0;
        lastDistance = Double.MAX_VALUE;

        // Got there over and over and still can't do it from there: leave it for later instead of looping
        if (goalBlock.equals(lastGoal)) {
            if (++samePlans > 4) {
                PrinterAddon.debug("Can't do {} from where it can stand, skipping it for now", goalBlock.toShortString());
                skipped.put(goalBlock, tick + SKIP_TICKS);
                work.remove(goalBlock);
                state = State.IDLE;
                samePlans = 0;
                lastGoal = null;
                return;
            }
        } else {
            lastGoal = goalBlock.immutable();
            samePlans = 0;
        }

        if (mode.canWalk() && (player.onGround() || player.isInWater()) && !flying(player)) {
            List<BlockPos> route = findWalk(mc, player.blockPosition(), goalBlock, reach);
            if (route != null) {
                path = route;
                pathIndex = 0;
                state = State.WALKING;
                PrinterAddon.debug("Walking to {} ({} steps)", goalBlock.toShortString(), route.size());
                return;
            }
        } else if (mode.canWalk() && flying(player)) {
            // Prefer walking: land if there's ground close below and a route from there
            BlockPos landing = landingSpot(mc, player.blockPosition());
            if (landing != null && findWalk(mc, landing, goalBlock, reach) != null) {
                PrinterAddon.debug("Landing at {} to walk", landing.toShortString());
                setFlying(player, false);
                state = State.IDLE;
                return;
            }
        }

        if (mode.canFly() && !flyUnavailable) {
            flyTarget = hoverPoint(mc, goalBlock);
            state = State.FLYING;
            PrinterAddon.debug("Flying to {} for {}", flyTarget, goalBlock.toShortString());
            return;
        }

        PrinterAddon.debug("Can't get to {}, skipping it for now", goalBlock.toShortString());
        skipped.put(goalBlock, tick + SKIP_TICKS);
        work.remove(goalBlock);
        state = State.IDLE;
    }

    @Nullable
    private List<BlockPos> findWalk(Minecraft mc, BlockPos from, BlockPos goalBlock, double reach) {
        Vec3 center = Vec3.atCenterOf(goalBlock);
        double goalReach = reach - 0.8;
        double eyeHeight = mc.player.getEyeHeight();

        PathFinder.Goal goal = new PathFinder.Goal() {
            @Override
            public boolean reached(BlockPos feet) {
                Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + eyeHeight, feet.getZ() + 0.5);
                double d = eye.distanceToSqr(center);
                // Not too close either: the printer skips blocks right next to the player
                return d <= goalReach * goalReach && d >= 2.25
                        && !reserved.contains(feet) && !reserved.contains(feet.above());
            }

            @Override
            public double estimate(BlockPos feet) {
                double dx = feet.getX() + 0.5 - center.x;
                double dz = feet.getZ() + 0.5 - center.z;
                return Math.max(0, Math.sqrt(dx * dx + dz * dz) - goalReach);
            }
        };

        return PathFinder.find(mc.level, from, goal, reserved::contains,
                Configs.Movement.MAX_WALK_DISTANCE.getIntegerValue(), MAX_PATH_NODES);
    }

    @Nullable
    private BlockPos landingSpot(Minecraft mc, BlockPos feet) {
        for (int drop = 0; drop <= 3; drop++) {
            BlockPos pos = feet.below(drop);
            if (PathFinder.canStand(mc.level, pos, reserved::contains)) {
                return pos;
            }
            if (!PathFinder.isPassable(mc.level, pos)) {
                return null;
            }
        }
        return null;
    }

    /** A free spot above the block, high enough to reach it and not in the way of the layer. */
    /**
     * Where to hover for a block: right above the layers being built (so nothing is ever over the player's head
     * and the reach goes furthest sideways), or above the block itself if it's lower (a block placed later).
     */
    private Vec3 hoverPoint(Minecraft mc, BlockPos goalBlock) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        int baseY = layers.isActive() && goalBlock.getY() >= layers.getLayer() && goalBlock.getY() <= layers.getLayerTop()
                ? layers.getLayerTop() : goalBlock.getY();
        // Not right above a block that still has to go in: the printer won't place under the player's feet.
        // Take the nearest column 2 blocks to the side whose top layer block is already done (or empty).
        int column = 0;
        int[][] offsets = {{0, 0}, {0, -2}, {-2, 0}, {2, 0}, {0, 2}, {-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
        if (reserved.contains(new BlockPos(goalBlock.getX(), baseY, goalBlock.getZ()))
                || reserved.contains(new BlockPos(goalBlock.getX(), goalBlock.getY(), goalBlock.getZ()))) {
            double best = Double.MAX_VALUE;
            for (int i = 1; i < offsets.length; i++) {
                BlockPos below = new BlockPos(goalBlock.getX() + offsets[i][0], baseY, goalBlock.getZ() + offsets[i][1]);
                if (reserved.contains(below) || reserved.contains(below.below(baseY - goalBlock.getY()))) {
                    continue;
                }
                double d = mc.player.position().distanceToSqr(Vec3.atCenterOf(below));
                if (d < best) {
                    best = d;
                    column = i;
                }
            }
        }
        BlockPos pos = new BlockPos(goalBlock.getX() + offsets[column][0], baseY + Configs.Movement.HOVER_HEIGHT.getIntegerValue(),
                goalBlock.getZ() + offsets[column][1]);
        for (int i = 0; i < 4; i++) {
            if (PathFinder.isPassable(mc.level, pos) && PathFinder.isPassable(mc.level, pos.above())
                    && !reserved.contains(pos) && !reserved.contains(pos.above())) {
                break;
            }
            pos = pos.above();
        }
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private void move(Minecraft mc, LocalPlayer player, MoveMode mode) {
        switch (state) {
            case WALKING -> walk(mc, player, mode);
            case FLYING -> fly(mc, player);
            default -> {
            }
        }
    }

    private void walk(Minecraft mc, LocalPlayer player, MoveMode mode) {
        if (path == null || pathIndex >= path.size()) {
            halt(player);
            state = State.IDLE;
            return;
        }

        BlockPos node = path.get(pathIndex);
        Vec3 pos = player.position();
        double dx = node.getX() + 0.5 - pos.x;
        double dz = node.getZ() + 0.5 - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal < 0.3 && Math.abs(pos.y - node.getY()) < 0.6) {
            pathIndex++;
            stuckTicks = 0;
            lastDistance = Double.MAX_VALUE;
            if (pathIndex >= path.size()) {
                halt(player);
                state = State.IDLE;
            }
            return;
        }

        if (horizontal < lastDistance - 0.01) {
            lastDistance = horizontal;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            PrinterAddon.debug("Stuck walking at {}, planning again", player.blockPosition().toShortString());
            if (++replans > 3 || target == null) {
                if (target != null) {
                    skipped.put(target, tick + SKIP_TICKS);
                    work.remove(target);
                }
                halt(player);
                state = State.IDLE;
                return;
            }
            plan(mc, player, target, mode, reach(player));
            return;
        }

        double speed = Math.min(Configs.Speed.walkSpeed(), horizontal);
        Vec3 motion = player.getDeltaMovement();
        double vy = motion.y;

        boolean needsJump = node.getY() > pos.y + 0.4 || (player.horizontalCollision && node.getY() >= Math.floor(pos.y));
        if (needsJump && player.onGround()) {
            vy = JUMP_VELOCITY;
        } else if (player.isInWater() && node.getY() >= pos.y) {
            vy = 0.06;
        }

        player.setDeltaMovement(dx / horizontal * speed, vy, dz / horizontal * speed);
    }

    private void fly(Minecraft mc, LocalPlayer player) {
        if (flyTarget == null) {
            state = State.IDLE;
            return;
        }
        if (!ensureFlying(player)) {
            return;
        }

        Vec3 to = flyTarget.subtract(player.position());
        double distance = to.length();

        if (distance < 0.2) {
            player.setDeltaMovement(Vec3.ZERO);
            state = State.IDLE;
            return;
        }

        if (distance < lastDistance - 0.01) {
            lastDistance = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            // Something in the way: go a bit higher
            PrinterAddon.debug("Stuck flying at {}, going up", player.blockPosition().toShortString());
            flyTarget = flyTarget.add(0, 1, 0);
            stuckTicks = 0;
            lastDistance = Double.MAX_VALUE;
            if (++replans > 4 && target != null) {
                skipped.put(target, tick + SKIP_TICKS);
                work.remove(target);
                player.setDeltaMovement(Vec3.ZERO);
                state = State.IDLE;
            }
            return;
        }

        double speed = Math.min(Configs.Speed.flySpeed(), distance);
        Vec3 motion = to.normalize().scale(speed);
        if (player.horizontalCollision) {
            motion = new Vec3(motion.x, Math.max(motion.y, 0.3), motion.z);
        }
        player.setDeltaMovement(motion);
    }

    /** Turns flying on (sending the fly command once if needed). Returns false while waiting for it. */
    private boolean ensureFlying(LocalPlayer player) {
        Abilities abilities = player.getAbilities();
        if (abilities.flying) {
            return true;
        }
        if (abilities.mayfly) {
            setFlying(player, true);
            return true;
        }

        String command = Configs.Movement.FLY_COMMAND.getStringValue().trim();
        if (!flyCommandSent && !command.isEmpty()) {
            flyCommandSent = true;
            flyCommandWait = 40;
            player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
            return false;
        }
        if (flyCommandWait > 0) {
            flyCommandWait--;
            return false;
        }

        flyUnavailable = true;
        state = State.IDLE;
        Chat.warn("Can't fly here" + (command.isEmpty() ? "" : " (/" + command + " didn't allow flying)")
                + ". Walking only from now on.");
        return false;
    }

    private static void setFlying(LocalPlayer player, boolean on) {
        player.getAbilities().flying = on;
        player.onUpdateAbilities();
    }

    private static boolean flying(LocalPlayer player) {
        return player.getAbilities().flying;
    }

    private static void halt(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0, flying(player) ? 0 : motion.y, 0);
    }

    private static double reach(LocalPlayer player) {
        double printerRange = me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
        return Math.min(printerRange, player.blockInteractionRange());
    }

    private static BlockPos nearest(LocalPlayer player, List<BlockPos> positions) {
        Vec3 pos = player.position();
        BlockPos best = positions.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : positions) {
            double d = pos.distanceToSqr(Vec3.atCenterOf(candidate));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    /** Finds the unfinished positions in the current layer range. */
    private void scan(Minecraft mc, WorldSchematic schematic) {
        work.clear();
        reserved.clear();
        unloaded.clear();

        LocalPlayer player = mc.player;
        boolean creative = player.getAbilities().instabuild;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.UNLOADED) {
                            if (unloaded.size() < 512) {
                                unloaded.add(pos.immutable());
                            }
                            continue;
                        }
                        if (result == BlockCheck.Result.MISSING && mc.level.getBlockState(pos).isAir()) {
                            AutoBuild.INSTANCE.markEmpty(pos);
                        }
                        if (result == BlockCheck.Result.OK || result == BlockCheck.Result.EXTRA
                                || result == BlockCheck.Result.UNLOADED) {
                            continue;
                        }

                        BlockPos immutable = pos.immutable();
                        BlockState target = schematic.getBlockState(pos);
                        if (!target.isAir()) {
                            reserved.add(immutable);
                        }

                        if (AutoBuild.INSTANCE.rails.isTemp(immutable)) {
                            continue;
                        }
                        boolean protectedHere = result == BlockCheck.Result.PROTECTED
                                && !AutoBuild.INSTANCE.placedDuringBuild(immutable);
                        if (protectedHere || skipped.containsKey(immutable)) {
                            continue;
                        }
                        if (result == BlockCheck.Result.MISSING && !creative && !hasItemFor(player, target)) {
                            continue;
                        }
                        if (result == BlockCheck.Result.BUCKET && !Fluids.canDo(mc, schematic, immutable, target)) {
                            continue;
                        }
                        if (result == BlockCheck.Result.MISSING && !PlacementOrder.isReady(mc.level, schematic, immutable, target)) {
                            continue;
                        }
                        work.add(immutable);
                    }
                }
            }
        }

        addReadyDeferred(mc, schematic, creative);
    }

    /** Deferred blocks (from finished layers) that can be placed now. */
    private void addReadyDeferred(Minecraft mc, WorldSchematic schematic, boolean creative) {
        for (BlockPos pos : AutoBuild.INSTANCE.deferred) {
            if (skipped.containsKey(pos) || work.contains(pos)) {
                continue;
            }
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            if (result != BlockCheck.Result.MISSING) {
                continue;
            }
            BlockState target = schematic.getBlockState(pos);
            reserved.add(pos);
            if ((creative || hasItemFor(mc.player, target)) && PlacementOrder.isReady(mc.level, schematic, pos, target)) {
                work.add(pos);
            }
        }
    }

    private static boolean hasItemFor(LocalPlayer player, BlockState state) {
        ItemStack needed = StateRules.itemFor(state);
        return !needed.isEmpty() && player.getInventory().contains(stack -> stack.is(needed.getItem()));
    }
}
