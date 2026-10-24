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

    /** A spot to go to before building on (a chest to take items from). */
    @Nullable
    private BlockPos errand;
    private boolean errandReached;

    /** Go within reach of this spot (null to stop). Returns true once it's within reach. */
    public boolean goTo(@Nullable BlockPos pos) {
        if (pos == null || !pos.equals(errand)) {
            errand = pos == null ? null : pos.immutable();
            errandReached = false;
            if (state == State.FLYING || state == State.WALKING) {
                state = State.IDLE;
            }
        }
        return errand != null && errandReached;
    }

    /** True for a block the printer was given time for in reach, but nothing happened. */
    public boolean wasSkipped(BlockPos pos) {
        return skipped.containsKey(pos);
    }

    /** What the player is doing now, for the build stats. */
    /**
     * True while flying or walking to the next spot, or still slowing down: the printer waits then, a block placed
     * from a moving player is placed from where the server doesn't have the player yet (wrong facing, wrong spot).
     */
    public boolean isTravelling(LocalPlayer player) {
        if (gliding) {
            return false;
        }
        if (state == State.FLYING || state == State.WALKING) {
            return true;
        }
        Vec3 motion = player.getDeltaMovement();
        return motion.x * motion.x + motion.z * motion.z > 0.03 * 0.03 || Math.abs(motion.y) > 0.08;
    }

    /** Mover state for the build log's snapshots. */
    public String details() {
        return state + (target != null ? " target " + target.toShortString() : "") + " area " + areaName(area)
                + " work " + work.size() + (unloadedAreas.isEmpty() ? "" : " unloaded areas " + unloadedAreas.size());
    }

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
        sweepLayer = Integer.MIN_VALUE;
        unloadable.clear();
        nearUnloadedSince = -1;
        errand = null;
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
            long started = Perf.start();
            scan(mc, schematic);
            Perf.end("movement: scan", started);
        }

        double reach = reach(player);
        BlockPos feet = player.blockPosition();

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
            if (state != State.FLYING && state != State.WALKING) {
                target = errand;
                path = null;
                stuckTicks = 0;
                lastDistance = Double.MAX_VALUE;
                if (ensureFlying(player)) {
                    flyTarget = new Vec3(errand.getX() + 0.5, errand.getY() + 1.2, errand.getZ() + 0.5);
                    state = State.FLYING;
                }
            }
            move(mc, player, mode);
            return;
        }

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
            if (reachable(mc, schematic, eye, pos, reach) && canDoNow(mc, schematic, pos)) {
                inReach.add(pos);
            }
        }

        // On the way to a chosen spot, keep going (the printer places what's in reach meanwhile): slowly while there
        // is something to place in reach, so the printer gets to all of it on the way; at full speed otherwise
        if (state == State.FLYING && flyTarget != null && mode.canFly()) {
            gliding = !inReach.isEmpty() && Configs.Movement.KEEP_MOVING.getBooleanValue();
            move(mc, player, mode);
            return;
        }
        gliding = false;

        // All that's left here is right under the player, where the printer never places: step aside to it now
        // instead of waiting and leaving it behind
        if (!inReach.isEmpty() && PrinterBridge.isIdle() && !AutoBuild.INSTANCE.breaker.isBusy()
                && inReach.stream().allMatch(p -> p.getX() == feet.getX() && p.getZ() == feet.getZ() && p.getY() < feet.getY())) {
            BlockPos under = inReach.getFirst();
            if (state != State.FLYING && state != State.WALKING) {
                PrinterAddon.debug("Only {} under the player is left here: moving aside to place it", under.toShortString());
                target = under;
                plan(mc, player, under, mode, reach);
            }
            move(mc, player, mode);
            return;
        }

        if (!inReach.isEmpty() || !PrinterBridge.isIdle()) {
            lastWorkTick = tick;
        }
        // Just placed something and an unfinished block of a chain is still in reach: its next block only counts as
        // placeable once the server has the last one. Stay for two printer turns for it. With nothing unfinished
        // left in reach, fly on right away
        boolean unfinishedInReach = false;
        if (inReach.isEmpty()) {
            for (BlockPos pos : work) {
                if (eye.distanceToSqr(Vec3.atCenterOf(pos)) <= (reach - 0.25) * (reach - 0.25)) {
                    unfinishedInReach = true;
                    break;
                }
            }
        }
        if (inReach.isEmpty() && unfinishedInReach && state == State.WORKING
                && tick - lastWorkTick < 2L * AutoBuild.INSTANCE.speed.interval() + GRACE_EXTRA_TICKS) {
            if (flying(player)) {
                player.setDeltaMovement(Vec3.ZERO);
            }
            return;
        }

        // Keep moving: with only a few placeable blocks left here, fly on to the next spot already; the printer
        // places these on the way (it places whatever is in reach while flying)
        if (!inReach.isEmpty() && inReach.size() <= KEEP_MOVING_LEFT && Configs.Movement.KEEP_MOVING.getBooleanValue()
                && mode.canFly() && flying(player) && tick - lastEarlyLeave > 10) {
            BlockPos next = bestSpot(mc, player, schematic, reach);
            if (next != null && hoverPoint(mc, next).distanceToSqr(player.position()) > 4) {
                lastEarlyLeave = tick;
                target = next;
                replans = 0;
                plan(mc, player, next, mode, reach);
                move(mc, player, mode);
                return;
            }
        }

        if (!inReach.isEmpty()) {
            if (state == State.WALKING || state == State.FLYING) {
                halt(player);
                if (moveStartTick >= 0) {
                    BuildLog.line("MOVE", String.format("arrived at %s after %.1f s, %.1f blocks from %s; %d placeable in reach",
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
        // Already there (the same spot again): no flight, just keep placing here
        if (state != State.WALKING && state != State.FLYING && mode.canFly() && flying(player)
                && hoverPoint(mc, nearest).distanceToSqr(player.position()) < 0.6 * 0.6) {
            target = nearest;
            state = State.WORKING;
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
            if (AutoBuild.INSTANCE.givenUp(p)) {
                return false;
            }
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
            // Held back by the add-on's own order (water first, what it stands on first...): the printer won't get
            // it, so it doesn't count as something to place here
            if (!PlacementOrder.isReady(mc.level, schematic, p, schematic.getBlockState(p))) {
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
            if (result == BlockCheck.Result.MISSING && Configs.Layers.ADDON_PLACES.getBooleanValue()
                    && AutoBuild.INSTANCE.rails.selfPlaceFacing(mc.level, schematic, p, schematic.getBlockState(p)) != null) {
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
        List<BlockPos> sorted = new ArrayList<>(work);
        sorted.sort(java.util.Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));

        // Area by area: the layer is cut into 16x16 squares (chunks), done in snake order from the north-west corner.
        // It stays in a square until nothing in it can be placed, then goes to the first square in that order that has
        // something placeable (every square is checked, near or far). Chains (hoppers) are so followed from where they
        // start, in small areas, back and forth, never in long lines
        int layer = AutoBuild.INSTANCE.layers.getLayer();
        if (layer != sweepLayer) {
            sweepLayer = layer;
            unloadable.clear();
            lastAreaOrder = Long.MIN_VALUE;
            area = null;
            sweep = 1;
        }
        java.util.Map<Long, List<BlockPos>> workByArea = new HashMap<>();
        for (BlockPos pos : sorted) {
            workByArea.computeIfAbsent(areaKey(pos), k -> new ArrayList<>()).add(pos);
        }
        if (area == null && tick - lastNothingDoable < 20) {
            // Searched every area a moment ago and nothing could be placed: don't search the whole layer every tick
            return null;
        }
        if (area == null || !anyDoable(mc, schematic, workByArea.get(area))) {
            java.util.Set<Long> keys = new java.util.HashSet<>(workByArea.keySet());
            keys.addAll(unloadedAreas.keySet());
            keys.removeAll(unloadable);
            // The nearest area with something placeable (nearest block in it), with a small extra cost for areas
            // behind in the usual order (north-west to south-east, snaking), so it doesn't hop back and forth.
            // Areas that aren't loaded yet come when nothing loaded is left, nearest first: none is skipped
            Long next = null;
            double best = Double.MAX_VALUE;
            for (Long key : keys) {
                List<BlockPos> list = workByArea.get(key);
                BlockPos first = firstDoable(mc, schematic, list);
                if (first == null) {
                    continue;
                }
                double cost = Math.sqrt(from.distanceToSqr(Vec3.atCenterOf(first)))
                        + (lastAreaOrder != Long.MIN_VALUE && areaOrder(key) < lastAreaOrder ? BEHIND_AREA_COST : 0);
                if (cost < best) {
                    best = cost;
                    next = key;
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
                    return nearestUnloaded;
                }
            }
            if (next == null) {
                lastNothingDoable = tick;
                if (tick - lastNothingDoableReport > 20 * 30 && !sorted.isEmpty()) {
                    lastNothingDoableReport = tick;
                    PrinterAddon.debug("Nothing of the {} unfinished blocks can be placed now; nearest: {}",
                            sorted.size(), Diagnose.explain(mc, schematic, sorted.getFirst()));
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
            if (canDoNow(mc, schematic, pos)) {
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
            BuildLog.line("PLAN", String.format("area %s: %d spots %d apart (reach %.2f, %.2f above the blocks, %.2f sideways)",
                    areaName(area), plan.size(), spacing, planR, eyeAbove, sideways));
        }
        for (int step = 0; step < plan.size(); step++) {
            int i = (planIndex + step) % plan.size();
            BlockPos spot = plan.get(i).atY(y);
            Vec3 eye = hoverPoint(mc, spot).add(0, eyeHeight, 0);
            BlockPos nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (BlockPos pos : here) {
                double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
                // Planned with a margin: the printer doesn't always manage at its full range
                if (d < nearestDistance && reachable(mc, schematic, eye, pos, planR + 0.25)) {
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
                    return nearest;
                }
                if (i != planIndex) {
                    PrinterAddon.debug("Next spot in area {}: {} of {}", areaName(area), i + 1, plan.size());
                }
                planIndex = i;
                spotFor = nearest;
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
    private static double learnedReach = -1;
    private static int placedSinceMiss;

    private double planReach(double reach) {
        double max = reach - 0.25;
        if (learnedReach < 0 || learnedReach > max) {
            learnedReach = max - 0.25;
        }
        int placed = AutoBuild.INSTANCE.stats.totalPlaced();
        if (placed - placedSinceMiss >= 150 && learnedReach < max) {
            placedSinceMiss = placed;
            learnedReach = Math.min(max, learnedReach + 0.1);
        }
        return learnedReach;
    }

    /** A block at this distance from the eye couldn't be done: plan closer from now on. */
    public static void missedAt(double distance) {
        // Only misses near the edge of the reach say something about the reach (a close block that isn't placed
        // has another reason)
        if (learnedReach > 0 && distance < learnedReach + 0.3 && distance > learnedReach - 0.8) {
            double before = learnedReach;
            learnedReach = Math.max(2.5, Math.min(learnedReach, distance - 0.25));
            placedSinceMiss = AutoBuild.INSTANCE.stats.totalPlaced();
            if (learnedReach < before - 0.01) {
                BuildLog.line("LEARN", String.format("a block %.2f away wasn't placed: planning with reach %.2f now (was %.2f)",
                        distance, learnedReach, before));
            }
        }
    }

    /**
     * The spots of an area, this many blocks apart, row by row back and forth from the corner nearest to the
     * player (like mowing a lawn); the rows and columns also reach the far edge of the area.
     */
    private static List<BlockPos> lawnPlan(long key, Vec3 from, int y, int spacing) {
        int minX = ((int) key) * 16;
        int minZ = ((int) (key >> 32)) * 16;
        List<Integer> offsets = new ArrayList<>();
        for (int o = spacing / 2; o < 16; o += spacing) {
            offsets.add(o);
        }
        if (offsets.getLast() < 15 - spacing / 2) {
            offsets.add(15 - spacing / 2);
        }
        int n = offsets.size();
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
        double bucket = mc.player.blockInteractionRange() - 0.5;
        return d <= bucket * bucket || BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.BUCKET;
    }

    /** Extra cost (blocks) for going back to an area behind in the usual order. */
    private static final double BEHIND_AREA_COST = 12;

    /** The first (nearest, the lists are sorted by distance) block in the list that can be done now. */
    @Nullable
    private BlockPos firstDoable(Minecraft mc, WorldSchematic schematic, @Nullable List<BlockPos> positions) {
        if (positions == null) {
            return null;
        }
        for (BlockPos pos : positions) {
            if (canDoNow(mc, schematic, pos)) {
                return pos;
            }
        }
        return null;
    }

    private boolean anyDoable(Minecraft mc, WorldSchematic schematic, @Nullable List<BlockPos> positions) {
        if (positions == null) {
            return false;
        }
        for (BlockPos pos : positions) {
            if (canDoNow(mc, schematic, pos)) {
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
    /** Blocks per tick while flying past blocks the printer is placing (about 2 blocks a second). */
    private static final double GLIDE_SPEED = 0.1;
    private boolean gliding;
    private long lastEarlyLeave = -100;
    /** Ticks on top of two printer turns to wait for the next block of a chain (a server round trip). */
    private static final int GRACE_EXTRA_TICKS = 4;

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
            player.setDeltaMovement(Vec3.ZERO);
        }

        // The printer looks for a block every interval: when it found nothing for two of its turns (and nothing
        // else is busy), the blocks here aren't going to happen now. Move on instead of hovering.
        // (longer than a just-tried block is held back while the server answers: 2x ping + 6)
        int idleLimit = Math.max(Math.max(MIN_IDLE_TICKS, 2 * AutoBuild.INSTANCE.speed.interval() + 4),
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
            Vec3 eye = player.getEyePosition();
            for (BlockPos pos : inReach) {
                skipped.put(pos, tick + SKIP_TICKS);
                missedAt(Math.sqrt(eye.distanceToSqr(Vec3.atCenterOf(pos))));
            }
            work.removeAll(inReach);
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
                skipped.put(block, tick + SKIP_TICKS);
                work.remove(block);
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
            }
            BuildLog.line("MOVE", String.format("flying from %s to %.1f %.1f %.1f (%.1f blocks) for %s, area %s",
                    player.blockPosition().toShortString(), flyTarget.x, flyTarget.y, flyTarget.z,
                    Math.sqrt(player.position().distanceToSqr(flyTarget)), goalBlock.toShortString(), areaName(area)));
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

    /**
     * Where to hover for a block: right above the layers being built (so nothing is ever over the player's head
     * and the reach goes furthest sideways: with range 5, about 4.5 blocks at hover height 1 against 3.9 at 2),
     * or above the block itself if it's lower (a block placed later).
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
        int hoverY = baseY + Configs.Movement.HOVER_HEIGHT.getIntegerValue();
        BlockPos pos = new BlockPos(goalBlock.getX() + offsets[column][0], hoverY, goalBlock.getZ() + offsets[column][1]);
        if (canHover(mc, pos)) {
            return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
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
                    if (eye.distanceToSqr(goal) > r * r || !canHover(mc, spot)) {
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
            return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }
        return new Vec3(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
    }

    /** The player fits there (2 blocks of free space) and isn't in the way of blocks still to be placed. */
    private boolean canHover(Minecraft mc, BlockPos feet) {
        return FlyPath.fits(mc.level, feet) && !reserved.contains(feet) && !reserved.contains(feet.above());
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

        // The way there, around buildings and anything else in the way (worked out once per target)
        if (flyPath == null || !flyTarget.equals(flyPathFor)) {
            long started = Perf.start();
            flyPathFor = flyTarget;
            flyIndex = 0;
            if (FlyPath.clear(mc.level, player.position(), flyTarget)) {
                flyPath = List.of(flyTarget);
            } else {
                flyPath = FlyPath.find(mc.level, player.position(), flyTarget);
                if (flyPath == null) {
                    // No way there (the spot is inside something, or walled in): leave that block for later instead
                    // of flying into a building and climbing it
                    PrinterAddon.debug("No flight path from {} to {}", player.position(), flyTarget);
                    BuildLog.line("MOVE", "no way to " + BlockPos.containing(flyTarget).toShortString()
                            + (target != null ? " for " + target.toShortString() : "") + ": leaving it for later");
                    if (target != null) {
                        skipped.put(target, tick + SKIP_TICKS);
                        work.remove(target);
                        if (spotFor != null) {
                            skipped.put(spotFor, tick + SKIP_TICKS);
                            work.remove(spotFor);
                        }
                    }
                    flyPath = null;
                    flyTarget = null;
                    player.setDeltaMovement(Vec3.ZERO);
                    state = State.IDLE;
                    return;
                } else {
                    BuildLog.line("MOVE", "way around what's in the way: " + flyPath.size() + " turns");
                    PrinterAddon.debug("Flight path from {} to {}: {}", player.position(), flyTarget, flyPath);
                }
            }
            Perf.end("movement: flight path", started);
        }
        Vec3 waypoint = flyPath.get(Math.min(flyIndex, flyPath.size() - 1));
        // On to the next turn once it's reached, or as soon as the straight line there is free (never cut a corner)
        if (flyIndex < flyPath.size() - 1 && (waypoint.distanceToSqr(player.position()) < 0.15 * 0.15
                || (tick % 4 == 0 && FlyPath.clear(mc.level, player.position(), flyPath.get(flyIndex + 1))))) {
            flyIndex++;
            waypoint = flyPath.get(flyIndex);
            lastDistance = Double.MAX_VALUE;
        }

        Vec3 to = waypoint.subtract(player.position());
        double distance = to.length();

        if (distance < 0.2 && flyIndex >= flyPath.size() - 1) {
            player.setDeltaMovement(Vec3.ZERO);
            state = State.IDLE;
            logArrival(mc, player);
            return;
        }

        if (distance < lastDistance - 0.01) {
            lastDistance = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            // Something in the way after all (a block changed): find the way again first, then go higher
            PrinterAddon.debug("Stuck flying at {}, finding the way again", player.blockPosition().toShortString());
            if (replans < 2) {
                flyPath = null;
                replans++;
                stuckTicks = 0;
                lastDistance = Double.MAX_VALUE;
                return;
            }
            // Still stuck: leave that block for later (never climb blindly)
            BuildLog.line("MOVE", "stuck at " + player.blockPosition().toShortString() + " on the way to "
                    + BlockPos.containing(flyTarget).toShortString() + ": leaving " + (target != null ? target.toShortString() : "it") + " for later");
            stuckTicks = 0;
            lastDistance = Double.MAX_VALUE;
            if (target != null) {
                skipped.put(target, tick + SKIP_TICKS);
                work.remove(target);
            }
            flyPath = null;
            flyTarget = null;
            player.setDeltaMovement(Vec3.ZERO);
            state = State.IDLE;
            return;
        }

        double speed = Math.min(gliding ? GLIDE_SPEED : Configs.Speed.flySpeed(), distance);
        Vec3 motion = to.normalize().scale(speed);
        if (player.horizontalCollision) {
            motion = new Vec3(motion.x, Math.max(motion.y, 0.3), motion.z);
        }
        player.setDeltaMovement(motion);
    }

    private void logArrival(Minecraft mc, LocalPlayer player) {
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
        BuildLog.line("MOVE", String.format("arrived at %s after %.1f s, %.1f blocks from %s; %d placeable in reach",
                player.blockPosition().toShortString(), (tick - moveStartTick) / 20.0,
                Math.sqrt(player.position().distanceToSqr(moveFrom)), BlockPos.containing(moveFrom).toShortString(), placeable));
        moveStartTick = -1;
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

    private static void halt(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0, flying(player) ? 0 : motion.y, 0);
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

    /** Finds the unfinished positions in the current layer range. */
    private void scan(Minecraft mc, WorldSchematic schematic) {
        work.clear();
        reserved.clear();
        unloaded.clear();
        unloadedAreas.clear();
        waitingForItems = 0;
        LayerManager layers = AutoBuild.INSTANCE.layers;

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
                        if (result == BlockCheck.Result.UNLOADED && layers.isActive()
                                && (y < layers.getLayer() || y > layers.getLayerTop())) {
                            continue;
                        }
                        if (result == BlockCheck.Result.UNLOADED) {
                            if (unloaded.size() < 512) {
                                unloaded.add(pos.immutable());
                            }
                            unloadedAreas.putIfAbsent(areaKey(pos), pos.immutable());
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
