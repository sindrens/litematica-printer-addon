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
    private static final int SCAN_INTERVAL = 20;
    /** Blocks that couldn't be done are skipped for this long before trying again. */
    private static final int SKIP_TICKS = 20 * 30;
    private static final int MAX_PATH_NODES = 6000;
    private static final int STUCK_TICKS = 40;
    private static final double JUMP_VELOCITY = 0.42;

    private enum State { IDLE, WORKING, WALKING, FLYING }

    private State state = State.IDLE;
    /** When and where the current flight started (for the build log), -1 when not flying. */
    private long moveStartTick = -1;
    private Vec3 moveFrom = Vec3.ZERO;
    private long tick;
    private int ticksUntilScan;

    /** Unfinished positions the player can do something about (items available, not skipped). */
    private final List<BlockPos> work = new ArrayList<>();
    /** Parts of the layer too far away to be loaded yet: flown to when nothing else is left. */
    private final List<BlockPos> unloaded = new ArrayList<>();
    /** Areas (see areaKey) with blocks that aren't loaded yet, with one such block each. */
    private final Map<Long, BlockPos> unloadedAreas = new HashMap<>();
    /** Unfinished blocks left out of the work because their item isn't in the inventory (last scan). */
    private int waitingForItems;
    private long lastNothingDoable = -100;
    /** Snake position of the last area worked in (Long.MIN_VALUE at the start of a layer). */
    private long lastAreaOrder = Long.MIN_VALUE;
    /** Unloaded areas that stayed unloaded with the player right there (never flown to again). */
    private final java.util.Set<Long> unloadable = new java.util.HashSet<>();
    private long nearUnloadedSince = -1;
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
    /** The way to flyTarget around whatever is in the way (see FlyPath), and how far along it the player is. */
    @Nullable
    private List<Vec3> flyPath;
    @Nullable
    private Vec3 flyPathFor;
    private int flyIndex;
    /** The path is a straight line towards a part that isn't loaded yet (worked out again once it is). */
    private boolean flyPathBlind;
    /** The path is only the first part of a long way (the next part is worked out at its end). */
    private boolean flyLeg;
    /**
     * The path is flown point by point as it is, no shortcuts: a way that keeps to a straight line, straight up over
     * what's in the way and straight down after it (a shortcut would be the long ramp it's there to avoid).
     */
    private boolean flyExact;

    private int workTicksWithoutProgress;
    private int lastInReach = -1;
    private int stuckTicks;
    private double lastDistance = Double.MAX_VALUE;
    private int replans;
    /** Where and when the player was last stuck flying (see fly). */
    @Nullable
    private BlockPos lastStuckAt;
    private long lastStuckTick = -100000;

    private boolean flyCommandSent;
    private int flyCommandWait;
    private boolean flyUnavailable;

    /** What the mover is doing, in words, for the status line. */
    public String describe() {
        return switch (state) {
            case WALKING -> target != null ? "walking to " + target.toShortString() : "walking";
            case FLYING -> sweeping() ? (laneApproach ? "to lane " : "lane ") + (laneIndex + 1) + " of " + lanes.size()
                    + (lastPrinterReach > 0 ? ", placing " + lastPrinterReach + " in reach" : "")
                    : (gliding && lastPrinterReach > 0 ? "gliding, placing " + lastPrinterReach + " on the way to " : "flying to ")
                    + (target != null ? target.toShortString() : "the next spot");
            case WORKING -> "placing " + lastPrinterReach + " block" + (lastPrinterReach == 1 ? "" : "s") + " within reach";
            case IDLE -> work.isEmpty() ? (skipped.isEmpty() ? "waiting" : "waiting, " + skipped.size() + " left for later")
                    : tick - lastNothingDoable < 40 && !waitReason.isEmpty() ? "waiting: " + waitReason
                    : "looking for the next block";
        };
    }

    /** Why nothing could be placed at the last look: the most common reason among the unfinished blocks. */
    private String waitReason = "";
    private long waitReasonAt = -1000;

    /** For the status line: why the unfinished blocks can't be placed now (at most every 2 s, from up to 600 of them). */
    private void explainWait(Minecraft mc, WorldSchematic schematic) {
        if (tick - waitReasonAt < 40 || work.isEmpty()) {
            return;
        }
        waitReasonAt = tick;
        Map<String, Integer> reasons = new HashMap<>();
        int looked = 0;
        for (BlockPos pos : work) {
            if (looked++ >= 600) {
                break;
            }
            reasons.merge(Diagnose.shortReason(mc, schematic, pos), 1, Integer::sum);
        }
        Map.Entry<String, Integer> top = reasons.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        waitReason = top == null ? "" : top.getKey() + (looked > 1 ? " (" + top.getValue() * 100 / Math.min(looked, work.size()) + "% of the blocks left)" : "");
    }

    /** The main reason nothing can be placed now, or "" while something can. */
    public String waitReason() {
        return tick - lastNothingDoable < 40 ? waitReason : "";
    }

    /** Placeable blocks in the printer's reach at the last tick (the printer's own test, see inPrinterReach). */
    private int lastPrinterReach;

    /** For the build log: how fast the mover flies right now (blocks a second). */
    public double speedPerSecond() {
        return velocity.length() * 20;
    }

    /** For the build log: how many placeable blocks the mover sees in the printer's reach right now. */
    public int printerReachCount() {
        return lastPrinterReach;
    }

    /** A spot to go to before building on (a chest to take items from). */
    @Nullable
    private BlockPos errand;
    private boolean errandReached;

    /** Go within reach of this spot (null to stop). Returns true once it's within reach. */
    public boolean goTo(@Nullable BlockPos pos) {
        return goTo(pos, null);
    }

    /** Where to fly for the errand, when not just above it (Auto break: a free spot beside it, see AreaBreaker). */
    @Nullable
    private Vec3 errandSpot;

    /** As goTo, flying to this point to get within reach (null: just above the spot). */
    public boolean goTo(@Nullable BlockPos pos, @Nullable Vec3 spot) {
        return goTo(pos, spot, false);
    }

    /**
     * The errand walks there when it can (Auto break: breaking from the ground is 5 times faster than from the air),
     * also with movement set to "Fly only": that's for building, breaking always goes on foot where there's ground.
     */
    private boolean errandWalk;

    /**
     * As goTo; {@code walk}: on foot when there's a way on the ground to a spot within reach (landing first when there's
     * ground right below), flying only when there isn't.
     */
    public boolean goTo(@Nullable BlockPos pos, @Nullable Vec3 spot, boolean walk) {
        errandWalk = walk;
        if (pos == null || !pos.equals(errand) || !java.util.Objects.equals(spot, errandSpot)) {
            errand = pos == null ? null : pos.immutable();
            errandSpot = pos == null ? null : spot;
            errandReached = false;
            if (state == State.FLYING || state == State.WALKING) {
                state = State.IDLE;
            }
        }
        return errand != null && errandReached;
    }

    /** The blocks left for later right now. */
    public java.util.Set<BlockPos> skippedNow() {
        return new java.util.HashSet<>(skipped.keySet());
    }

    /**
     * Leaves a block for later (30 s, or sooner when nothing else is left), with the reason and everything known about
     * it in the build log. A block left for later this many times in a layer is left out of the layer (it's tried
     * again at the end of the build, and listed if it still can't be done): the layer never waits on one block.
     */
    private void skip(BlockPos pos, String why) {
        Minecraft mc = Minecraft.getInstance();
        WorldSchematic schematic = SchematicHelper.world();
        String explain = schematic != null && mc.player != null ? " | " + Diagnose.explain(mc, schematic, pos) : "";
        int times = skipCounts.merge(pos.immutable(), 1, Integer::sum);
        if (times >= MAX_SKIPS && schematic != null && BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.UNLOADED) {
            skipCounts.remove(pos);
            AutoBuild.INSTANCE.couldNotPlace.add(pos.immutable());
            BuildLog.line("GIVEUP", pos.toShortString() + " for this layer: left for later " + times + " times (last: " + why
                    + "); the layer goes on without it, it's tried again at the end of the build" + explain);
            return;
        }
        skipped.put(pos.immutable(), tick + SKIP_TICKS);
        BuildLog.line("SKIP", pos.toShortString() + " for 30 s (" + times + " of " + MAX_SKIPS + "): " + why + explain);
        // Built over already (the layer above went on top of it): the block above comes off for it, and back after
        AutoBuild.INSTANCE.unsealIfCovered(mc, pos, why);
    }

    /** How often each block of this layer was left for later. */
    private final Map<BlockPos, Integer> skipCounts = new HashMap<>();
    /**
     * Left for later this often, a block is left out of its layer (done at the end of the build). It was 4: the player
     * would rather have the layer wait a little longer than holes to come back for (each time is from another spot,
     * and dirt for farmland gets the add-on's own hoe click meanwhile, see Tiller).
     */
    private static final int MAX_SKIPS = 8;

    /**
     * Nothing has been placed for a long while although there's work, and it's not buying or fetching (see
     * AutoBuild): whatever the mover is doing doesn't get anywhere (a lane end it flew to every tick for minutes, a
     * spot it kept coming back to). That's left for later, and it plans again from where it is. The second time in a
     * row, the part of the layer it's in is left for two minutes.
     */
    public void watchdog(Minecraft mc, int strike) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        String doing = describe();
        BlockPos stuckAt = spotFor != null ? spotFor : target;
        if (laneEnd != null) {
            laneBlocked(player, "watchdog: nothing placed for 30 s");
        } else if (stuckAt != null && work.contains(stuckAt)) {
            skip(stuckAt, "watchdog: nothing placed for 30 s while " + doing);
            dropWork(stuckAt);
        }
        if (strike >= 2 && stuckAt != null) {
            unreachableAreas.put(areaKey(stuckAt), tick + 2 * 60 * 20);
        }
        BuildLog.line("WATCHDOG", "nothing placed for " + 30 * strike + " s while " + doing + " at " + player.blockPosition().toShortString()
                + (stuckAt != null ? " (for " + stuckAt.toShortString() + ")" : "") + ": planning again"
                + (strike >= 2 && stuckAt != null ? ", that part of the layer left for 2 minutes" : ""));
        flyPath = null;
        flyTarget = null;
        target = null;
        spotFor = null;
        halt(player);
        state = State.IDLE;
        ticksUntilScan = 0;
    }

    /** There's work on the layer: blocks to place now (not only ones waiting for their items). */
    public boolean hasWork() {
        return !work.isEmpty();
    }

    /** Nothing on the current layers to go to (as of the last look): no block to place or break, no part to load. */
    public boolean nothingToDo() {
        return work.isEmpty() && waitingForItems == 0
                && unloaded.stream().allMatch(p -> skipped.containsKey(p) || areaUnreachable(areaKey(p)));
    }

    /** True for a block the printer was given time for in reach, but nothing happened. */
    public boolean wasSkipped(BlockPos pos) {
        return skipped.containsKey(pos);
    }

    /**
     * True while moving faster than the printer can place from (flying on to the next part): it waits then, a block
     * placed from a fast moving player is placed from where the server doesn't have the player yet (wrong facing,
     * wrong spot). Gliding along while placing is slower than that.
     */
    public boolean isTravelling(LocalPlayer player) {
        if (state == State.WALKING) {
            return true;
        }
        // The speed the mover flies at; without the mover (the player moving by hand), the real motion
        Vec3 motion = state == State.FLYING || state == State.WORKING ? velocity : player.getDeltaMovement();
        double safe = sweeping() ? SWEEP_SAFE : SAFE_PLACE_SPEED;
        return motion.x * motion.x + motion.z * motion.z > safe * safe || Math.abs(motion.y) > 0.15;
    }

    /** Fastest the player glides while the printer places (blocks a tick, almost 5 blocks a second), just under SAFE_PLACE_SPEED. */
    private static final double GLIDE_MAX = 0.24;
    /** Slowest glide while there's still something to place on the way. */
    private static final double GLIDE_MIN = 0.04;
    /** With this many placeable blocks in reach the player stops and places them. */
    private static final int STOP_FOR = 10;
    /** Up to this speed the printer keeps placing; faster (flying on to the next part) it waits. */
    private static final double SAFE_PLACE_SPEED = 0.25;
    /** Most the speed changes in a tick (blocks a tick): no sudden starts and stops. */
    private static final double ACCEL = 0.05;
    /** Braking for the end of a flight: speed is kept at what can still be stopped smoothly. */
    private static final double BRAKE = 0.035;
    /** Speed through a turn of the flight path (around something in the way). */
    private static final double TURN_SPEED = 0.2;
    /** This close to the spot with nothing much left in reach: on to the next spot without stopping. */
    private static final double RETARGET_DISTANCE = 1.5;

    /** The speed the mover flies at (each tick changed by at most ACCEL). */
    private Vec3 velocity = Vec3.ZERO;
    /** The last tick the printer placed something (see glideSpeedFor). */
    private long lastPlacementTick;
    private int lastPlacementCount = -1;
    /** Held for blocks to place on the way for at most this long without anything being placed. */
    private static final int HOLD_LIMIT_TICKS = 30;
    /** Held still on purpose with nothing placed for this long (and nothing else going on): it goes on (see fly). */
    private static final int HOLD_GIVE_UP_TICKS = 60;
    private long lastHoldNote = -1000;
    /**
     * The feet this little into the block below don't stand in it: hovering right on top of a layer is at 65.99999
     * now and then instead of 66, or a little lower for a moment after coming down to it.
     */
    private static final double FEET_SLACK = 0.2;
    /** The last tick the printer couldn't place (flying too fast, paused): a stall is only counted after it. */
    private long lastPrinterFree = -1000;
    /** How fast to glide now (from what's in reach, see glideSpeed). */
    private double glideSpeed = GLIDE_MAX;
    private long lastRetarget;
    /** The current flight is to a spot of the area plan (the next one may be taken on the way). */
    private boolean spotFlight;

    /**
     * After a flight with nothing more to do: slows down to a stop. Only while still moving from the flight, so the
     * player can move by hand while auto build waits.
     */
    private void settle(LocalPlayer player) {
        if (velocity.lengthSqr() > 1.0e-6 && flying(player)) {
            steer(player, Vec3.ZERO);
        }
    }

    /** Room kept between the player's body and a cactus or berry bush: sideways, and below the feet. */
    private static final double HARM_SIDE = 0.15;
    private static final double HARM_BELOW = 0.2;
    private long lastHarmNote = -1000;
    /** Rising away from a cactus now (for the run log's count). */
    private boolean rising;

    /**
     * The body about to touch a cactus (or a berry bush, fire...) next tick: up and over it instead. The game hurts
     * the player as soon as the body is in the cactus's block, and the flights ran with the feet right on top of the
     * layer: a cactus grown one block where its sand was still missing was brushed 6 times in a log (Y 96 to 97).
     */
    private void keepClearOfHarm(net.minecraft.world.level.Level level, LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        Vec3 next = player.position().add(motion);
        BlockPos harm = harmNear(level, next);
        if (harm == null) {
            rising = false;
            return;
        }
        if (!rising) {
            rising = true;
            RunLog.harmLift();
        }
        player.setDeltaMovement(motion.x * 0.6, Math.max(motion.y, 0.2), motion.z * 0.6);
        velocity = new Vec3(velocity.x * 0.6, Math.max(velocity.y, 0.2), velocity.z * 0.6);
        if (tick - lastHarmNote > 40) {
            lastHarmNote = tick;
            BuildLog.line("SAFETY", "harm=" + level.getBlockState(harm).getBlock().toString().replace("Block{minecraft:", "").replace("}", "")
                    + " at=" + harm.toShortString() + " feet=" + String.format(java.util.Locale.ROOT, "%.2f", player.getY())
                    + ": rising over it");
        }
    }

    /** A block that hurts (cactus, berry bush, fire...) where the player's body at these feet would touch it, or null. */
    @Nullable
    static BlockPos harmNear(net.minecraft.world.level.Level level, Vec3 feet) {
        double half = 0.3 + HARM_SIDE;
        int minX = (int) Math.floor(feet.x - half);
        int maxX = (int) Math.floor(feet.x + half);
        // (The hover height over a cactus layer is exactly this much over it: not under it by a rounding error)
        int minY = (int) Math.floor(feet.y - HARM_BELOW + 1.0e-6);
        int maxY = (int) Math.floor(feet.y + 1.8);
        int minZ = (int) Math.floor(feet.z - half);
        int maxZ = (int) Math.floor(feet.z + half);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    if (PathFinder.hurts(level.getBlockState(pos))) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    /** Speeds up or slows down towards this motion, at most ACCEL a tick. */
    private void steer(LocalPlayer player, Vec3 wanted) {
        Vec3 change = wanted.subtract(velocity);
        double length = change.length();
        velocity = length > ACCEL ? velocity.add(change.scale(ACCEL / length)) : wanted;
        player.setDeltaMovement(velocity);
    }

    /**
     * How fast to glide with these blocks to place in reach: the fewer, the faster (a lot in reach: stop and place
     * them). Never so fast that a block behind leaves the reach before the printer gets to it.
     */
    /** Waiting for the server's answer to a click on it or next to it (the printer isn't given it meanwhile). */
    private static boolean heldBack(BlockPos pos) {
        if (!ServerAcks.works()) {
            return false;
        }
        // (Not "what it's clicked against was placed a moment ago": waiting for that made every lane of a test farm
        // slower, 699 blocks a minute instead of 769, for the few clicks the server refuses)
        return ServerAcks.isWaiting(pos) || ServerAcks.nextToWaiting(pos);
    }

    private double glideSpeedFor(LocalPlayer player, List<BlockPos> inReach, double reach) {
        // Breaking a block or using the bucket: that block has to stay in reach, hold still
        if (AutoBuild.INSTANCE.breaker.isBusy() || AutoBuild.INSTANCE.fluids.isBusy()) {
            return 0;
        }
        // Only waited for while the printer gets on with it: nothing placed for a while, glide on (what it can't
        // place now is left for the next time around, or skipped as usual)
        boolean placing = tick - lastPlacementTick < HOLD_LIMIT_TICKS;
        // Only what the printer can click now counts: a block clicked a moment ago (or next to one) only waits for the
        // server's answer, and slowing down for those left the printer with nothing to do (a quarter of its turns)
        int n = 0;
        for (BlockPos pos : inReach) {
            if (!heldBack(pos)) {
                n++;
            }
        }
        if (n >= STOP_FOR) {
            return placing ? 0 : GLIDE_MIN;
        }
        double speed = n <= KEEP_MOVING_LEFT ? GLIDE_MAX
                : Math.max(GLIDE_MIN, GLIDE_MAX * (STOP_FOR - n) / (double) (STOP_FOR - KEEP_MOVING_LEFT));
        if (velocity.lengthSqr() > 1.0e-4) {
            Vec3 direction = velocity.normalize();
            Vec3 eye = player.getEyePosition();
            for (BlockPos pos : inReach) {
                Vec3 to = Vec3.atCenterOf(pos).subtract(eye);
                if (placing && to.dot(direction) < 0 && !ServerAcks.isWaiting(pos)) {
                    // Behind: slower the closer it is to the edge of the reach (stopped 0.3 before it)
                    double margin = reach - to.length();
                    speed = Math.min(speed, GLIDE_MAX * Math.max(0, (margin - 0.3) / 0.9));
                }
            }
        }
        // The printer is in the middle of a block (turned, the click still to come): not faster than a glide
        if (!PrinterBridge.isIdle()) {
            speed = Math.min(speed, GLIDE_MAX);
        }
        return speed;
    }

    /**
     * Where the printer's nearest-first order counts from: while gliding a little behind the player, so what's about to
     * leave the reach goes in first (and the glide doesn't have to wait for it).
     */
    public Vec3 orderFrom(LocalPlayer player) {
        if (state == State.FLYING && gliding && velocity.lengthSqr() > 1.0e-4) {
            return player.position().subtract(velocity.normalize().scale(2.5));
        }
        return player.position();
    }

    /**
     * While flying on placing (a lane or a glide): for a block, how many ticks until it leaves the printer's reach at
     * this speed. Null when the player stays put (the usual order then).
     */
    @Nullable
    public java.util.function.ToDoubleFunction<BlockPos> ticksLeftInReach(LocalPlayer player) {
        if (state != State.FLYING || !gliding) {
            return null;
        }
        Vec3 flat = new Vec3(velocity.x, 0, velocity.z);
        double speed = flat.length();
        if (speed < 0.02) {
            return null;
        }
        Vec3 direction = flat.scale(1 / speed);
        Vec3 eye = player.getEyePosition();
        double range = me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
        return pos -> {
            Vec3 d = Vec3.atCenterOf(pos).subtract(eye);
            double along = d.dot(direction);
            double across = d.lengthSqr() - along * along;
            return (along + Math.sqrt(Math.max(0, range * range - across))) / speed;
        };
    }

    /** Mover state for the build log's snapshots. */
    public String details() {
        return state + (target != null ? " target " + target.toShortString() : "") + " area " + areaName(area)
                + " work " + work.size() + (unloadedAreas.isEmpty() ? "" : " unloaded areas " + unloadedAreas.size());
    }

    public BuildStats.Activity activity() {
        return switch (state) {
            case WORKING -> BuildStats.Activity.PLACING;
            // Gliding past blocks the printer places is placing
            case FLYING -> gliding && lastPrinterReach > 0 ? BuildStats.Activity.PLACING : BuildStats.Activity.FLYING;
            case WALKING -> BuildStats.Activity.WALKING;
            case IDLE -> work.isEmpty() ? BuildStats.Activity.IDLE : BuildStats.Activity.WAITING;
        };
    }

    public void reset() {
        state = State.IDLE;
        sweepLayer = Integer.MIN_VALUE;
        // The lanes from scratch: started again on the same layer (after a resume, or another build on the same Y)
        // the old plan said "all lanes flown" and the whole layer went spot by spot
        sweepFor = Integer.MIN_VALUE;
        sweepPlanned = false;
        sweepDone = false;
        laneEnd = null;
        laneStart = null;
        lanes = List.of();
        flownParts.clear();
        tallCache.clear();
        scannedLayer = Integer.MIN_VALUE;
        scannedTop = Integer.MIN_VALUE;
        sweepRetryAfterScan = -1;
        unloadable.clear();
        unreachableAreas.clear();
        skipCounts.clear();
        nearUnloadedSince = -1;
        errand = null;
        work.clear();
        workVersion++;
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
        velocity = Vec3.ZERO;
    }

    /** Stops moving but stays in the air if flying, so the player doesn't fall. */
    public void stop(Minecraft mc) {
        if (mc.player != null && (state != State.IDLE || velocity.lengthSqr() > 1.0e-6)) {
            halt(mc.player);
        }
        RunLog.tripEnd(mc, "stopped");
        velocity = Vec3.ZERO;
        state = State.IDLE;
        path = null;
        flyTarget = null;
        target = null;
    }

    private State loggedState;
    @Nullable
    private BlockPos loggedTarget;

    public void tick(Minecraft mc) {
        tickNow(mc);
        if (mc.player != null && mc.level != null && flying(mc.player)) {
            keepClearOfHarm(mc.level, mc.player);
        }
        // Every change of what the player is doing, in the build log; a new target in the same state only with "Debug
        // log" (STATE FLYING -> FLYING was most of 680 lines an hour)
        if (BuildLog.isOpen() && mc.player != null && (state != loggedState || !java.util.Objects.equals(target, loggedTarget))) {
            if (state != loggedState || no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.getBooleanValue()) {
                BuildLog.line("STATE", (loggedState == null ? "-" : loggedState) + " -> " + state
                        + (target != null ? " | target " + target.toShortString() : "")
                        + " | area " + areaName(area) + " | at " + mc.player.blockPosition().toShortString()
                        + " | " + describe());
            }
            loggedState = state;
            loggedTarget = target;
        }
    }

    private void tickNow(Minecraft mc) {
        tick++;
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        MoveMode mode = Configs.Movement.mode();

        // Without a schematic only an errand (Auto break flies to the blocks of an area that way, see AreaBreaker)
        if (player == null || mc.level == null || mode == MoveMode.OFF || schematic == null && errand == null) {
            return;
        }

        skipped.values().removeIf(until -> until < tick);
        // When the printer last placed something, whichever way this tick goes on below (the waits count from it)
        int placedCount = AutoBuild.INSTANCE.stats.totalPlaced();
        if (placedCount != lastPlacementCount) {
            lastPlacementCount = placedCount;
            lastPlacementTick = tick;
        }

        if (schematic != null && --ticksUntilScan <= 0) {
            ticksUntilScan = SCAN_INTERVAL;
            long started = Perf.start();
            scan(mc, schematic);
            Perf.end("movement: scan", started);
        }

        double reach = reach(player);
        BlockPos feet = BlockPos.containing(player.getX(), player.getY() + FEET_SLACK, player.getZ());

        // An errand (e.g. to a chest) comes before the building work
        if (errand != null) {
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(errand)) <= (reach - 0.7) * (reach - 0.7)) {
                if (state == State.WALKING || state == State.FLYING) {
                    halt(player);
                }
                state = State.IDLE;
                errandReached = true;
                return;
            }
            errandReached = false;
            // At flying speed, not at the glide speed of the lane it left (0 while that waited for a block)
            gliding = false;
            if (state != State.FLYING && state != State.WALKING) {
                target = errand;
                spotFor = null;
                path = null;
                stuckTicks = 0;
                lastDistance = Double.MAX_VALUE;
                if (errandWalk && mode != no.sindrens.printeraddon.config.MoveMode.OFF && walkToErrand(mc, player, reach)) {
                    move(mc, player, mode);
                    return;
                }
                if (ensureFlying(player)) {
                    flyTarget = errandSpot != null ? errandSpot : new Vec3(errand.getX() + 0.5, errand.getY() + 1.2, errand.getZ() + 0.5);
                    state = State.FLYING;
                }
            }
            move(mc, player, mode);
            return;
        }
        if (schematic == null) {
            return;
        }

        // Standing where a block still has to go: step aside first, at flying speed. Not along a lane, which takes the
        // player on from there by itself. A lane once stood still for good over the redstone under the feet (at 65.99999):
        // it went on here with the glide speed it had waited with, 0, never worked out again
        if (!laneFlight() && (reserved.contains(feet) || reserved.contains(feet.above()))) {
            if (state != State.WALKING && state != State.FLYING) {
                PrinterAddon.debug("Standing in an unfinished spot {}, moving away", feet.toShortString());
                spotFor = null;
                plan(mc, player, feet, mode, reach);
            }
            gliding = false;
            move(mc, player, mode);
            return;
        }

        // In reach exactly as the printer sees it (its range from the eye, never within 1 block of the eye or the
        // feet), and only blocks that can be done right now (the printer can place it, or it's for the
        // breaker/bucket). Blocks the printer skips for being that close are kept apart: the player steps aside for them
        List<BlockPos> inReach = new ArrayList<>();
        List<BlockPos> tooClose = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        java.util.Set<BlockPos> printerSees = AutoBuild.INSTANCE.printerReachNow();
        notTaken.values().removeIf(until -> until < tick);
        for (BlockPos pos : work) {
            if (notTaken.containsKey(pos)) {
                continue;
            }
            double dx = pos.getX() + 0.5 - eye.x;
            double dy = pos.getY() + 0.5 - eye.y;
            double dz = pos.getZ() + 0.5 - eye.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d > reach * reach) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(pos);
            if (!canDoNow(mc, schematic, pos)) {
                continue;
            }
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            boolean printerPlaces = result == BlockCheck.Result.MISSING || result == BlockCheck.Result.PRINTER_FIXES;
            if (printerPlaces && printerSees != null && !printerSees.contains(pos) && d > 1) {
                // Not in the printer's own reach from here (it measures a little differently): nothing to wait for
                continue;
            }
            if (printerPlaces && (d <= 1 || player.position().distanceToSqr(center) <= 1)) {
                tooClose.add(pos);
            } else if (result != BlockCheck.Result.BUCKET || d <= bucketRange(mc) * bucketRange(mc)) {
                inReach.add(pos);
            }
        }
        // Never waiting for good on blocks the printer doesn't take: 3 s in reach, nothing placed, nothing else going
        // on (no breaking, no bucket, no lag), and they're left out for 30 s. A lane stood still for minutes on
        // "placing 10 in reach" that the printer called not ready (a test of two layers at once)
        // Only time the printer could have placed counts: not while flying too fast for it, or paused for anything
        // else (a leftover sand block was left out for 30 s each time it was flown to, after 3 s of flying there)
        if (isTravelling(player) || AutoBuild.INSTANCE.shouldPausePrinter()) {
            lastPrinterFree = tick;
        }
        if (!inReach.isEmpty() && tick - lastPlacementTick > 60 && tick - lastNotTakenAt > 60 && tick - lastPrinterFree > 60
                && PrinterBridge.isIdle()
                && !AutoBuild.INSTANCE.breaker.isBusy() && !AutoBuild.INSTANCE.fluids.isBusy() && !ServerLag.shouldWait(mc)) {
            lastNotTakenAt = tick;
            StringBuilder why = new StringBuilder();
            for (BlockPos pos : inReach.subList(0, Math.min(5, inReach.size()))) {
                why.append(" | ").append(Diagnose.explain(mc, schematic, pos));
            }
            BuildLog.line("STALL", inReach.size() + " block(s) in reach, nothing placed for 3 s: left out for 30 s, going on" + why);
            for (BlockPos pos : inReach) {
                notTaken.put(pos.immutable(), tick + 600);
            }
            inReach.clear();
        }
        lastPrinterReach = inReach.size();
        boolean keepMoving = Configs.Movement.KEEP_MOVING.getBooleanValue() && mode.canFly() && flying(player);

        // Full layers: straight lanes over the whole layer, the printer placing all the way (see sweepTick)
        if (mode.canFly() && sweepTick(mc, player, schematic, inReach, reach, mode)) {
            return;
        }

        // On the way to a chosen spot, keep going (the printer places what's in reach meanwhile): gliding while there
        // is something to place in reach, slower the more there is, so the printer gets to all of it on the way; at
        // full speed otherwise
        if (state == State.FLYING && flyTarget != null && mode.canFly()) {
            gliding = (!inReach.isEmpty() || !PrinterBridge.isIdle()) && Configs.Movement.KEEP_MOVING.getBooleanValue();
            glideSpeed = glideSpeedFor(player, inReach, reach);
            // Nearly at the spot with nothing much left in reach: on to the next spot already, without stopping
            if (keepMoving && spotFlight && inReach.size() < STOP_FOR && tick - lastRetarget >= 5
                    && player.position().distanceTo(flyTarget) < RETARGET_DISTANCE) {
                lastRetarget = tick;
                spotExclude = new HashSet<>(inReach);
                BlockPos next = bestSpot(mc, player, schematic, reach);
                spotExclude = Set.of();
                if (next != null && !next.equals(target) && hoverPoint(mc, next).distanceToSqr(flyTarget) > 0.5 * 0.5) {
                    target = next;
                    replans = 0;
                    plan(mc, player, next, mode, reach);
                }
            }
            move(mc, player, mode);
            return;
        }
        gliding = false;

        // All that's left here is right next to the feet, where the printer never places: step aside for it now
        // instead of waiting and leaving it behind
        if (inReach.isEmpty() && !tooClose.isEmpty() && PrinterBridge.isIdle() && !AutoBuild.INSTANCE.breaker.isBusy()) {
            BlockPos under = tooClose.getFirst();
            if (state != State.FLYING && state != State.WALKING) {
                BuildLog.line("MOVE", "only " + under.toShortString() + " is left here and it's too close to the player for the printer: moving aside");
                target = under;
                spotFor = null;
                plan(mc, player, under, mode, reach);
            }
            move(mc, player, mode);
            return;
        }

        if (!inReach.isEmpty() || !PrinterBridge.isIdle()) {
            lastWorkTick = tick;
        }
        // (No waiting here once nothing is left in reach: the old wait counted blocks placed a moment ago as still
        // to do, and stood still half a second after every spot)

        // Keep moving: fewer blocks in reach than it takes to stop for, glide on to the next spot already; the printer
        // places these on the way (it places whatever is in reach while gliding), and the glide is slow enough that
        // none of them is left behind (see glideSpeedFor)
        if (!inReach.isEmpty() && inReach.size() < STOP_FOR && keepMoving && tick - lastEarlyLeave >= 4
                && !AutoBuild.INSTANCE.breaker.isBusy() && !AutoBuild.INSTANCE.fluids.isBusy()) {
            lastEarlyLeave = tick;
            // The next spot for what isn't in reach yet (what is in reach is placed from here or on the way)
            spotExclude = new HashSet<>(inReach);
            BlockPos next = bestSpot(mc, player, schematic, reach);
            spotExclude = Set.of();
            if (next != null && hoverPoint(mc, next).distanceToSqr(player.position()) > 0.5 * 0.5) {
                target = next;
                replans = 0;
                plan(mc, player, next, mode, reach);
                gliding = true;
                glideSpeed = glideSpeedFor(player, inReach, reach);
                move(mc, player, mode);
                return;
            }
        }

        if (!inReach.isEmpty()) {
            if (state == State.WALKING) {
                halt(player);
                RunLog.tripEnd(mc, "arrived");
                if (moveStartTick >= 0) {
                    BuildLog.line("MOVE", String.format(java.util.Locale.ROOT, "arrived at %s after %.1f s, %.1f blocks from %s; %d placeable in reach",
                            player.blockPosition().toShortString(), (tick - moveStartTick) / 20.0,
                            Math.sqrt(player.position().distanceToSqr(moveFrom)), BlockPos.containing(moveFrom).toShortString(),
                            inReach.size()));
                    moveStartTick = -1;
                }
            }
            state = State.WORKING;
            path = null;
            flyTarget = null;
            waitForWork(player, inReach);
            return;
        }

        // Only when nothing here waits for items (they're being bought or fetched: wait for them here)
        if (work.isEmpty() && !unloaded.isEmpty() && waitingForItems == 0) {
            // The rest of the layer is too far away to be loaded: go there so it loads (not to parts no flight could
            // get to a moment ago)
            List<BlockPos> notSkipped = unloaded.stream()
                    .filter(p -> !skipped.containsKey(p) && !areaUnreachable(areaKey(p))).toList();
            if (notSkipped.isEmpty()) {
                halt(player);
                state = State.IDLE;
                retryLeftForLater();
                return;
            }
            BlockPos far = nearest(player, notSkipped);
            if (target == null || !target.equals(far) || (state != State.WALKING && state != State.FLYING)) {
                target = far;
                spotFor = null;
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
            settle(player);
            retryLeftForLater();
            return;
        }

        // Flying: go where the most blocks can be placed at once; walking: to the nearest one
        boolean planned = mode.canFly() && (!mode.canWalk() || flying(player));
        BlockPos nearest = planned ? bestSpot(mc, player, schematic, reach) : nearestDoable(mc, player, schematic);
        if (!planned) {
            spotFor = null;
        }
        if (nearest == null) {
            // Nothing can be done right now (waiting for items, or for other blocks first)
            if (state == State.WALKING || state == State.FLYING) {
                halt(player);
            }
            state = State.IDLE;
            settle(player);
            retryLeftForLater();
            return;
        }
        // The spot chosen is where the player already is, and still nothing is in the printer's reach: the block the
        // spot is for can't be done from here after all. Leave it for a moment, so the next tick picks another spot
        // (standing still here "placing within reach" with nothing in reach was the long stops in the logs)
        if (state != State.WALKING && state != State.FLYING && hoverPoint(mc, nearest).distanceToSqr(player.position()) < 0.3 * 0.3) {
            BlockPos block = spotFor != null ? spotFor : nearest;
            skip(block, String.format(java.util.Locale.ROOT, "the spot planned for it is right where the player is, but it isn't in the printer's reach from here"
                    + " (%.2f from the eye, printer range %.2f)", Math.sqrt(eye.distanceToSqr(Vec3.atCenterOf(block))), reach));
            dropWork(block);
            state = State.IDLE;
            settle(player);
            return;
        }
        if (target == null || !target.equals(nearest) || (state != State.WALKING && state != State.FLYING)) {
            target = nearest;
            replans = 0;
            plan(mc, player, nearest, mode, reach);
        }

        move(mc, player, mode);
    }

    // ---- Lanes ----

    /** The layer the lanes are for; planned; all flown (the rest goes spot by spot). */
    private int sweepFor = Integer.MIN_VALUE;
    private boolean sweepPlanned;
    private boolean sweepDone;
    /** Lanes run east-west (along x), else north-south. */
    private boolean lanesAlongX;
    /** The lanes' cross coordinate (z of an east-west lane), in the order they're flown, and how many blocks each covers to either side. */
    private List<Integer> lanes = List.of();
    private int laneIndex;
    private int laneHalf;
    /** The lowest lane's cross coordinate and the lanes' width (they're evenly spaced from there). */
    private int laneFirst;
    private int laneWidth = 1;
    /** The layer's length along the lanes, and the eye's height over its lowest blocks when hovering (see lineOf). */
    private int laneAlongFrom;
    private int laneAlongTo;
    private double laneEyeAbove;
    /** The row each lane is flown along, by its cross coordinate: its middle, or the row beside it (see lineOf). */
    private final Map<Integer, Integer> laneLines = new HashMap<>();
    /** Each lane's stretches (along it) already flown, by its cross coordinate: what's left there goes spot by spot. */
    private final Map<Integer, List<int[]>> flownParts = new HashMap<>();
    /** A stretch this long with nothing to do splits a lane in two (each part flown on its own). */
    private static final int LANE_GAP = 24;
    /** Something on a lane's line at least this high above the hover height splits the lane there (a wall). */
    private static final int LANE_TALL = 3;
    /** How much further away a lane part counts when something tall stands between it and the player. */
    private static final double OVER_WALL = 30;
    /** The next lane in order is taken when it's at most this much farther than the nearest. */
    private static final double IN_ORDER_SLACK = 12;
    /** A lane part with fewer blocks than this isn't flown as a lane (see nextLane). */
    private static final int MIN_LANE_PART = 20;
    private int lastTinyParts = -1;
    /** Held at a lane's end at most this long while blocks in reach are still to be placed (see sweepTick). */
    private static final int LANE_END_WAIT = 20;
    private int laneEndWaited;
    /** Which way the sweep goes over the lanes (+1: to higher lane numbers). */
    private int sweepDirection = 1;
    /** The lane being flown: its start and end (blocks of the layer); first flying to its start. */
    @Nullable
    private BlockPos laneStart;
    @Nullable
    private BlockPos laneEnd;
    private boolean laneApproach;
    /**
     * Flights along the lane picked up again after a stop without getting closer (see sweepTick): three times in a
     * row, and the lane is left there (it went on for 20 s every tick with a lane end that couldn't be got to).
     */
    private int laneRetries;
    private double laneRetryDistance;
    /** Blocks the printer places a tick while there's something in its reach (learned while flying the lanes). */
    private double sweepRate = 0.6;
    private int rateTicks;
    private int ratePlaced;
    private int rateLastCount = -1;

    /** Fastest while placing (blocks a tick); with nothing to place in reach, on to the next blocks faster. */
    private static final double SWEEP_MAX = 0.4;
    private static final double SWEEP_TRAVEL = 0.6;
    /** Up to this speed the printer places while flying a lane. */
    private static final double SWEEP_SAFE = 0.45;
    /** A layer with fewer unfinished blocks than this isn't worth lanes. */
    private static final int SWEEP_MIN_BLOCKS = 40;
    /** Nor one with fewer than this many on average for each lane it would take. */
    private static final int LANE_MIN_BLOCKS = 40;

    private long lastLaneStallNote = -100000;
    /** Blocks the printer didn't take while in reach (see tickNow), until when they're left out. */
    private final Map<BlockPos, Long> notTaken = new HashMap<>();
    private long lastNotTakenAt = -100000;

    private boolean sweeping() {
        return state == State.FLYING && laneEnd != null && !sweepDone && sweepFor == AutoBuild.INSTANCE.layers.getLayer();
    }

    /**
     * Full layers are flown over in straight lanes (like mowing a lawn), as wide as the printer reaches sideways, the
     * printer placing all the way: no stops, no flying with the printer waiting. The speed is the fastest at which
     * nothing in reach is left behind (see sweepSpeed). Layers of chains (hoppers into hoppers, rails) and what's left
     * after the last lane go spot by spot. Returns false when there's no sweep.
     */
    private boolean sweepTick(Minecraft mc, LocalPlayer player, WorldSchematic schematic, List<BlockPos> inReach, double reach,
                              MoveMode mode) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        if (layers.getLayer() != sweepFor) {
            sweepFor = layers.getLayer();
            sweepPlanned = false;
            sweepDone = false;
            laneEnd = null;
            sweepRetryAfterScan = -1;
            // The new layer's blocks at once: the work is still the last layer's until the next scan
            ticksUntilScan = 0;
        }
        if (sweepDone || !Configs.Movement.LANES.getBooleanValue() || !layers.isActive() || layers.isFinishingDeferred()) {
            return false;
        }
        if (!sweepPlanned) {
            // Only from a scan of these layers: right after a layer change the work is still the last layer's
            // (a block left on Y 58), and Y 60 went spot by spot with "only 0 blocks" (13 min instead of about 7)
            if (scannedLayer != layers.getLayer() || scannedTop != layers.getLayerTop() || scans <= sweepRetryAfterScan) {
                return false;
            }
            // Not while most of the layer waits for its items (being bought): planned from a handful of blocks,
            // the whole layer went spot by spot (a test layer took 60 s instead of 32)
            if (work.isEmpty() || waitingForItems > work.size()) {
                return false;
            }
            sweepPlanned = true;
            int planned = planLanes(player, schematic, reach);
            if (planned < 0) {
                // Few blocks to do now, but many more waiting for items or not loaded yet: again after the next scan
                sweepPlanned = false;
                sweepRetryAfterScan = scans;
                return false;
            }
            if (planned == 0) {
                sweepDone = true;
                return false;
            }
        }
        if (!ensureFlying(player)) {
            return true;
        }
        if (laneEnd == null || state != State.FLYING) {
            if (laneEnd != null && laneApproach && player.position().distanceToSqr(laneHover(laneStart)) <= 1.5 * 1.5) {
                // At the lane's start: along it
                laneApproach = false;
                flyAlongLane(player, laneEnd);
            } else if (laneEnd != null && (laneApproach || player.position().distanceToSqr(laneHover(laneEnd)) > 2.0 * 2.0)) {
                // Stopped on the way (a trip to the shop, a vault): on from here. Not again and again without
                // getting any closer: then the lane is left there
                double left = Math.sqrt(player.position().distanceToSqr(laneHover(laneApproach ? laneStart : laneEnd)));
                laneRetries = left < laneRetryDistance - 1 ? 0 : laneRetries + 1;
                laneRetryDistance = left;
                if (laneRetries >= 3) {
                    laneBlocked(player, String.format(java.util.Locale.ROOT, "stopped %d times %.1f from its %s without getting closer",
                            laneRetries, left, laneApproach ? "start" : "end"));
                    return true;
                }
                flyAlongLane(player, laneApproach ? laneStart : laneEnd);
            } else if (laneEnd != null && !inReach.isEmpty() && laneEndWaited < LANE_END_WAIT) {
                // At the lane's end with blocks in reach still to go in (most only waiting for the server's answer to
                // the block next to them): a moment for those, not left for spot by spot after the lanes
                laneEndWaited++;
                settle(player);
                return true;
            } else if (!nextLane(player)) {
                sweepDone = true;
                laneEnd = null;
                state = State.IDLE;
                BuildLog.line("SWEEP", "all lanes of " + layers.describeLayers() + " flown; " + work.size()
                        + " blocks left for spot by spot");
                RunLog.lanesDone(work.size());
                return false;
            }
        }
        gliding = true;
        glideSpeed = sweepSpeed(mc, player, inReach, reach);
        // Never stood still on a lane for good: nothing placed for 2 s while it waits, it goes on (what's left there
        // is done spot by spot after the lanes)
        if (glideSpeed <= 0 && tick - lastPlacementTick > 40 && !AutoBuild.INSTANCE.breaker.isBusy()
                && !AutoBuild.INSTANCE.fluids.isBusy()) {
            glideSpeed = SWEEP_MAX / 4;
            if (tick - lastLaneStallNote > 20 * 10) {
                lastLaneStallNote = tick;
                BuildLog.line("SWEEP", "lane " + (laneIndex + 1) + ": waited 2 s with nothing placed for " + inReach.size()
                        + " block(s) in reach; going on (they're done after the lanes)");
            }
        }
        learnRate(inReach);
        move(mc, player, mode);
        return true;
    }

    /**
     * The lanes for this layer from its unfinished blocks: 1 if planned, 0 if it isn't one to fly in lanes, -1 if
     * there are too few blocks to do yet but many more waiting (for items, or not loaded): planned later.
     */
    private int planLanes(LocalPlayer player, WorldSchematic schematic, double reach) {
        tallCache.clear();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int lowestY = Integer.MAX_VALUE;
        int chains = 0;
        int blocks = 0;
        int breaks = 0;
        for (BlockPos pos : work) {
            if (!inLayers(pos)) {
                continue;
            }
            blocks++;
            if (BlockCheck.check(player.level(), schematic, pos).needsBreaking()) {
                breaks++;
            }
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            lowestY = Math.min(lowestY, pos.getY());
            BlockState target = schematic.getBlockState(pos);
            if (target.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                    && target.getValue(net.minecraft.world.level.block.HopperBlock.FACING) != net.minecraft.core.Direction.DOWN
                    || target.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) {
                chains++;
            }
        }
        // The parts not loaded yet are part of the layer too: the lanes go over them (they load on the way)
        List<int[]> notLoaded = unloadedParts();
        for (int[] part : notLoaded) {
            minX = Math.min(minX, part[0]);
            maxX = Math.max(maxX, part[1]);
            minZ = Math.min(minZ, part[2]);
            maxZ = Math.max(maxZ, part[3]);
        }
        if (lowestY == Integer.MAX_VALUE) {
            lowestY = AutoBuild.INSTANCE.layers.getLayer();
        }
        String layer = AutoBuild.INSTANCE.layers.describeLayers();
        if (blocks + 16 * notLoaded.size() < SWEEP_MIN_BLOCKS && waitingForItems + unloaded.size() >= SWEEP_MIN_BLOCKS) {
            BuildLog.line("SWEEP", layer + ": only " + blocks + " blocks to do now, " + waitingForItems + " waiting for items and "
                    + unloaded.size() + " not loaded: the lanes are planned once those can be done");
            return -1;
        }
        if (blocks + 16 * notLoaded.size() < SWEEP_MIN_BLOCKS || chains * 5 > blocks) {
            BuildLog.line("SWEEP", layer + " spot by spot: " + (blocks < SWEEP_MIN_BLOCKS ? "only " + blocks + " blocks"
                    : chains + " of " + blocks + " blocks are chains (hoppers into hoppers, rails), placed in their order"));
            return 0;
        }
        lanesAlongX = maxX - minX >= maxZ - minZ;
        // As wide as the printer reaches sideways from the hover height (from the lowest blocks, with a margin)
        double eyeAbove = laneHover(new BlockPos(minX, lowestY, minZ)).y + player.getEyeHeight() - (lowestY + 0.5);
        double planR = planReach(reach);
        double sideways = Math.sqrt(Math.max(1, planR * planR - eyeAbove * eyeAbove));
        laneHalf = Math.max(1, (int) Math.floor(sideways - 0.5));
        int width = 2 * laneHalf + 1;
        int from = lanesAlongX ? minZ : minX;
        int to = lanesAlongX ? maxZ : maxX;
        // As few lanes as cover it, centred on it (a strip 3 wide is one lane over its middle, not along its edge),
        // moved sideways as far as they still cover it so their lines miss what's tall (a lane along the top of a wall
        // of the farm can't place from up there); one lane more when that's the only way
        int span = to - from + 1;
        int count = (span + width - 1) / width;
        // Few blocks for that many lanes, or mostly wrong blocks to break (each stops the lane anyway): spot by spot,
        // from one to the next. A layer of grown cactus to clear, 146 blocks, took 11 lanes and 284 s
        if (blocks < count * LANE_MIN_BLOCKS || breaks * 2 > blocks) {
            BuildLog.line("SWEEP", layer + " spot by spot: " + (breaks * 2 > blocks ? breaks + " of " + blocks
                    + " blocks are wrong ones to break" : "only " + blocks + " blocks for " + count + " lanes"));
            return 0;
        }
        int alongFrom = lanesAlongX ? minX : minZ;
        int alongTo = lanesAlongX ? maxX : maxZ;
        int first = from + laneHalf - (count * width - span) / 2;
        long blocked = Long.MAX_VALUE;
        for (int lanesTried = count; lanesTried <= count + 1 && blocked > 0; lanesTried++) {
            int slack = lanesTried * width - span;
            int centred = from + laneHalf - slack / 2;
            int bestFirst = centred;
            long fewest = Long.MAX_VALUE;
            for (int f = from + laneHalf - slack; f <= from + laneHalf; f++) {
                long tall = 0;
                for (int i = 0; i < lanesTried; i++) {
                    tall += tallLength(player, f + i * width, alongFrom, alongTo);
                }
                if (tall < fewest || tall == fewest && Math.abs(f - centred) < Math.abs(bestFirst - centred)) {
                    fewest = tall;
                    bestFirst = f;
                }
            }
            if (fewest < blocked) {
                blocked = fewest;
                first = bestFirst;
                count = lanesTried;
            }
        }
        if (blocked > 0) {
            BuildLog.line("SWEEP", "something tall stands on the lanes' lines for " + blocked + " blocks however they're laid: over it there");
        }
        laneFirst = first;
        laneWidth = width;
        laneAlongFrom = alongFrom;
        laneAlongTo = alongTo;
        laneEyeAbove = eyeAbove;
        laneLines.clear();
        flownParts.clear();
        List<Integer> centers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            centers.add(first + i * width);
        }
        // From the side the player is on
        double at = lanesAlongX ? player.getZ() : player.getX();
        if (Math.abs(at - from) > Math.abs(at - to)) {
            java.util.Collections.reverse(centers);
        }
        lanes = centers;
        laneIndex = -1;
        sweepDirection = 1;
        laneEnd = null;
        BuildLog.line("SWEEP", String.format(java.util.Locale.ROOT, "%s in %d %s lanes %d wide, for %d blocks%s%s (reach %.2f, %.2f above the blocks, %.2f sideways)",
                layer, centers.size(), lanesAlongX ? "east-west" : "north-south", width, blocks,
                notLoaded.isEmpty() ? "" : " and " + notLoaded.size() + " parts not loaded yet",
                work.size() > blocks ? " (" + (work.size() - blocks) + " left in the layers below: spot by spot after the lanes)" : "",
                planR, eyeAbove, sideways));
        return 1;
    }

    /** One part of a lane: from-to along it, its lowest block, its blocks and the parts not loaded yet in it. */
    private record LanePart(int index, int c, int lo, int hi, int y, int blocks, int unloaded, int of) {
    }

    /**
     * The next part of a lane with unfinished blocks, flown from its end nearest the player. A lane is split where
     * something tall stands on its line (a wall of the farm) or where nothing is to be done for a long stretch; each part
     * is flown on its own, the nearest first: the parts on this side of a wall one lane after the other, the other side
     * later, never up over the wall and down again for each lane. Over the player's farm the lanes went up over its
     * walls and the whole middle (built already) for every lane and placed almost nothing on either side: 1 to 5 blocks a
     * second instead of 18, and 1273 blocks left for spot by spot. False when none is left.
     */
    private boolean nextLane(LocalPlayer player) {
        List<LanePart> parts = laneParts(player);
        // A part with only a few blocks isn't flown as a lane (the first three lanes of a layer, 1, 2 and 5 blocks,
        // took 15 s, mostly flying to them): the lanes beside it reach some of them, the rest go spot by spot after
        // the lanes. Not a part not loaded yet (unknown how much is there)
        int tiny = 0;
        int tinyBlocks = 0;
        for (LanePart part : parts) {
            if (part.blocks() < MIN_LANE_PART && part.unloaded() == 0) {
                tiny++;
                tinyBlocks += part.blocks();
            }
        }
        if (tiny > 0) {
            parts.removeIf(part -> part.blocks() < MIN_LANE_PART && part.unloaded() == 0);
            if (tiny != lastTinyParts) {
                lastTinyParts = tiny;
                BuildLog.line("SWEEP", tiny + " lane part" + (tiny == 1 ? "" : "s") + " with fewer than " + MIN_LANE_PART
                        + " blocks (" + tinyBlocks + " in all) not flown as lanes: placed from the lanes beside them or after the lanes");
            }
        }
        if (parts.isEmpty()) {
            return false;
        }
        // Nearest end first; something tall in the way there counts as far (up and down again); about as near (within
        // a block): the lanes in their order, so the sweep goes on the way it was going
        Vec3 at = player.position();
        parts.sort(java.util.Comparator.comparingDouble(part -> partDistance(at, part)));
        double nearest = Double.MAX_VALUE;
        List<double[]> rated = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            LanePart part = parts.get(i);
            double raw = partDistance(at, part);
            if (raw > nearest + 1) {
                break;
            }
            double distance = raw + (overWall(player, part) ? OVER_WALL : 0);
            nearest = Math.min(nearest, distance);
            rated.add(new double[]{i, distance});
        }
        LanePart best = null;
        for (double[] rating : rated) {
            LanePart part = parts.get((int) rating[0]);
            if (rating[1] <= nearest + 1 && (best == null || part.index() < best.index())) {
                best = part;
            }
        }
        // The lane beside the one just flown, the way the sweep goes, unless another is much nearer: lane 4 was taken
        // before lane 3 (lane 3's end was 1 block farther), and lane 3 was flown later from the other side
        if (laneIndex >= 0) {
            LanePart inOrder = null;
            for (LanePart part : parts) {
                if (part.index() == laneIndex + sweepDirection
                        && (inOrder == null || partDistance(at, part) < partDistance(at, inOrder))) {
                    inOrder = part;
                }
            }
            if (inOrder != null && inOrder != best
                    && partDistance(at, inOrder) + (overWall(player, inOrder) ? OVER_WALL : 0) <= nearest + IN_ORDER_SLACK) {
                best = inOrder;
            }
        }
        // The layer's first lane: the edge nearest the player, then across in order. From the middle it swept to one
        // edge and flew back over the half it had done for the other (lane 11 down to 1, then back to 12)
        if (laneIndex < 0 && parts.size() > 1) {
            LanePart low = null;
            LanePart high = null;
            for (LanePart part : parts) {
                if (low == null || part.index() < low.index()) {
                    low = part;
                }
                if (high == null || part.index() > high.index()) {
                    high = part;
                }
            }
            LanePart edge = partDistance(at, low) <= partDistance(at, high) ? low : high;
            sweepDirection = edge == low ? 1 : -1;
            if (edge != best) {
                BuildLog.line("SWEEP", "starting at the edge nearest the player: lane " + (edge.index() + 1) + " (lane "
                        + (best.index() + 1) + " was nearest, in the middle)");
                best = edge;
            }
        }
        laneIndex = best.index();
        int c = best.c();
        flownParts.computeIfAbsent(c, k -> new ArrayList<>()).add(new int[]{best.lo(), best.hi()});
        // The lane's blocks, for the run log's summary at its end (what it left, and why)
        if (RunLog.isActive()) {
            List<BlockPos> laneBlocks = new ArrayList<>();
            for (BlockPos pos : work) {
                int along = lanesAlongX ? pos.getX() : pos.getZ();
                if (inLayers(pos) && laneOf(lanesAlongX ? pos.getZ() : pos.getX()) == c && along >= best.lo() && along <= best.hi()) {
                    laneBlocks.add(pos);
                }
            }
            RunLog.laneStart((laneIndex + 1) + "/" + lanes.size(), laneBlocks, AutoBuild.INSTANCE.layers.getLayerTop());
        }
        double along = lanesAlongX ? at.x : at.z;
        boolean forward = Math.abs(along - best.lo()) <= Math.abs(along - best.hi());
        int startAlong = forward ? best.lo() - 1 : best.hi() + 1;
        int endAlong = forward ? best.hi() + 1 : best.lo() - 1;
        int line = lineOf(c);
        BlockPos start = lanePos(line, startAlong, best.y());
        BlockPos end = lanePos(line, endAlong, best.y());
        // An end the player doesn't fit at (a column of the farm already standing up there) is pulled in
        // along the lane: the printer still reaches the last blocks from a little before it
        laneStart = freeLaneEnd(player, start, end);
        laneEnd = freeLaneEnd(player, end, start);
        laneRetries = 0;
        laneRetryDistance = Double.MAX_VALUE;
        laneEndWaited = 0;
        // What the lane takes: if the inventory won't last it, it's topped up first, not halfway along it
        AutoBuild.INSTANCE.beforeLane(laneItems(c, best.lo(), best.hi()));
        // Flown to its start first when that's not right here, or not in the clear from here (over a wall: the lane
        // itself stays at the hover height, a test lane went 10 up from its first block)
        laneApproach = player.position().distanceToSqr(laneHover(laneStart)) > 2.5 * 2.5
                || !FlyPath.clearAsFarAsKnown(player.level(), player.position(), laneHover(laneStart));
        flyAlongLane(player, laneApproach ? laneStart : laneEnd);
        BuildLog.line("SWEEP", "lane " + (laneIndex + 1) + " of " + lanes.size()
                + (best.of() > 1 ? " (" + (lanesAlongX ? "x " : "z ") + best.lo() + " to " + best.hi() + " of it, "
                + best.of() + " parts: something tall or nothing to do in between)" : "") + ": " + best.blocks() + " blocks"
                + (best.unloaded() > 0 ? " and " + best.unloaded() + " parts not loaded yet" : "") + ", from "
                + laneStart.toShortString() + " to " + laneEnd.toShortString() + String.format(java.util.Locale.ROOT,
                " | printer %.2f blocks a tick", sweepRate));
        return true;
    }

    /** The block of a lane at this place along it. */
    private BlockPos lanePos(int c, int along, int y) {
        return lanesAlongX ? new BlockPos(along, y, c) : new BlockPos(c, y, along);
    }

    /** From the player to the nearer end of a lane part, at the hover height. */
    private double partDistance(Vec3 at, LanePart part) {
        int line = lineOf(part.c());
        return Math.sqrt(Math.min(at.distanceToSqr(laneHover(lanePos(line, part.lo() - 1, part.y()))),
                at.distanceToSqr(laneHover(lanePos(line, part.hi() + 1, part.y())))));
    }

    /** Something tall stands between the player and the nearer end of this part (a wall to go up over and down). */
    private boolean overWall(LocalPlayer player, LanePart part) {
        Vec3 at = player.position();
        int line = lineOf(part.c());
        Vec3 a = laneHover(lanePos(line, part.lo() - 1, part.y()));
        Vec3 b = laneHover(lanePos(line, part.hi() + 1, part.y()));
        Vec3 end = at.distanceToSqr(a) <= at.distanceToSqr(b) ? a : b;
        Vec3 from = new Vec3(at.x, end.y, at.z);
        if (FlyPath.clearAsFarAsKnown(player.level(), from, end)) {
            return false;
        }
        // Only a wall when the way over it climbs: one cactus grown a block on the way isn't (it made lane 3 count
        // 30 blocks farther, and lane 4 went first)
        List<Vec3> over = FlyPath.lineWay(player.level(), from, end, (int) Math.floor(end.y + 0.01), true);
        return over == null || over.stream().mapToDouble(p -> p.y).max().orElse(end.y) - end.y >= LANE_TALL;
    }

    /**
     * The parts of the lanes still to fly: per lane, its unfinished blocks (and the parts not loaded yet) along it, not
     * in a stretch flown already, split where something tall stands on its line or nothing is to be done for long.
     */
    private List<LanePart> laneParts(LocalPlayer player) {
        // Each lane's line from what's in the way on it now (a grown cactus falls once the sand beside it is in)
        placeLines(player);
        // Per lane: {from, to, lowest y, blocks, parts not loaded} for each block and each part not loaded
        Map<Integer, List<int[]>> spans = new HashMap<>();
        for (BlockPos pos : work) {
            if (!inLayers(pos)) {
                continue;
            }
            int c = laneOf(lanesAlongX ? pos.getZ() : pos.getX());
            int along = lanesAlongX ? pos.getX() : pos.getZ();
            // Not where a wall stands on the lane's line (blocks beside it in the lane): no part may reach over the
            // wall, the printer gets those from the part's end next to it (a test lane ran along a row the walls
            // cross, with blocks beside them, and went 10 up over each wall)
            if (c != Integer.MIN_VALUE && !flown(c, along, along) && !tallOnLine(player, lineOf(c), along, along)) {
                spans.computeIfAbsent(c, k -> new ArrayList<>()).add(new int[]{along, along, pos.getY(), 1, 0});
            }
        }
        for (int[] part : unloadedParts()) {
            int crossFrom = lanesAlongX ? part[2] : part[0];
            int crossTo = lanesAlongX ? part[3] : part[1];
            int from = lanesAlongX ? part[0] : part[2];
            int to = lanesAlongX ? part[1] : part[3];
            for (int c : lanes) {
                // Flown along to within 2 blocks of its ends counts (the printer reached those): a part not loaded when
                // the lane went by kept lane 8 open, and after the last lane it flew 139 blocks back there for nothing
                if (crossTo >= c - laneHalf && crossFrom <= c + laneHalf && !flown(c, Math.min(to, from + 2), Math.max(from, to - 2))) {
                    spans.computeIfAbsent(c, k -> new ArrayList<>()).add(new int[]{from, to, AutoBuild.INSTANCE.layers.getLayer(), 0, 1});
                }
            }
        }
        List<LanePart> parts = new ArrayList<>();
        for (int index = 0; index < lanes.size(); index++) {
            int c = lanes.get(index);
            List<int[]> lane = spans.get(c);
            if (lane == null) {
                continue;
            }
            lane.sort(java.util.Comparator.comparingInt(span -> span[0]));
            List<int[]> runs = new ArrayList<>();
            int[] run = lane.getFirst().clone();
            for (int k = 1; k < lane.size(); k++) {
                int[] span = lane.get(k);
                if (span[0] > run[1] + 1 && (span[0] - run[1] - 1 >= LANE_GAP || tallOnLine(player, lineOf(c), run[1] + 1, span[0] - 1))) {
                    runs.add(run);
                    run = span.clone();
                    continue;
                }
                run[1] = Math.max(run[1], span[1]);
                run[2] = Math.min(run[2], span[2]);
                run[3] += span[3];
                run[4] += span[4];
            }
            runs.add(run);
            for (int[] r : runs) {
                parts.add(new LanePart(index, c, r[0], r[1], r[2], r[3], r[4], runs.size()));
            }
        }
        return parts;
    }

    /** How many places along a lane's line (from-to) have something tall on them (see tallOnLine). */
    private int tallLength(LocalPlayer player, int c, int from, int to) {
        int tall = 0;
        for (int along = from; along <= to; along++) {
            if (tallOnLine(player, c, along, along)) {
                tall++;
            }
        }
        return tall;
    }

    /** The lane (its cross coordinate) a block at this cross coordinate is in, or MIN_VALUE for none. */
    private int laneOf(int cross) {
        int k = Math.floorDiv(cross - (laneFirst - laneHalf), laneWidth);
        return k < 0 || k >= lanes.size() ? Integer.MIN_VALUE : laneFirst + k * laneWidth;
    }

    /** All of from-to along this lane was flown already. */
    private boolean flown(int c, int from, int to) {
        for (int[] range : flownParts.getOrDefault(c, List.of())) {
            if (from >= range[0] && to <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Something on the lane's line between these two places along it (both included) the player can't fly through
     * at the hover height or the next {@link #LANE_TALL} above it: a wall, to be flown up over and down again.
     */
    private boolean tallOnLine(LocalPlayer player, int c, int from, int to) {
        net.minecraft.world.level.Level level = player.level();
        int hoverY = (int) Math.floor(laneHover(lanePos(c, from, 0)).y);
        for (int along = from; along <= to; along++) {
            BlockPos feet = lanePos(c, along, hoverY);
            Boolean known = tallCache.get(feet);
            if (known == null) {
                if (!level.isLoaded(feet)) {
                    continue;
                }
                boolean over = false;
                for (int up = 0; up < LANE_TALL && !over; up++) {
                    over = FlyPath.fits(level, feet.above(up));
                }
                known = !over;
                tallCache.put(feet, known);
            }
            if (known) {
                return true;
            }
        }
        return false;
    }

    /** What tallOnLine found, per place on a lane's line at the hover height (walls don't move during a layer). */
    private final Map<BlockPos, Boolean> tallCache = new HashMap<>();

    /** The row this lane (by its cross coordinate) is flown along (see placeLines). */
    private int lineOf(int c) {
        return laneLines.getOrDefault(c, c);
    }

    /**
     * The row each lane is flown along: its middle, or the row beside it when the middle has something in the way at
     * the hover height (or a cactus right under the feet) and the row beside it has less: a cactus grown up out of the
     * layer. Over the cactus rows of the player's tower the lanes went up over every grown column, out of the
     * printer's reach (lane 11 left 116 of its 299 blocks), and two lanes whose ends the columns took up were left for
     * last. The lane's blocks stay the same: its row on the far side is one further away then, still in the printer's
     * range (or the lane stays on its middle). Of the two rows beside it, the one with less left on the far side.
     */
    private void placeLines(LocalPlayer player) {
        double range = me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
        double far = laneHalf + 1;
        boolean room = Math.sqrt(far * far + laneEyeAbove * laneEyeAbove) <= range - 0.1;
        // What's left per row across the lanes (for the far side)
        Map<Integer, Integer> perRow = new HashMap<>();
        for (BlockPos pos : work) {
            if (inLayers(pos)) {
                perRow.merge(lanesAlongX ? pos.getZ() : pos.getX(), 1, Integer::sum);
            }
        }
        for (int c : lanes) {
            int line = c;
            int middle = inTheWay(player, c);
            if (middle > 0 && room) {
                int fewest = middle;
                int farLeft = Integer.MAX_VALUE;
                for (int side : new int[]{1, -1}) {
                    int n = inTheWay(player, c + side);
                    int left = perRow.getOrDefault(c - side * laneHalf, 0);
                    if (n < fewest || n == fewest && line != c && left < farLeft) {
                        fewest = n;
                        farLeft = left;
                        line = c + side;
                    }
                }
            }
            Integer before = laneLines.put(c, line);
            if (line != c && (before == null || before != line)) {
                BuildLog.line("SWEEP", String.format(java.util.Locale.ROOT, "lane %d is flown along %s %d, the row beside its middle: %d places on its "
                                + "middle (%s %d) have a cactus or something else in the way at the hover height, %d on that row",
                        lanes.indexOf(c) + 1, lanesAlongX ? "z" : "x", line, middle, lanesAlongX ? "z" : "x", c, inTheWay(player, line)));
            }
        }
    }

    /**
     * How many places along this row (the whole layer's length, at the hover height) the player doesn't fit, or would
     * touch a cactus (or anything else that hurts) under or beside the body.
     */
    private int inTheWay(LocalPlayer player, int line) {
        net.minecraft.world.level.Level level = player.level();
        int n = 0;
        for (int along = laneAlongFrom - 1; along <= laneAlongTo + 1; along++) {
            Vec3 hover = laneHover(lanePos(line, along, 0));
            BlockPos feet = BlockPos.containing(hover.x, hover.y + 0.01, hover.z);
            if (level.isLoaded(feet) && (!FlyPath.fits(level, feet) || harmNear(level, hover) != null)) {
                n++;
            }
        }
        return n;
    }

    /**
     * The parts of the current layers that aren't loaded yet (and weren't seen finished): x and z from-to of each,
     * within the schematic. Unknown what's there, so the lanes go over them.
     */
    private List<int[]> unloadedParts() {
        List<int[]> parts = new ArrayList<>();
        for (long key : unloadedAreas.keySet()) {
            int chunkX = (int) key;
            int chunkZ = (int) (key >> 32);
            for (SchematicHelper.Area area : SchematicHelper.areas()) {
                int x0 = Math.max(area.minX(), chunkX * 16);
                int x1 = Math.min(area.maxX(), chunkX * 16 + 15);
                int z0 = Math.max(area.minZ(), chunkZ * 16);
                int z1 = Math.min(area.maxZ(), chunkZ * 16 + 15);
                if (x0 <= x1 && z0 <= z1) {
                    parts.add(new int[]{x0, x1, z0, z1});
                }
            }
        }
        return parts;
    }

    private void flyAlongLane(LocalPlayer player, BlockPos to) {
        if (laneApproach && to.equals(laneStart)) {
            RunLog.tripStart(Minecraft.getInstance(), "lane-approach");
        } else {
            RunLog.tripEnd(Minecraft.getInstance(), "at-lane-start");
        }
        flyTarget = laneHover(to);
        flyPath = null;
        target = to;
        spotFor = null;
        spotFlight = false;
        stuckTicks = 0;
        lastDistance = Double.MAX_VALUE;
        replans = 0;
        moveStartTick = tick;
        moveFrom = player.position();
        state = State.FLYING;
    }

    /** What the blocks of the lane at this cross position, from-to along it, take, item by item. */
    private Map<net.minecraft.world.item.Item, Integer> laneItems(int c, int from, int to) {
        WorldSchematic schematic = SchematicHelper.world();
        Map<net.minecraft.world.item.Item, Integer> items = new HashMap<>();
        if (schematic == null) {
            return items;
        }
        // Everything the printer reaches from the lane's line on the way, a row beyond the lane's own on either side:
        // it places those too (a lane of 366 blocks placed 818), and two trips started near a lane's end ("string 8
        // left" at 82 and 96 %) after the check at its start
        int line = lineOf(c);
        int band = laneHalf + 1;
        for (BlockPos pos : work) {
            int along = lanesAlongX ? pos.getX() : pos.getZ();
            if (Math.abs((lanesAlongX ? pos.getZ() : pos.getX()) - line) > band || !inLayers(pos) || along < from - band || along > to + band) {
                continue;
            }
            net.minecraft.world.item.ItemStack stack = StateRules.itemFor(schematic.getBlockState(pos));
            if (!stack.isEmpty()) {
                items.merge(stack.getItem(), 1, Integer::sum);
            }
        }
        // And some to spare: a trip starts at this many left anyway
        int left = Configs.Shop.BUY_WHEN_LEFT.getIntegerValue();
        items.replaceAll((item, count) -> count + count / 10 + left);
        return items;
    }

    /** A block of the layers being built now (not one left from a layer below: those go spot by spot). */
    private static boolean inLayers(BlockPos pos) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        return pos.getY() >= layers.getLayer() && pos.getY() <= layers.getLayerTop();
    }

    /**
     * The end of a lane where the player fits at the hover height: this one, or the first one in from it towards
     * the other end, at most as far in as the printer reaches sideways. This one if there's none.
     */
    private BlockPos freeLaneEnd(LocalPlayer player, BlockPos end, BlockPos other) {
        int dx = Integer.signum(other.getX() - end.getX());
        int dz = Integer.signum(other.getZ() - end.getZ());
        int length = Math.abs(other.getX() - end.getX()) + Math.abs(other.getZ() - end.getZ());
        if (!player.level().isLoaded(BlockPos.containing(laneHover(end)))) {
            // Not known yet what's there (the lane goes over a part not loaded yet)
            return end;
        }
        for (int in = 0; in <= Math.min(laneHalf + 1, length / 2); in++) {
            BlockPos pos = end.offset(dx * in, 0, dz * in);
            if (FlyPath.fits(player.level(), BlockPos.containing(laneHover(pos)))) {
                if (in > 0) {
                    BuildLog.line("SWEEP", "lane end " + end.toShortString() + " is taken up there (" + player.level()
                            .getBlockState(BlockPos.containing(laneHover(end))).getBlock().getName().getString() + "): the lane ends "
                            + in + " in from it");
                }
                return pos;
            }
        }
        return end;
    }

    /**
     * The lane's start or end can't be got to (no way there, or stuck): the lane is left there and the next one taken;
     * what it didn't get to is done spot by spot after the lanes. Never down into the layer to get near the end
     * instead (that stood still in the layer, a log had it every tick for 20 s).
     */
    private void laneBlocked(LocalPlayer player, String why) {
        BuildLog.line("SWEEP", "lane " + (laneIndex + 1) + " of " + lanes.size() + " left here: " + why + "; on to the next lane");
        flyPath = null;
        flyTarget = null;
        halt(player);
        state = State.IDLE;
        laneEnd = null;
    }

    /** Flying along a lane now (not to its start): for the run log's time by category. */
    public boolean onLane() {
        return laneFlight() && !laneApproach;
    }

    /** Rises away from a cactus about to touch the body (see keepClearOfHarm), without moving otherwise (Auto break). */
    public void keepClear(Minecraft mc) {
        if (mc.player != null && mc.level != null && flying(mc.player)) {
            keepClearOfHarm(mc.level, mc.player);
        }
    }

    /** Flying to the start of the next lane (placing what's in reach on the way). */
    public boolean laneApproaching() {
        return laneFlight() && laneApproach;
    }

    /** "7/15" for the lane being flown (or flown to), "-" when none. */
    public String laneName() {
        return laneEnd != null && laneIndex >= 0 ? (laneIndex + 1) + "/" + lanes.size() : "-";
    }

    /** The flight under way is along a lane (or to its start), not to a spot for a block. */
    private boolean laneFlight() {
        return laneEnd != null && spotFor == null && target != null && (target.equals(laneEnd) || target.equals(laneStart));
    }

    /**
     * Along a lane, the way keeps to the lane's line (within a block of it, or of where the player is now): over
     * what's in the way, never around it. Around the walls of hoppers in the middle of the player's farm it went 9
     * blocks to the side for the whole lane, and placed nothing. Any way at all for other flights.
     */
    private java.util.function.Predicate<BlockPos> wayLimit(LocalPlayer player) {
        if (!laneFlight() || laneApproach || laneIndex < 0 || laneIndex >= lanes.size()) {
            return p -> true;
        }
        int c = lineOf(lanes.get(laneIndex));
        double off = Math.abs((lanesAlongX ? player.getZ() : player.getX()) - (c + 0.5));
        int half = Math.max(1, (int) Math.ceil(off));
        return p -> Math.abs((lanesAlongX ? p.getZ() : p.getX()) - c) <= half;
    }

    /**
     * Where the player flies over a block of the layer: the hover height above the layers being built (a little more
     * over a cactus, see LayerManager#hoverLift).
     */
    private static Vec3 laneHover(BlockPos pos) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        int hoverY = layers.getLayerTop() + Configs.Movement.HOVER_HEIGHT.getIntegerValue();
        return new Vec3(pos.getX() + 0.5, hoverY + layers.hoverLift(), pos.getZ() + 0.5);
    }

    /**
     * How fast to fly the lane: every placeable block in reach (and the ones next to a block just placed, a moment
     * later) gets placed before it leaves the reach. The printer places about one a turn, those about to leave first,
     * so the k-th of them is placed after k turns and has to be in reach until then: the speed is at most its distance
     * left to the edge of the reach over those turns. Nothing to place in reach: on at travel speed.
     */
    private double sweepSpeed(Minecraft mc, LocalPlayer player, List<BlockPos> inReach, double reach) {
        if (AutoBuild.INSTANCE.breaker.isBusy() || AutoBuild.INSTANCE.fluids.isBusy() || flyTarget == null) {
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 toTarget = flyTarget.subtract(player.position());
        Vec3 direction = new Vec3(toTarget.x, 0, toTarget.z);
        if (direction.lengthSqr() < 1.0e-4) {
            return SWEEP_MAX;
        }
        direction = direction.normalize();
        WorldSchematic schematic = SchematicHelper.world();
        // Nothing left behind that a moment more gets in: holes left on the way cost a trip back after the lanes
        if (schematic != null && holdForBehind(mc, player, schematic, direction, reach)) {
            RunLog.heldForBehind();
            return 0;
        }
        int ack = 2 * ServerLag.pingTicks(mc) + 2;
        double bucket = bucketRange(mc);
        List<double[]> leaving = new ArrayList<>(inReach.size());
        for (BlockPos pos : inReach) {
            Vec3 d = Vec3.atCenterOf(pos).subtract(eye);
            double along = d.dot(direction);
            double across = d.lengthSqr() - along * along;
            boolean water = schematic != null && BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.BUCKET;
            double r = water ? bucket : reach;
            leaving.add(new double[]{along + Math.sqrt(Math.max(0, r * r - across)), heldBack(pos) ? ack : 0});
        }
        if (leaving.isEmpty()) {
            return SWEEP_TRAVEL;
        }
        leaving.sort(java.util.Comparator.comparingDouble(l -> l[0]));
        double speed = SWEEP_MAX;
        for (int k = 0; k < leaving.size(); k++) {
            double room = leaving.get(k)[0] - 0.35;
            if (room <= 0) {
                return 0;
            }
            speed = Math.min(speed, room / ((k + 1) / sweepRate + leaving.get(k)[1]));
        }
        return speed;
    }

    /** How long the lane already stood still for each block at the back of the reach (see holdForBehind). */
    private final Map<BlockPos, Integer> behindWaited = new HashMap<>();
    /** At most this long for one block at the back of the reach. */
    private static final int HOLD_BEHIND_TICKS = 30;
    /** Within this much of leaving the reach, a block is at the back of it. */
    private static final double BEHIND_EDGE = 0.6;

    /**
     * A block about to leave the reach behind the lane that a moment more gets in: its click not answered yet (it may
     * come back empty: most of what the lanes left was "clicked-no-result", the server's answer came after the block
     * was out of reach), or one the printer (or the tiller) can do now, or one waiting for the block under it, itself
     * clicked a moment ago. The lane stands still for it, at most HOLD_BEHIND_TICKS each: the player asked for that
     * rather than the holes it came back for after the lanes.
     */
    private boolean holdForBehind(Minecraft mc, LocalPlayer player, WorldSchematic schematic, Vec3 direction, double reach) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        // Where the printer really clicks (the add-on holds back what's beyond the reach learned on this server)
        double learned = ReachStats.reach(reach);
        double r = learned < reach - 0.05 ? Math.min(reach, learned + 0.25) : reach;
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int cube = (int) Math.ceil(r);
        boolean hold = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = layers.getLayer(); y <= layers.getLayerTop(); y++) {
            for (int x = center.getX() - cube; x <= center.getX() + cube; x++) {
                for (int z = center.getZ() - cube; z <= center.getZ() + cube; z++) {
                    pos.set(x, y, z);
                    Vec3 d = Vec3.atCenterOf(pos).subtract(eye);
                    double distance = d.lengthSqr();
                    if (distance > r * r) {
                        continue;
                    }
                    double along = d.dot(direction);
                    double room = along + Math.sqrt(Math.max(0, r * r - (distance - along * along)));
                    if (room > BEHIND_EDGE || schematic.getBlockState(pos).isAir() || !worthWaitingFor(mc, schematic, pos)) {
                        continue;
                    }
                    BlockPos at = pos.immutable();
                    int waited = behindWaited.getOrDefault(at, 0);
                    if (waited < HOLD_BEHIND_TICKS) {
                        behindWaited.put(at, waited + 1);
                        hold = true;
                    }
                }
            }
        }
        if (behindWaited.size() > 512) {
            behindWaited.clear();
        }
        return hold;
    }

    /** See holdForBehind. */
    private boolean worthWaitingFor(Minecraft mc, WorldSchematic schematic, BlockPos pos) {
        Long leftOut = notTaken.get(pos);
        if (leftOut != null && leftOut > tick || AutoBuild.INSTANCE.givenUp(pos)) {
            return false;
        }
        // Clicked, the answer still to come (the client already shows the block)
        if (ServerAcks.works() && ServerAcks.isWaiting(pos)) {
            return true;
        }
        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
        if (result != BlockCheck.Result.MISSING && result != BlockCheck.Result.PRINTER_FIXES) {
            return false;
        }
        return canDoNow(mc, schematic, pos) || ServerAcks.works() && ServerAcks.isWaiting(pos.below());
    }

    /** How many blocks the printer really places a tick while it has something in reach (for sweepSpeed). */
    private void learnRate(List<BlockPos> inReach) {
        int placed = AutoBuild.INSTANCE.stats.totalPlaced();
        // Only while nothing else holds the printer back: the speed guard's limit (8 blocks a second) learned as the
        // printer's own speed had the next lane flown at a third of the speed (0.27 blocks a tick against 0.8 to 0.9)
        if (AutoBuild.INSTANCE.heldBackNow()) {
            rateLastCount = placed;
            return;
        }
        if (rateLastCount >= 0 && !inReach.isEmpty()) {
            rateTicks++;
            ratePlaced += placed - rateLastCount;
        }
        rateLastCount = placed;
        if (rateTicks >= 60) {
            double measured = ratePlaced / (double) rateTicks;
            sweepRate = Math.max(0.25, Math.min(1.0, 0.7 * sweepRate + 0.3 * measured));
            rateTicks = 0;
            ratePlaced = 0;
        }
    }

    private long lastRetry = -100000;

    /**
     * Nothing to do but blocks left for later (no way there, nothing happened to them in reach): rather than waiting
     * out their 30 s, they're tried again after a few seconds, from another spot (the build log showed the player
     * waiting minutes for the last block of a layer).
     */
    private void retryLeftForLater() {
        if (waitingForItems > 0 || tick - lastRetry < 20 * 5 || tick - lastWorkTick < 20 * 3) {
            return;
        }
        boolean givenUp = AutoBuild.INSTANCE.hasGivenUp();
        if (skipped.isEmpty() && unreachableAreas.isEmpty() && !givenUp) {
            return;
        }
        lastRetry = tick;
        StringBuilder which = new StringBuilder();
        int listed = 0;
        for (BlockPos pos : skipped.keySet()) {
            if (listed++ < 6) {
                which.append(which.isEmpty() ? "" : ", ").append(pos.toShortString());
            }
        }
        BuildLog.line("RETRY", skipped.size() + " block(s) left for later" + (givenUp ? " (and blocks the printer gave up on)" : "")
                + (unreachableAreas.isEmpty() ? "" : ", " + unreachableAreas.size() + " part(s) no flight got to")
                + " and nothing else to do: trying them again now" + (which.isEmpty() ? "" : " (" + which + (skipped.size() > 6 ? ", ..." : "") + ")"));
        skipped.clear();
        unreachableAreas.clear();
        AutoBuild.INSTANCE.forgetGivenUp();
        ticksUntilScan = 0;
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
            if (AutoBuild.INSTANCE.givenUp(p)) {
                return false;
            }
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, p);
            if (result.needsBreaking() || result == BlockCheck.Result.PROTECTED
                    || result == BlockCheck.Result.EXTRA && BlockBreaker.breakAnyway(mc.level, schematic, p)) {
                return true;
            }
            if (result == BlockCheck.Result.BUCKET) {
                return Fluids.canDo(mc, schematic, p, schematic.getBlockState(p));
            }
            if (result != BlockCheck.Result.MISSING && result != BlockCheck.Result.PRINTER_FIXES) {
                return false;
            }
            // Held back by the add-on's own order (water first, what it stands on first...): the printer won't get
            // it, so it doesn't count as something to place here. Except a stem on dirt that should be farmland: once
            // in reach the dirt is tilled (see Tiller, also when it's on a layer already finished), then it goes in
            if (!PlacementOrder.isReady(mc.level, schematic, p, schematic.getBlockState(p))) {
                return result == BlockCheck.Result.MISSING && Tiller.untilled(mc.level, schematic, p.below());
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
            if (result == BlockCheck.Result.MISSING && Configs.Layers.ADDON_PLACES.getBooleanValue()
                    && AutoBuild.INSTANCE.rails.selfPlaceFacing(mc.level, schematic, p, schematic.getBlockState(p)) != null) {
                return true;
            }
            // Air place does blocks with nothing beside or under them, now or later (not sand waiting for the string
            // under it: that waits, the printer can't place it)
            return AutoBuild.INSTANCE.airPlace.allowed() && result == BlockCheck.Result.MISSING
                    && AirPlace.needsAirPlace(mc.level, schematic, p, schematic.getBlockState(p));
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

    /** Sweep direction along the rows: +1 east, -1 west. */
    private int sweep = 1;
    /** Score taken off spots behind the sweep direction (more than any spot ahead can have). */
    private static final double BEHIND_PENALTY = 10000;

    /** How many of the nearest unfinished blocks are asked whether they can be placed now, when choosing a spot. */
    private static final int DOABLE_CHECKS = 600;
    /** Where to hover relative to a placeable block: above it, or 3 blocks to a side. */
    /** The layer the current sweep is for (a new one starts at its corner). */
    private int sweepLayer = Integer.MIN_VALUE;
    private static final int[][] HOVER_SHIFTS = {{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};
    /** The placeable block the chosen hover spot is for (the spot itself may be a block to the side of it). */
    @Nullable
    private BlockPos spotFor;
    /** Blocks the next spot isn't chosen for (they're in reach now: placed from here or on the way). */
    private Set<BlockPos> spotExclude = Set.of();
    /** The last spot bestSpot chose (a flight to it may go on to the next one without stopping). */
    @Nullable
    private BlockPos lastBestSpot;
    /** The last tick something in reach could be placed or the printer was busy. */
    private long lastWorkTick;
    /** Past the nearest ones, stop looking once this many placeable blocks are found. */
    private static final int ENOUGH_DOABLE = 40;
    private long lastNothingDoableReport = -100000;

    /** How many unfinished blocks near the player are looked at when choosing where to fly. */
    private static final int NEARBY_FOR_SPOT = 400;

    /**
     * The block to fly to: among the nearest blocks that can be done now, the one whose hover spot has the most
     * unfinished blocks within reach, a little less for spots further away. Fewer, better placed stops.
     */
    @Nullable
    private BlockPos bestSpot(Minecraft mc, LocalPlayer player, WorldSchematic schematic, double reach) {
        long started = Perf.start();
        BlockPos best = bestSpotNow(mc, player, schematic, reach);
        Perf.end("movement: next spot", started);
        return best;
    }

    @Nullable
    private BlockPos bestSpotNow(Minecraft mc, LocalPlayer player, WorldSchematic schematic, double reach) {
        Vec3 from = player.position();

        // Area by area: the layer is cut into 16x16 squares (chunks), done in snake order from the north-west corner.
        // It stays in a square until nothing in it can be placed, then goes to the first square in that order that has
        // something placeable (every square is checked, near or far). Chains (hoppers) are so followed from where they
        // start, in small areas, back and forth, never in long lines
        int layer = AutoBuild.INSTANCE.layers.getLayer();
        if (layer != sweepLayer) {
            sweepLayer = layer;
            routeLoggedFor = -1;
            unloadable.clear();
            lastAreaOrder = Long.MIN_VALUE;
            area = null;
            sweep = 1;
        }
        // Grouped once per change of the work list (a layer of 5000 blocks was sorted every tick before)
        java.util.Map<Long, List<BlockPos>> workByArea = areaWork();
        if (area == null && tick - lastNothingDoable < 20) {
            // Searched every area a moment ago and nothing could be placed: don't search the whole layer every tick
            return null;
        }
        // Placed something a moment ago: the area isn't finished just because nothing in it can be placed this very
        // tick. A row of hoppers goes in one by one, each as soon as the server has the one before it; flying off then
        // meant flying back for the rest (90 to 115 blocks each way, 55% of a build spent flying)
        int placedNow = AutoBuild.INSTANCE.stats.totalPlaced();
        if (placedNow != areaPlacedCount) {
            areaPlacedCount = placedNow;
            areaProgressTick = tick;
        }
        // Only while something there waits for the server's answer (it's placeable in a moment); else on at once (each
        // of the last sand blocks of a layer had it wait 2 s before flying on)
        if (area != null && workByArea.get(area) != null && !workByArea.get(area).isEmpty()
                && tick - areaProgressTick < stayTicks() && !anyDoable(mc, schematic, workByArea.get(area), spotExclude)
                && anyAnswerAwaited(workByArea.get(area))) {
            return null;
        }
        if (area == null || !anyDoable(mc, schematic, workByArea.get(area), spotExclude)) {
            java.util.Set<Long> keys = new java.util.HashSet<>(workByArea.keySet());
            keys.addAll(unloadedAreas.keySet());
            keys.removeAll(unloadable);
            keys.removeIf(this::areaUnreachable);
            // The nearest area with something placeable (by how far its square is), with a small extra cost for areas
            // behind in the usual order (north-west to south-east, snaking), so it doesn't hop back and forth.
            // Areas that aren't loaded yet come when nothing loaded is left, nearest first: none is skipped
            Long next = null;
            if (sweepDone) {
                next = routeNext(mc, schematic, workByArea, keys, from);
            } else {
                List<Long> byCost = new ArrayList<>(keys);
                byCost.sort(java.util.Comparator.comparingDouble(key -> areaCost(key, from)));
                for (Long key : byCost) {
                    if (anyDoable(mc, schematic, workByArea.get(key), spotExclude)) {
                        next = key;
                        break;
                    }
                }
            }
            if (next == null) {
                BlockPos nearestUnloaded = null;
                Long unloadedKey = null;
                for (Long key : keys) {
                    BlockPos notLoaded = unloadedAreas.get(key);
                    if (notLoaded != null && (nearestUnloaded == null
                            || from.distanceToSqr(Vec3.atCenterOf(notLoaded)) < from.distanceToSqr(Vec3.atCenterOf(nearestUnloaded)))) {
                        nearestUnloaded = notLoaded;
                        unloadedKey = key;
                    }
                }
                if (nearestUnloaded != null) {
                    if (Vec3.atCenterOf(nearestUnloaded).distanceToSqr(from) < 24 * 24) {
                        if (nearUnloadedSince < 0) {
                            nearUnloadedSince = tick;
                        } else if (tick - nearUnloadedSince > 20 * 15) {
                            PrinterAddon.debug("Area {} doesn't load: leaving it", areaName(unloadedKey));
                            unloadable.add(unloadedKey);
                            nearUnloadedSince = -1;
                        }
                    } else {
                        nearUnloadedSince = -1;
                    }
                    area = null;
                    spotFor = null;
                    lastBestSpot = null;
                    return nearestUnloaded;
                }
            }
            if (next == null) {
                lastNothingDoable = tick;
                explainWait(mc, schematic);
                if (tick - lastNothingDoableReport > 20 * 30 && !work.isEmpty()) {
                    lastNothingDoableReport = tick;
                    BuildLog.line("WAIT", "nothing of the " + work.size() + " unfinished blocks can be placed now: mostly "
                            + waitReason + "; nearest: "
                            + Diagnose.explain(mc, schematic, nearest(player, work)));
                }
                area = null;
                return null;
            }
            if (!next.equals(area)) {
                PrinterAddon.debug("Next area: {}", areaName(next));
            }
            area = next;
            lastAreaOrder = areaOrder(next);
            // Back and forth inside the square starts at its west side on even area rows, east side on odd ones
            sweep = Math.floorMod((int) (area >> 32), 2) == 0 ? 1 : -1;
        }
        List<BlockPos> here = new ArrayList<>();
        for (BlockPos pos : workByArea.get(area)) {
            if (canDoNow(mc, schematic, pos) && !spotExclude.contains(pos)) {
                here.add(pos);
            }
        }
        // Inside the area a fixed plan, like mowing a lawn: 9 spots 6 blocks apart (3 rows of 3), done row by row,
        // back and forth, starting at the corner nearest the player. At each spot everything in reach is placed
        // (also what only becomes placeable there, like tilling after the dirt), then on to the next spot in the
        // plan that has something placeable; past the end it starts over (a chain grown back, items that arrived)
        double eyeHeight = player.getEyeHeight();
        int y = here.isEmpty() ? AutoBuild.INSTANCE.layers.getLayer() : here.getFirst().getY();
        // How far apart the spots can be: from what the player really reaches sideways at the hover height (the
        // printer's range, minus what was learned about this server, see planReach)
        int minX = ((int) (long) area) * 16;
        int minZ = ((int) (area >> 32)) * 16;
        double eyeAbove = hoverPoint(mc, new BlockPos(minX + 8, y, minZ + 8)).y + eyeHeight - (y + 0.5);
        double planR = planReach(reach);
        double sideways = Math.sqrt(Math.max(1, planR * planR - eyeAbove * eyeAbove));
        int spacing = Math.max(2, Math.min(8, (int) Math.floor(sideways * 1.41)));
        if (!area.equals(planArea) || spacing != planSpacing) {
            planArea = area;
            planSpacing = spacing;
            plan = lawnPlan(area, from, y, spacing);
            planIndex = 0;
            BuildLog.line("PLAN", String.format(java.util.Locale.ROOT, "area %s: %d spots %d apart (reach %.2f, %.2f above the blocks, %.2f sideways)",
                    areaName(area), plan.size(), spacing, planR, eyeAbove, sideways));
        }
        for (int step = 0; step < plan.size(); step++) {
            int i = (planIndex + step) % plan.size();
            BlockPos spot = plan.get(i).atY(y);
            Vec3 feetAt = hoverPoint(mc, spot);
            Vec3 eye = feetAt.add(0, eyeHeight, 0);
            BlockPos nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (BlockPos pos : here) {
                Vec3 center = Vec3.atCenterOf(pos);
                double d = eye.distanceToSqr(center);
                // Planned with a margin: the printer doesn't always manage at its full range. Not a block right at
                // the feet either: the printer never places within 1 block of the feet or the eye
                if (d < nearestDistance && reachable(mc, schematic, eye, pos, planR + 0.25)
                        && (d > 1 && feetAt.distanceToSqr(center) > 1 || BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.BUCKET)) {
                    nearestDistance = d;
                    nearest = pos;
                }
            }
            if (nearest != null) {
                // Water for a waterlogged block needs the player close (a bucket reaches less than the printer and
                // needs a clear view): straight above that block instead of the spot
                if (BlockCheck.check(mc.level, schematic, nearest) == BlockCheck.Result.BUCKET
                        && eye.distanceToSqr(Vec3.atCenterOf(nearest)) > 3.0 * 3.0) {
                    planIndex = i;
                    spotFor = nearest;
                    lastBestSpot = nearest;
                    return nearest;
                }
                if (i != planIndex) {
                    PrinterAddon.debug("Next spot in area {}: {} of {}", areaName(area), i + 1, plan.size());
                }
                planIndex = i;
                spotFor = nearest;
                lastBestSpot = spot;
                return spot;
            }
        }
        // Nothing placeable in reach of any spot (blocks near the area's edge): straight to the nearest one
        BlockPos nearest = null;
        for (BlockPos pos : here) {
            if (nearest == null || from.distanceToSqr(Vec3.atCenterOf(pos)) < from.distanceToSqr(Vec3.atCenterOf(nearest))) {
                nearest = pos;
            }
        }
        spotFor = nearest;
        lastBestSpot = nearest;
        return nearest;
    }

    @Nullable
    private Long planArea;
    private int planSpacing;
    private List<BlockPos> plan = List.of();
    private int planIndex;

    /**
     * The reach planned with (from the eye to a block's centre). Starts a little inside the printer's range and is
     * learned on the server: a block in reach that the printer kept trying without result brings it down to just
     * inside that block's distance; long runs without such misses let it grow back towards the printer's range.
     */
    /** The planning reach last written to the build log. */
    private static double loggedReach = -1;

    private double planReach(double reach) {
        double max = reach - 0.25;
        double learned = ReachStats.reach(max);
        if (Math.abs(learned - loggedReach) >= 0.2) {
            BuildLog.line("LEARN", String.format(java.util.Locale.ROOT, "planning with reach %.2f (most %.2f): %s", learned, max, ReachStats.explain(max)));
            loggedReach = learned;
        }
        return learned;
    }

    /**
     * The spots of an area, this many blocks apart, row by row back and forth from the corner nearest to the
     * player (like mowing a lawn); the rows and columns also reach the far edge of the area.
     */
    private static List<BlockPos> lawnPlan(long key, Vec3 from, int y, int spacing) {
        int minX = ((int) key) * 16;
        int minZ = ((int) (key >> 32)) * 16;
        // As many rows and columns as the spacing needs, spread evenly over the 16 blocks (an extra column right next
        // to the last one at the area's edge was the 1-block hops in the logs)
        int n = Math.max(1, (int) Math.ceil(16.0 / spacing));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            offsets.add((int) Math.round((i + 0.5) * 16.0 / n - 0.5));
        }
        boolean westFirst = from.x < minX + 8;
        boolean northFirst = from.z < minZ + 8;
        List<BlockPos> spots = new ArrayList<>();
        for (int row = 0; row < n; row++) {
            int z = minZ + offsets.get(northFirst ? row : n - 1 - row);
            for (int col = 0; col < n; col++) {
                boolean east = westFirst == (row % 2 == 0);
                int x = minX + offsets.get(east ? col : n - 1 - col);
                spots.add(new BlockPos(x, y, z));
            }
        }
        return spots;
    }

    /**
     * How many of these blocks can be placed one after the other from one spot: the ones placeable now, then every
     * hopper whose target (the block it points into) is among those, and so on.
     */
    private static int chainClosure(Minecraft mc, WorldSchematic schematic, List<BlockPos> inReach, java.util.Set<BlockPos> doable) {
        java.util.Set<BlockPos> done = new java.util.HashSet<>();
        for (BlockPos pos : inReach) {
            if (doable.contains(pos)) {
                done.add(pos);
            }
        }
        boolean grew = true;
        for (int round = 0; grew && round < 16; round++) {
            grew = false;
            for (BlockPos pos : inReach) {
                if (done.contains(pos)) {
                    continue;
                }
                net.minecraft.world.level.block.state.BlockState target = schematic.getBlockState(pos);
                if (target.getBlock() instanceof net.minecraft.world.level.block.HopperBlock) {
                    net.minecraft.core.Direction facing = target.getValue(net.minecraft.world.level.block.HopperBlock.FACING);
                    BlockPos into = pos.relative(facing);
                    if (facing != net.minecraft.core.Direction.DOWN && done.contains(into)) {
                        done.add(pos);
                        grew = true;
                    }
                }
            }
        }
        return done.size();
    }

    @Nullable
    private Long area;

    /** True if one of these unfinished blocks can be placed now (stops at the first). */
    /**
     * In reach from there: the printer's range, but a bucket (water in the leaves) only as far as the player can
     * use items on blocks, which is less: the player has to come closer for those.
     */
    private static boolean reachable(Minecraft mc, WorldSchematic schematic, Vec3 eye, BlockPos pos, double reach) {
        double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
        double r = reach - 0.25;
        if (d > r * r) {
            return false;
        }
        double bucket = bucketRange(mc);
        return d <= bucket * bucket || BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.BUCKET;
    }

    /** How far a bucket reaches (as far as the player can use items, a little less to be safe). */
    private static double bucketRange(Minecraft mc) {
        return mc.player.blockInteractionRange() - 0.5;
    }

    /** Extra cost (blocks) for going back to an area behind in the usual order. */
    private static final double BEHIND_AREA_COST = 12;

    /** Bumped whenever the work list changes: the areas it's grouped into are worked out again then. */
    private int workVersion;
    private int areasVersion = -1;
    private final Map<Long, List<BlockPos>> areaWork = new HashMap<>();

    /** The work list by area (see areaKey), grouped again only when the work changed. */
    private Map<Long, List<BlockPos>> areaWork() {
        if (areasVersion != workVersion) {
            areasVersion = workVersion;
            areaWork.clear();
            for (BlockPos pos : work) {
                areaWork.computeIfAbsent(areaKey(pos), k -> new ArrayList<>()).add(pos);
            }
        }
        return areaWork;
    }

    /** Takes a block out of the work (until the next scan). */
    private void dropWork(BlockPos pos) {
        if (work.remove(pos)) {
            workVersion++;
        }
    }

    /** Placements counted so far, and when the count last went up (the area is still being worked on). */
    private int areaPlacedCount = -1;
    private long areaProgressTick;

    /** One of these blocks (or the block under or beside it) was clicked and the server hasn't answered yet. */
    private static boolean anyAnswerAwaited(List<BlockPos> blocks) {
        // A server that doesn't answer clicks: no telling, so it stays the 2 s as before
        if (!ServerAcks.works()) {
            return true;
        }
        for (BlockPos pos : blocks) {
            if (ServerAcks.isWaiting(pos) || ServerAcks.nextToWaiting(pos) || ServerAcks.unconfirmed(pos.below(), 3)) {
                return true;
            }
        }
        return false;
    }

    /** How long an area with work left is kept after the last block placed: 2 s, longer with a high ping. */
    private static int stayTicks() {
        return Math.max(40, 3 * ServerLag.pingTicks(Minecraft.getInstance()) + 20);
    }

    /** How far the player is from an area's square, plus the extra cost for going back against the usual order. */
    /** Areas in the leftovers' route when it was last written to the build log (-1: not yet). */
    private int routeLoggedFor = -1;

    /**
     * After the lanes (or on a layer done spot by spot): the areas with something placeable as one short route from
     * where the player is (nearest first, then shortened by turning legs around: 2-opt), and its first area. Area by
     * area in their usual order, with a cost for going back, the leftovers' flights were 2 to 3 times the straight way.
     */
    @Nullable
    private Long routeNext(Minecraft mc, WorldSchematic schematic, Map<Long, List<BlockPos>> workByArea,
                           java.util.Collection<Long> keys, Vec3 from) {
        List<Long> route = new ArrayList<>();
        Map<Long, double[]> at = new HashMap<>();
        int blocks = 0;
        for (Long key : keys) {
            List<BlockPos> list = workByArea.get(key);
            if (list == null) {
                continue;
            }
            // Where its placeable blocks are, on average
            double sx = 0;
            double sz = 0;
            int n = 0;
            for (BlockPos pos : list) {
                if (!spotExclude.contains(pos) && canDoNow(mc, schematic, pos)) {
                    sx += pos.getX() + 0.5;
                    sz += pos.getZ() + 0.5;
                    n++;
                }
            }
            if (n > 0) {
                at.put(key, new double[]{sx / n, sz / n});
                route.add(key);
                blocks += n;
            }
        }
        if (route.isEmpty()) {
            return null;
        }
        double[] start = {from.x, from.z};
        // Nearest first
        List<Long> left = new ArrayList<>(route);
        route.clear();
        double[] here = start;
        while (!left.isEmpty()) {
            Long best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Long key : left) {
                double d = planeDistance(here, at.get(key));
                if (d < bestDistance) {
                    bestDistance = d;
                    best = key;
                }
            }
            route.add(best);
            left.remove(best);
            here = at.get(best);
        }
        double nearestFirst = routeLength(route, at, start);
        // 2-opt on the open route from the player: legs a-b and c-d become a-c and b-d when that's shorter
        boolean better = true;
        for (int pass = 0; better && pass < 20; pass++) {
            better = false;
            for (int i = 0; i < route.size() - 1; i++) {
                for (int k = i + 1; k < route.size(); k++) {
                    double[] a = i == 0 ? start : at.get(route.get(i - 1));
                    double[] b = at.get(route.get(i));
                    double[] c = at.get(route.get(k));
                    double[] d = k + 1 < route.size() ? at.get(route.get(k + 1)) : null;
                    double before = planeDistance(a, b) + (d == null ? 0 : planeDistance(c, d));
                    double after = planeDistance(a, c) + (d == null ? 0 : planeDistance(b, d));
                    if (after < before - 0.01) {
                        java.util.Collections.reverse(route.subList(i, k + 1));
                        better = true;
                    }
                }
            }
        }
        if (route.size() != routeLoggedFor) {
            routeLoggedFor = route.size();
            BuildLog.line("LEFTOVER-PLAN", String.format(java.util.Locale.ROOT, "areas=%d blocks=%d route=%.0f nearestFirst=%.0f first=%s",
                    route.size(), blocks, routeLength(route, at, start), nearestFirst, areaName(route.getFirst()).replace(" ", "")));
        }
        return route.getFirst();
    }

    private static double planeDistance(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static double routeLength(List<Long> route, Map<Long, double[]> at, double[] start) {
        double length = 0;
        double[] here = start;
        for (Long key : route) {
            length += planeDistance(here, at.get(key));
            here = at.get(key);
        }
        return length;
    }

    private double areaCost(long key, Vec3 from) {
        double minX = ((int) key) * 16;
        double minZ = ((int) (key >> 32)) * 16;
        double dx = Math.max(0, Math.max(minX - from.x, from.x - (minX + 16)));
        double dz = Math.max(0, Math.max(minZ - from.z, from.z - (minZ + 16)));
        return Math.sqrt(dx * dx + dz * dz)
                + (lastAreaOrder != Long.MIN_VALUE && areaOrder(key) < lastAreaOrder ? BEHIND_AREA_COST : 0);
    }

    /** Parts of the layer that aren't loaded yet and that no flight got to (area -> until when they're left alone). */
    private final Map<Long, Long> unreachableAreas = new HashMap<>();

    private boolean areaUnreachable(long key) {
        Long until = unreachableAreas.get(key);
        if (until == null) {
            return false;
        }
        if (until < tick) {
            unreachableAreas.remove(key);
            return false;
        }
        return true;
    }

    private boolean anyDoable(Minecraft mc, WorldSchematic schematic, @Nullable List<BlockPos> positions, Set<BlockPos> exclude) {
        if (positions == null) {
            return false;
        }
        for (BlockPos pos : positions) {
            if (!exclude.contains(pos) && canDoNow(mc, schematic, pos)) {
                return true;
            }
        }
        return false;
    }

    private static long areaKey(BlockPos pos) {
        return ((long) (pos.getZ() >> 4) << 32) | ((pos.getX() >> 4) & 0xFFFFFFFFL);
    }

    /** Snake order of areas: area rows north to south, west to east on even rows and east to west on odd ones. */
    private static long areaOrder(long key) {
        int row = (int) (key >> 32);
        int column = (int) key;
        return (long) row * 100000L + (Math.floorMod(row, 2) == 0 ? column : -column) + 50000L;
    }

    private static String areaName(@Nullable Long key) {
        return key == null ? "-" : "x " + ((int) (long) key) * 16 + " z " + ((int) (key >> 32)) * 16;
    }


    /**
     * Fewest ticks to wait for the printer to pick something in reach before moving on: longer than the 20 ticks a
     * block that was just tried is held back while the server answers.
     */
    private static final int MIN_IDLE_TICKS = 14;
    /** Keep moving: leave for the next spot when this few placeable blocks are left in reach. */
    private static final int KEEP_MOVING_LEFT = 3;
    private boolean gliding;
    private long lastEarlyLeave = -100;

    /** Waits while the printer and breaker work; skips the blocks in reach if nothing happens for a while. */
    private int lastPlacedCount = -1;

    private void waitForWork(LocalPlayer player, List<BlockPos> inReach) {
        // Paused printer (slot change settling, lag, eating...) isn't "nothing happening"
        // Progress is a block placed here, not the printer looking busy: it can keep turning to a block it never
        // places (the player then stood still for a minute)
        boolean paused = AutoBuild.INSTANCE.breaker.isBusy() || AutoBuild.INSTANCE.shouldPausePrinter();
        int placed = AutoBuild.INSTANCE.stats.totalPlaced();
        if (paused || placed != lastPlacedCount || inReach.size() < lastInReach) {
            workTicksWithoutProgress = 0;
        } else {
            workTicksWithoutProgress++;
        }
        lastPlacedCount = placed;
        lastInReach = inReach.size();

        if (flying(player)) {
            // Slows down to a stop (no sudden stop) and holds still while the printer places
            steer(player, Vec3.ZERO);
        }

        // The printer looks for a block every turn: when it found nothing for a few of its turns (and nothing else is
        // busy), the blocks here aren't going to happen now. Move on instead of hovering.
        // (longer than a just-tried block is held back while the server answers: 2x ping + 6)
        int idleLimit = Math.max(Math.max(MIN_IDLE_TICKS, 4 * AutoBuild.INSTANCE.speed.ticksPerBlock() + 4),
                2 * ServerLag.pingTicks(Minecraft.getInstance()) + 10);
        int limit = Math.min(idleLimit, Configs.Movement.WORK_WAIT_SECONDS.getIntegerValue() * 20);
        if (workTicksWithoutProgress > limit) {
            PrinterAddon.debug("Nothing happened to {} blocks in reach for {} ticks, skipping them for now", inReach.size(), limit);
            WorldSchematic schematic = SchematicHelper.world();
            if (schematic != null) {
                Minecraft mc = Minecraft.getInstance();
                for (BlockPos pos : inReach.subList(0, Math.min(3, inReach.size()))) {
                    PrinterAddon.debug("  why: {}", Diagnose.explain(mc, schematic, pos));
                }
            }
            for (BlockPos pos : inReach) {
                skip(pos, "nothing happened to it in reach");
            }
            work.removeAll(inReach);
            workVersion++;
            workTicksWithoutProgress = 0;
            lastInReach = -1;
        }
    }

    @Nullable
    private BlockPos lastGoal;
    private int samePlans;
    private int lastPlanPlaced = -1;

    private void plan(Minecraft mc, LocalPlayer player, BlockPos goalBlock, MoveMode mode, double reach) {
        path = null;
        flyTarget = null;
        spotFlight = goalBlock.equals(lastBestSpot);
        stuckTicks = 0;
        lastDistance = Double.MAX_VALUE;

        // Got there over and over and still can't do it from there: leave it for later instead of looping. Only
        // when nothing was placed meanwhile: coming back to the same spot of the area plan is normal
        int placedNow = AutoBuild.INSTANCE.stats.totalPlaced();
        if (placedNow != lastPlanPlaced) {
            lastPlanPlaced = placedNow;
            samePlans = 0;
        }
        if (goalBlock.equals(lastGoal)) {
            if (++samePlans > 4) {
                // A hover spot to the side of the block it's for: skip that block
                BlockPos block = !work.contains(goalBlock) && spotFor != null ? spotFor : goalBlock;
                PrinterAddon.debug("Can't do {} from where it can stand, skipping it for now", block.toShortString());
                skip(block, "got to it over and over and still not done");
                dropWork(block);
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
            if (state != State.FLYING || moveStartTick < 0) {
                moveStartTick = tick;
                moveFrom = player.position();
                RunLog.tripStart(mc, errand != null ? "vault-or-chest" : sweepDone ? "leftover" : isNotLoaded(mc, goalBlock) ? "unloaded" : "spot");
            }
            BuildLog.line("MOVE", String.format(java.util.Locale.ROOT, "flying from %s to %.1f %.1f %.1f (%.1f blocks) for %s, area %s",
                    player.blockPosition().toShortString(), flyTarget.x, flyTarget.y, flyTarget.z,
                    Math.sqrt(player.position().distanceToSqr(flyTarget)), goalBlock.toShortString(), areaName(area)));
            state = State.FLYING;
            PrinterAddon.debug("Flying to {} for {}", flyTarget, goalBlock.toShortString());
            return;
        }

        PrinterAddon.debug("Can't get to {}, skipping it for now", goalBlock.toShortString());
        skip(goalBlock, "no way to get to it");
        dropWork(goalBlock);
        state = State.IDLE;
    }

    /**
     * The errand on foot: a route on the ground to a spot within reach of it, from where the player stands, or from
     * the ground right below when flying (it lands first). False when there's no way on foot.
     */
    private boolean walkToErrand(Minecraft mc, LocalPlayer player, double reach) {
        if ((player.onGround() || player.isInWater()) && !flying(player)) {
            List<BlockPos> route = findWalk(mc, player.blockPosition(), errand, reach);
            if (route != null) {
                path = route;
                pathIndex = 0;
                state = State.WALKING;
                PrinterAddon.debug("Walking to {} ({} steps)", errand.toShortString(), route.size());
                return true;
            }
            return false;
        }
        if (flying(player)) {
            BlockPos landing = landingSpot(mc, player.blockPosition());
            if (landing != null && findWalk(mc, landing, errand, reach) != null) {
                PrinterAddon.debug("Landing at {} to walk to {}", landing.toShortString(), errand.toShortString());
                setFlying(player, false);
                state = State.IDLE;
                return true;
            }
        }
        return false;
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

    /**
     * Where to hover for a block: right above the layers being built (so nothing is ever over the player's head
     * and the reach goes furthest sideways: with range 5, about 4.5 blocks at hover height 1 against 3.9 at 2),
     * or above the block itself if it's lower (a block placed later).
     */
    private Vec3 hoverPoint(Minecraft mc, BlockPos goalBlock) {
        // Worked out once per scan (the same spot is asked for many times while choosing where to go)
        return hoverCache.computeIfAbsent(goalBlock.immutable(), g -> hoverPointNow(mc, g));
    }

    private final Map<BlockPos, Vec3> hoverCache = new HashMap<>();

    private Vec3 hoverPointNow(Minecraft mc, BlockPos goalBlock) {
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
        int hoverY = baseY + Configs.Movement.HOVER_HEIGHT.getIntegerValue();
        // A little higher over a cactus (the feet a hair in its block hurt)
        double lift = layers.isActive() ? layers.hoverLift() : 0;
        BlockPos pos = new BlockPos(goalBlock.getX() + offsets[column][0], hoverY, goalBlock.getZ() + offsets[column][1]);
        if (canHover(mc, pos, lift)) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + lift, pos.getZ() + 0.5);
        }
        // Something is in the way there (a building, a wall): the nearest free spot around it, low first, from
        // where the block is still in reach. Never a spot inside blocks
        double r = reach(mc.player) - 0.25;
        double eyeHeight = mc.player.getEyeHeight();
        Vec3 goal = Vec3.atCenterOf(goalBlock);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos spot = new BlockPos(goalBlock.getX() + dx, hoverY + dy, goalBlock.getZ() + dz);
                    Vec3 eye = new Vec3(spot.getX() + 0.5, spot.getY() + eyeHeight, spot.getZ() + 0.5);
                    if (eye.distanceToSqr(goal) > r * r || !canHover(mc, spot, lift)) {
                        continue;
                    }
                    double score = dy * 2 + Math.abs(dx) + Math.abs(dz);
                    if (score < bestScore) {
                        bestScore = score;
                        best = spot;
                    }
                }
            }
        }
        if (best == null) {
            // Nowhere to stand: the flight there finds no way and the block is left for later (see fly)
            return new Vec3(pos.getX() + 0.5, pos.getY() + lift, pos.getZ() + 0.5);
        }
        return new Vec3(best.getX() + 0.5, best.getY() + lift, best.getZ() + 0.5);
    }

    /**
     * The turns of a way at the target's own height get its lift as well (a little over the layer over a cactus, see
     * LayerManager#hoverLift): else the way there runs that much lower, the feet in the cactus's block.
     */
    private static List<Vec3> withLift(List<Vec3> way, Vec3 target) {
        int goalY = (int) Math.floor(target.y + 0.01);
        double lift = target.y - goalY;
        if (lift < 0.05) {
            return way;
        }
        List<Vec3> lifted = new ArrayList<>(way.size());
        for (Vec3 point : way) {
            lifted.add(Math.abs(point.y - goalY) < 1.0e-6 ? new Vec3(point.x, target.y, point.z) : point);
        }
        return lifted;
    }

    /**
     * The player fits there (2 blocks of free space), isn't in the way of blocks still to be placed, and keeps the
     * safety room from a cactus (or anything else that hurts) at the feet this much over the block: a spot right
     * beside a grown cactus column had the player rising and sinking there for 25 s, "stuck" 8 times.
     */
    private boolean canHover(Minecraft mc, BlockPos feet, double lift) {
        return FlyPath.fits(mc.level, feet) && !reserved.contains(feet) && !reserved.contains(feet.above())
                && harmNear(mc.level, new Vec3(feet.getX() + 0.5, feet.getY() + lift, feet.getZ() + 0.5)) == null;
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
                    skip(target, "stuck walking");
                    dropWork(target);
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
        // Never to a point the player can't get to past the world border (the island's edge)
        flyTarget = insideBorder(player, flyTarget);
        if (!ensureFlying(player)) {
            return;
        }

        // The way there, around buildings and anything else in the way (worked out once per target)
        boolean targetLoaded = mc.level.isLoaded(BlockPos.containing(flyTarget));
        if (flyPath == null || !flyTarget.equals(flyPathFor) || (flyPathBlind && targetLoaded)) {
            long started = Perf.start();
            boolean found = planFlight(mc, player, targetLoaded);
            Perf.end("movement: flight path", started);
            if (!found) {
                return;
            }
            flyPath = withLift(flyPath, flyTarget);
        }
        // Flying blind towards a part that isn't loaded: something coming up ahead (a building) is flown around, not
        // climbed (checked twice a second, the way around is worked out with what's loaded by now)
        if (flyPathBlind && !flyExact && tick % 10 == 0 && tick - blindDetourFailed > 40
                && !FlyPath.clearAsFarAsKnown(mc.level, player.position(), ahead(player.position(), flyTarget, 6))) {
            flyPath = null;
            return;
        }
        // Along a lane over parts not loaded yet: the way again now and then, with what has loaded since
        if (flyPathBlind && flyExact && tick % 20 == 0) {
            flyPath = null;
            return;
        }
        Vec3 waypoint = flyPath.get(Math.min(flyIndex, flyPath.size() - 1));
        // On to the next turn once it's reached, or as soon as the straight line there is free (never cut a corner)
        double reached = Math.max(0.15, velocity.length());
        if (flyIndex < flyPath.size() - 1 && (waypoint.distanceToSqr(player.position()) < reached * reached
                || (!flyExact && tick % 4 == 0 && FlyPath.shortcutOk(player.position(), flyPath.get(flyIndex + 1))
                && FlyPath.clear(mc.level, player.position(), flyPath.get(flyIndex + 1))))) {
            flyIndex++;
            waypoint = flyPath.get(flyIndex);
            lastDistance = Double.MAX_VALUE;
        }

        Vec3 to = waypoint.subtract(player.position());
        double distance = to.length();

        if (distance < 0.2 && flyIndex >= flyPath.size() - 1) {
            if (flyLeg) {
                // The end of one part of a long way: the next part is worked out from here
                flyPath = null;
                return;
            }
            // Arrived, slow already (braked on the way): no sudden stop, the next tick goes on from this speed
            state = State.IDLE;
            logArrival(mc, player);
            return;
        }

        // Speed: full speed on the way, gliding while the printer places on the way (slower the more there is),
        // braking for the end and for turns, so the player never stops, starts or turns suddenly
        double cruise = gliding ? glideSpeed : Configs.Speed.flySpeed();
        double toEnd = distance;
        for (int i = flyIndex; i < flyPath.size() - 1; i++) {
            toEnd += flyPath.get(i).distanceTo(flyPath.get(i + 1));
        }
        double speed = Math.min(cruise, Math.sqrt(2 * BRAKE * toEnd));
        if (flyIndex < flyPath.size() - 1 || flyLeg) {
            // Straight up or down from that turn (over a wall, right in front of it): nearly stopped there, so the way
            // along doesn't carry on into the wall (a flight over a building bumped into it 10 times on the way up)
            Vec3 after = flyIndex < flyPath.size() - 1 ? flyPath.get(flyIndex + 1) : null;
            boolean upOrDown = after != null && Math.sqrt((after.x - waypoint.x) * (after.x - waypoint.x)
                    + (after.z - waypoint.z) * (after.z - waypoint.z)) < 0.3 && Math.abs(after.y - waypoint.y) > 0.5;
            double turn = upOrDown ? 0.05 : TURN_SPEED;
            speed = Math.min(speed, Math.sqrt(turn * turn + 2 * BRAKE * distance));
        }
        speed = Math.min(speed, distance);
        // Holding still on purpose (a block behind still to place): not stuck. Never for good, though: nothing placed
        // for 3 s and nothing else going on (no breaking, no bucket, no lag), it goes on slowly
        if (speed < 0.02) {
            if (tick - lastPlacementTick > HOLD_GIVE_UP_TICKS && !AutoBuild.INSTANCE.breaker.isBusy()
                    && !AutoBuild.INSTANCE.fluids.isBusy() && !ServerLag.shouldWait(mc)) {
                speed = Math.min(GLIDE_MIN, distance);
                if (tick - lastHoldNote > 20 * 10) {
                    lastHoldNote = tick;
                    BuildLog.line("MOVE", "held still for 3 s with nothing placed (" + describe() + "): going on slowly to "
                            + BlockPos.containing(flyTarget).toShortString());
                }
            } else {
                stuckTicks = 0;
                lastDistance = distance;
            }
        }

        if (distance < lastDistance - 0.01) {
            lastDistance = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            // Something in the way after all (a block changed): find the way again first, then go higher. Stuck right
            // where it was stuck a moment ago: no new way from there, the block is left for later at once (the same spot
            // was "stuck" 8 times, 2.3 s each, with the way worked out again each time)
            BlockPos stuckAt = player.blockPosition();
            boolean again = stuckAt.equals(lastStuckAt) && tick - lastStuckTick < 20 * 30;
            lastStuckAt = stuckAt;
            lastStuckTick = tick;
            PrinterAddon.debug("Stuck flying at {}, finding the way again", stuckAt.toShortString());
            if (replans < 2 && !again) {
                flyPath = null;
                replans++;
                stuckTicks = 0;
                lastDistance = Double.MAX_VALUE;
                return;
            }
            // Still stuck: leave that block for later (never climb blindly); a lane: the next one
            if (laneFlight()) {
                laneBlocked(player, "stuck at " + player.blockPosition().toShortString());
                return;
            }
            BlockPos block = spotFor != null ? spotFor : target;
            BuildLog.line("MOVE", "stuck at " + player.blockPosition().toShortString() + (again ? " again" : "") + " on the way to "
                    + BlockPos.containing(flyTarget).toShortString() + ": leaving " + (block != null ? block.toShortString() : "it") + " for later");
            stuckTicks = 0;
            lastDistance = Double.MAX_VALUE;
            if (block != null) {
                skip(block, "stuck flying");
                dropWork(block);
            }
            flyPath = null;
            flyTarget = null;
            halt(player);
            state = State.IDLE;
            return;
        }

        Vec3 motion = distance > 1.0e-6 ? to.scale(speed / distance) : Vec3.ZERO;
        // A little off the waypoint's height on a long level way (sunk a bit coming down to it): back to that height at
        // once, not bit by bit over the whole way. A lane went 0.3 under the hover height for its whole length, the
        // feet in the layer's row
        double level = Math.sqrt(to.x * to.x + to.z * to.z);
        if (Math.abs(to.y) < 0.5 && level > 1) {
            motion = new Vec3(motion.x, Math.max(-0.1, Math.min(0.1, to.y * 0.4)), motion.z);
        }
        // Along a lane: on its line sideways too, at once (a lane laid right beside a wall drifted 0.2 off its line,
        // grazed the wall and went 6 up it)
        boolean onLine = laneFlight() && !laneApproach;
        if (onLine) {
            double off = lanesAlongX ? laneEnd.getZ() + 0.5 - player.getZ() : laneEnd.getX() + 0.5 - player.getX();
            double side = Math.max(-0.1, Math.min(0.1, off * 0.4));
            motion = lanesAlongX ? new Vec3(motion.x, motion.y, motion.z + side) : new Vec3(motion.x + side, motion.y, motion.z);
        }
        // (A way found around something on the lane may step aside on purpose: only the lane's own straight way is held)
        onLine &= flyExact;
        // Up over what's in the way; not up along the world border (the island's edge), which is no wall to get over
        if (player.horizontalCollision && !atBorder(player)) {
            motion = new Vec3(motion.x, Math.max(motion.y, 0.3), motion.z);
        }
        steer(player, motion);
        if (onLine) {
            // The sideways part as wanted straight away, not eased in with the speed: coming onto lane 8 from the side,
            // the speed it had sideways took the player 0.4 off the lane's line, into the cactus of the row beside it
            velocity = lanesAlongX ? new Vec3(velocity.x, velocity.y, motion.z) : new Vec3(motion.x, velocity.y, velocity.z);
            player.setDeltaMovement(velocity);
        }
    }

    /** The point moved in where the player's box (0.6 wide) would reach past the world border. */
    private static Vec3 insideBorder(LocalPlayer player, Vec3 point) {
        net.minecraft.world.level.border.WorldBorder border = player.level().getWorldBorder();
        double margin = 0.31;
        if (border.getMaxX() - border.getMinX() < 2 * margin || border.getMaxZ() - border.getMinZ() < 2 * margin) {
            return point;
        }
        double x = Math.max(border.getMinX() + margin, Math.min(border.getMaxX() - margin, point.x));
        double z = Math.max(border.getMinZ() + margin, Math.min(border.getMaxZ() - margin, point.z));
        return x == point.x && z == point.z ? point : new Vec3(x, point.y, z);
    }

    /** The player's box touches the world border. */
    private static boolean atBorder(LocalPlayer player) {
        net.minecraft.world.level.border.WorldBorder border = player.level().getWorldBorder();
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        return box.minX - border.getMinX() < 0.05 || border.getMaxX() - box.maxX < 0.05
                || box.minZ - border.getMinZ() < 0.05 || border.getMaxZ() - box.maxZ < 0.05;
    }

    /** Flights longer than this are flown in parts when the whole way can't be found at once. */
    private static final double LEG_LENGTH = 40;
    /** A way straight over what's in the way is taken when it's at most this much longer than the straight line. */
    private static final double OVER_EXTRA = 30;

    /** How far it is along these points from here. */
    private static double wayLength(Vec3 from, List<Vec3> way) {
        double length = 0;
        Vec3 at = from;
        for (Vec3 point : way) {
            length += at.distanceTo(point);
            at = point;
        }
        return length;
    }
    /** Flying towards a part that isn't loaded: how far ahead the line must be free (else around what's there). */
    private static final double BLIND_LOOK_AHEAD = 12;
    private long blindDetourFailed = -1000;

    /** The point this far from {@code from} on the line to {@code to} (or {@code to}, if that's closer). */
    private static Vec3 ahead(Vec3 from, Vec3 to, double distance) {
        Vec3 d = to.subtract(from);
        double length = d.length();
        return length <= distance ? to : from.add(d.scale(distance / length));
    }

    /**
     * What's solid in the 2 blocks above the block and around it (5 x 5), where the player would hover to place it:
     * for the log, to see what keeps a block out of reach (a roof, leaves, the player's building...).
     */
    private static String blocking(Minecraft mc, BlockPos block) {
        java.util.Map<String, Integer> found = new java.util.TreeMap<>();
        int solid = 0;
        int all = 0;
        for (int dy = 1; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = block.offset(dx, dy, dz);
                    all++;
                    if (!PathFinder.isPassable(mc.level, pos)) {
                        solid++;
                        found.merge(mc.level.isLoaded(pos) ? mc.level.getBlockState(pos).getBlock().getName().getString() : "not loaded", 1, Integer::sum);
                    }
                }
            }
        }
        return solid == 0 ? "nothing (" + all + " spots free)" : solid + " of " + all + " spots taken: " + found;
    }

    /**
     * Works out the way to flyTarget: straight when nothing is in the way, else around it (FlyPath). When the spot
     * itself can't be got to, any other spot from where the block is in the printer's reach; a long way part by part.
     * Returns false (and leaves the block for later) when there's no way at all.
     */
    private boolean planFlight(Minecraft mc, LocalPlayer player, boolean targetLoaded) {
        Vec3 from = player.position();
        flyPathFor = flyTarget;
        flyIndex = 0;
        flyPathBlind = false;
        flyLeg = false;
        flyExact = false;
        if (laneFlight() && !laneApproach) {
            // Along a lane: in its own line at the hover height, straight up right before what's in the way and
            // straight down right after it, also between two walls (never around it, never up a long ramp: a lane
            // flew a 30 block ramp up over the farm's walls and stayed up over the whole middle). Parts not loaded
            // yet count as free until they are
            int hoverY = (int) Math.floor(laneHover(target).y);
            List<Vec3> lane = FlyPath.lineWay(mc.level, from, flyTarget, hoverY, !targetLoaded);
            if (lane != null) {
                flyPath = lane;
                flyExact = true;
                flyPathBlind = !targetLoaded;
                if (lane.size() > 1 && targetLoaded) {
                    BuildLog.line("MOVE", "lane way: " + lane.size() + " turns, at most "
                            + (int) Math.floor(lane.stream().mapToDouble(p -> p.y).max().orElse(hoverY)) + " high (hover height "
                            + hoverY + ")");
                }
                return true;
            }
        }
        if (!targetLoaded) {
            // Not loaded yet (far away): nothing is known there, so towards it in a straight line; the way around
            // anything is found as soon as it has loaded. Something in the way on the part that is loaded (a
            // building): around it first
            if (!FlyPath.clearAsFarAsKnown(mc.level, from, ahead(from, flyTarget, BLIND_LOOK_AHEAD))) {
                List<Vec3> leg = FlyPath.leg(mc.level, from, flyTarget, BLIND_LOOK_AHEAD + 8, wayLimit(player));
                if (leg != null) {
                    flyPath = leg;
                    flyLeg = true;
                    BuildLog.line("MOVE", "something in the way towards " + BlockPos.containing(flyTarget).toShortString()
                            + " (not loaded yet): " + (laneFlight() && !laneApproach ? "over" : "around") + " it first, to "
                            + BlockPos.containing(leg.getLast()).toShortString()
                            + " (" + leg.size() + " turns)");
                    return true;
                }
                // No way around found with what's loaded: straight on for now, looked at again in a while
                blindDetourFailed = tick;
            }
            flyPath = List.of(flyTarget);
            flyPathBlind = true;
            BuildLog.line("MOVE", "flying straight towards " + BlockPos.containing(flyTarget).toShortString()
                    + " (not loaded yet; the way is worked out once it is)");
            return true;
        }
        if (FlyPath.clear(mc.level, from, flyTarget)) {
            flyPath = List.of(flyTarget);
            return true;
        }
        // Over what's in the way, along the straight line, when that's not much longer: up right before it and down
        // right after it, so the printer can go on placing until then (else around it, below)
        if (!laneFlight()) {
            int baseY = (int) Math.floor(Math.max(from.y, flyTarget.y) + 0.01);
            List<Vec3> over = FlyPath.lineWay(mc.level, from, flyTarget, baseY, false);
            if (over != null && wayLength(from, over) <= from.distanceTo(flyTarget) + OVER_EXTRA) {
                flyPath = over;
                flyExact = true;
                BuildLog.line("MOVE", "way over what's in the way: " + over.size() + " turns, at most "
                        + (int) Math.floor(over.stream().mapToDouble(p -> p.y).max().orElse(baseY)) + " high");
                return true;
            }
        }
        List<Vec3> way = FlyPath.find(mc.level, from, flyTarget, wayLimit(player));
        if (way == null && laneFlight()) {
            // A lane end that wasn't loaded when the lane was planned, and the player doesn't fit there: pulled in
            BlockPos end = target;
            BlockPos pulled = freeLaneEnd(player, end, end.equals(laneEnd) ? laneStart : laneEnd);
            if (!pulled.equals(end)) {
                if (end.equals(laneEnd)) {
                    laneEnd = pulled;
                } else {
                    laneStart = pulled;
                }
                target = pulled;
                flyTarget = laneHover(pulled);
                flyPathFor = flyTarget;
                way = FlyPath.clear(mc.level, from, flyTarget) ? List.of(flyTarget) : FlyPath.find(mc.level, from, flyTarget, wayLimit(player));
            }
        }
        if (way != null) {
            flyPath = way;
            BuildLog.line("MOVE", (laneFlight() && !laneApproach ? "way over what's in the way on the lane: " : "way around what's in the way: ")
                    + way.size() + " turns");
            PrinterAddon.debug("Flight path from {} to {}: {}", from, flyTarget, way);
            return true;
        }
        // The planned spot can't be got to (something is in the way there, or it's walled in): any spot from where the
        // block is in the printer's reach will do, also one to the side or lower down
        BlockPos block = spotFor != null ? spotFor : target;
        boolean loose = target != null && isNotLoaded(mc, target);
        boolean lane = laneFlight();
        // Not for a lane: its ends aren't blocks to place, and a spot "in reach" of one was down in the layer
        if (block != null && !loose && !lane) {
            double planR = planReach(reach(player));
            List<Vec3> near = FlyPath.findNear(mc.level, from, block, planR, player.getEyeHeight(), reserved::contains);
            if (near != null) {
                Vec3 spot = near.getLast();
                BuildLog.line("MOVE", String.format(java.util.Locale.ROOT, "the planned spot %s can't be got to: flying to %.1f %.1f %.1f instead, %.2f from %s (%d turns)",
                        BlockPos.containing(flyTarget).toShortString(), spot.x, spot.y, spot.z,
                        Math.sqrt(spot.add(0, player.getEyeHeight(), 0).distanceToSqr(Vec3.atCenterOf(block))), block.toShortString(), near.size()));
                flyPath = near;
                flyTarget = spot;
                flyPathFor = spot;
                return true;
            }
        }
        // A long way that couldn't be found in one go: the first part of it, the rest from there
        if (from.distanceTo(flyTarget) > LEG_LENGTH) {
            List<Vec3> leg = FlyPath.leg(mc.level, from, flyTarget, LEG_LENGTH, wayLimit(player));
            if (leg != null) {
                flyPath = leg;
                flyLeg = true;
                BuildLog.line("MOVE", String.format(java.util.Locale.ROOT, "long way to %s (%.0f blocks): flying the first part to %s (%d turns)",
                        BlockPos.containing(flyTarget).toShortString(), from.distanceTo(flyTarget),
                        BlockPos.containing(leg.getLast()).toShortString(), leg.size()));
                return true;
            }
        }
        if (lane) {
            laneBlocked(player, "no way to " + BlockPos.containing(flyTarget).toShortString() + " from " + player.blockPosition().toShortString());
            return false;
        }
        noWay(mc, player, block, loose);
        return false;
    }

    /**
     * No flight gets there. A part of the layer that isn't loaded yet: that whole area waits a while (instead of one
     * block after the other, each with a full search: the start of a log had 33 of those in a row). A block: left for
     * later (see skip: after the 4th time it's left out of this layer, so the layer goes on).
     */
    private void noWay(Minecraft mc, LocalPlayer player, @Nullable BlockPos block, boolean loose) {
        PrinterAddon.debug("No flight path from {} to {}", player.position(), flyTarget);
        BuildLog.line("MOVE", "no way to " + BlockPos.containing(flyTarget).toShortString() + " or any spot in reach of "
                + (block != null ? block.toShortString() : "it") + " from " + player.blockPosition().toShortString()
                + (block != null && !loose ? " | in the way above it: " + blocking(mc, block) : ""));
        if (loose && target != null) {
            unreachableAreas.put(areaKey(target), tick + SKIP_TICKS);
            BuildLog.line("SKIP", "area " + areaName(areaKey(target)) + " for 30 s: no flight path to the part that isn't loaded yet ("
                    + target.toShortString() + ")");
        } else if (block != null) {
            // Left out of this layer at once (not four tries 30 s apart, each a long flight there: a stem built over
            // by the layer above was flown to three times); tried again at the end of the build
            skipCounts.put(block.immutable(), Math.max(skipCounts.getOrDefault(block, 0), MAX_SKIPS - 1));
            skip(block, "no flight path to any spot in reach of it");
            dropWork(block);
            // The blocks right around it are most likely in the same closed-in spot (a room of a building): left for
            // later too, without each costing a search (they're tried one by one when nothing else is left)
            List<BlockPos> around = new ArrayList<>();
            for (BlockPos pos : work) {
                if (Math.abs(pos.getX() - block.getX()) <= 2 && Math.abs(pos.getY() - block.getY()) <= 2
                        && Math.abs(pos.getZ() - block.getZ()) <= 2) {
                    around.add(pos);
                }
            }
            for (BlockPos pos : around) {
                skipped.put(pos, tick + SKIP_TICKS);
                dropWork(pos);
            }
            if (!around.isEmpty()) {
                BuildLog.line("SKIP", around.size() + " blocks around " + block.toShortString() + " for 30 s too (most likely just as closed in)");
            }
        }
        flyPath = null;
        flyTarget = null;
        halt(player);
        state = State.IDLE;
    }

    /** True for a spot in a part of the layer that isn't loaded yet (only gone to so it loads). */
    private static boolean isNotLoaded(Minecraft mc, BlockPos pos) {
        WorldSchematic schematic = SchematicHelper.world();
        return schematic == null || BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.UNLOADED;
    }

    private void logArrival(Minecraft mc, LocalPlayer player) {
        // Arriving counts as work here: the grace for blocks that become placeable a moment later starts now
        lastWorkTick = tick;
        RunLog.tripEnd(mc, "arrived");
        if (moveStartTick < 0 || !BuildLog.isOpen()) {
            moveStartTick = -1;
            return;
        }
        WorldSchematic schematic = SchematicHelper.world();
        int placeable = 0;
        if (schematic != null) {
            double reach = reach(player);
            Vec3 eye = player.getEyePosition();
            for (BlockPos pos : work) {
                if (reachable(mc, schematic, eye, pos, reach) && canDoNow(mc, schematic, pos)) {
                    placeable++;
                }
            }
        }
        BuildLog.line("MOVE", String.format(java.util.Locale.ROOT, "arrived at %s after %.1f s, %.1f blocks from %s; %d placeable in reach",
                player.blockPosition().toShortString(), (tick - moveStartTick) / 20.0,
                Math.sqrt(player.position().distanceToSqr(moveFrom)), BlockPos.containing(moveFrom).toShortString(), placeable));
        moveStartTick = -1;
    }

    /** Turns flying on (sending the fly command once if needed). Returns false while waiting for it. */
    private boolean ensureFlying(LocalPlayer player) {
        // Standing on the layer for a moment to break a block faster, or hopping there (see BlockBreaker#isStanding):
        // the breaker moves the player meanwhile; flying on once it's done
        if (AutoBuild.INSTANCE.breaker.isStanding()) {
            return false;
        }
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

    /** At the start of auto build: up into the air right away when flying is allowed (or after /fly). */
    public void takeOff(LocalPlayer player) {
        if (Configs.Movement.mode().canFly()) {
            ensureFlying(player);
        }
    }

    private static void setFlying(LocalPlayer player, boolean on) {
        player.getAbilities().flying = on;
        player.onUpdateAbilities();
    }

    private static boolean flying(LocalPlayer player) {
        return player.getAbilities().flying;
    }

    /** Stops at once (for chests, the shop, a change of plan): the rest of the time the mover slows down smoothly. */
    private void halt(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0, flying(player) ? 0 : motion.y, 0);
        velocity = Vec3.ZERO;
    }

    private static double reach(LocalPlayer player) {
        // The printer's own range (the player's setting): that's where it places
        return me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
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

    private int scannedLayer = Integer.MIN_VALUE;
    /** The top of the layers the last scan was for, and how many scans there were (lanes are planned after one). */
    private int scannedTop = Integer.MIN_VALUE;
    private int scans;
    /** Lanes not planned yet: tried again after this scan (few blocks in the work while many wait). */
    private int sweepRetryAfterScan = -1;

    /** Finds the unfinished positions in the current layer range. */
    private void scan(Minecraft mc, WorldSchematic schematic) {
        work.clear();
        workVersion++;
        hoverCache.clear();
        reserved.clear();
        unloaded.clear();
        unloadedAreas.clear();
        waitingForItems = 0;
        LayerManager layers = AutoBuild.INSTANCE.layers;
        // Only the layer auto build is on (also if Litematica's layer view was changed by hand meanwhile)
        boolean oneLayer = layers.isActive() && !layers.isFinishingDeferred();
        if (layers.getLayer() != scannedLayer) {
            // A new layer: what was left for later on the last one says nothing about this one
            scannedLayer = layers.getLayer();
            skipCounts.clear();
            unreachableAreas.clear();
        }
        scannedTop = layers.getLayerTop();
        scans++;

        LocalPlayer player = mc.player;
        boolean creative = player.getAbilities().instabuild;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            // Only the levels being built: a tall schematic was gone through whole every second (3.7 million spots
            // of a 239 high cactus tower for its 2 levels: up to 105 ms, a stutter in the flight)
            int yFrom = oneLayer ? Math.max(area.minY(), layers.getLayer()) : area.minY();
            int yTo = oneLayer ? Math.min(area.maxY(), layers.getLayerTop()) : area.maxY();
            for (int y = yFrom; y <= yTo; y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (oneLayer ? y < layers.getLayer() || y > layers.getLayerTop()
                                : !DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.UNLOADED) {
                            // Seen finished before it unloaded: nothing to fly there for (see LayerManager#seenFinished)
                            if (oneLayer && layers.seenFinished(pos)) {
                                continue;
                            }
                            if (unloaded.size() < 512) {
                                unloaded.add(pos.immutable());
                            }
                            unloadedAreas.putIfAbsent(areaKey(pos), pos.immutable());
                            continue;
                        }
                        // The player's own building where the schematic wants something else: not ours to change
                        if (result.needsBreaking() && AutoBuild.INSTANCE.isConflict(pos, result, mc.level.getBlockState(pos))) {
                            continue;
                        }
                        // A cactus that grew up, or the flower on it, that drops by itself once the sand beside its
                        // column goes in: nothing to fly to or wait for (the spot is placeable after)
                        if (result != BlockCheck.Result.MISSING && BlockBreaker.fallsByItself(mc.level, schematic, pos)) {
                            continue;
                        }
                        if (result == BlockCheck.Result.MISSING && mc.level.getBlockState(pos).isAir()) {
                            AutoBuild.INSTANCE.markEmpty(pos);
                        }
                        // Where the schematic has air: nothing to do, unless it's one of the "always break" blocks (a
                        // cactus flower on the farm), which is flown to like a wrong block
                        if (result == BlockCheck.Result.OK || result == BlockCheck.Result.UNLOADED
                                || result == BlockCheck.Result.EXTRA && !BlockBreaker.breakAnyway(mc.level, schematic, pos)) {
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
                        // Left out of this layer (no spot in reach of it can be got to): listed at the end
                        if (protectedHere || skipped.containsKey(immutable) || AutoBuild.INSTANCE.couldNotPlace.contains(immutable)) {
                            continue;
                        }
                        if (result == BlockCheck.Result.MISSING && !creative && !hasItemFor(player, target)) {
                            waitingForItems++;
                            continue;
                        }
                        if (result == BlockCheck.Result.BUCKET && !Fluids.canDo(mc, schematic, immutable, target)) {
                            continue;
                        }
                        // Blocks that wait (for the water next to them, for what they stand on) stay in the work:
                        // they count as unfinished in reach, so the player waits a moment for them instead of
                        // flying off and coming back; canDoNow says when they can be placed
                        work.add(immutable);
                    }
                }
            }
        }

        addReadyDeferred(mc, schematic, creative);
        BuildLog.line("SCAN", "work " + work.size() + " | waiting for items " + waitingForItems + " | not loaded "
                + unloaded.size() + " in " + unloadedAreas.size() + " areas | left for later " + skipped.size()
                + " | conflicts " + AutoBuild.INSTANCE.conflicts.size() + " | player at " + player.blockPosition().toShortString());
    }

    /** Deferred blocks (from finished layers) that can be placed now. */
    private void addReadyDeferred(Minecraft mc, WorldSchematic schematic, boolean creative) {
        for (BlockPos pos : AutoBuild.INSTANCE.deferred) {
            // Left out for good (no way to it): only at the very end again, not every 30 s
            if (skipped.containsKey(pos) || work.contains(pos) || AutoBuild.INSTANCE.couldNotPlace.contains(pos)) {
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
