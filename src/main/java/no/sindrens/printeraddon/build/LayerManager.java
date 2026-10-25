package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds bottom-up: sets Litematica to a single Y layer and moves to the next layer once it's done.
 */
public class LayerManager {
    /** How many positions to list in chat when a layer is stuck. */
    private static final int MAX_REPORTED = 12;

    private boolean active;
    private int layer;
    /** Top of the layers being built together: the same as {@link #layer}, or one more for farmland + crops. */
    private int layerTop;
    private int minY;
    private int maxY;
    private int ticksUntilCheck;
    /** The layers last looked through for dead-end rows (see DeadEnds). */
    private int deadEndsLayer = Integer.MIN_VALUE;
    private int deadEndsTop = Integer.MIN_VALUE;
    private long deadEndsAt;
    private int completeChecks;
    private int lastRemaining = -1;
    /** The fewest blocks left on these layers so far: only a new low (or a block placed) is progress, not a count going up and down. */
    private int lowestRemaining = Integer.MAX_VALUE;
    private int ticksWithoutProgress;
    private int lastPlaced = -1;
    private int lastReportAt;
    private boolean reportedStuck;
    /** Layer and blocks left, for the status line. */
    private String status = "";
    private int waitingChecks;
    /** All layers are done; only the deferred blocks are left. */
    private boolean finishingDeferred;
    private boolean retriedSkipped;

    /**
     * Layers that are finished (or that the build went past, their leftovers waiting until the end): never read
     * again to find where to go on, only checked in the background (see {@link #checked}). Saved with the blocks
     * left out (see BuildProgress), so a restart goes on at the first layer not in here.
     */
    private final java.util.Set<Integer> doneLayers = new java.util.TreeSet<>();
    private int checksSinceSave;
    /** The whole build is finished: nothing to save for next time. */
    private boolean buildFinished;
    /** Leftovers that are fine on a layer the build went past: this many, or 1 in 100 of its blocks. */
    private static final int PASSED_LEFTOVERS = 64;
    /** A finished layer missing more than this is built again; fewer are placed when the player is near them. */
    private static final int RECHECK_DEFER_LIMIT = 64;

    /** Litematica's layer settings before auto build, put back afterwards. */
    @Nullable
    private LayerMode previousMode;
    private Direction.Axis previousAxis;
    private int previousSingle;

    public boolean isActive() {
        return active;
    }

    /**
     * Parts (chunks) of the current layers seen finished while they were loaded. A build wider than what's loaded
     * around the player is never loaded all at once: a part that isn't loaded now counts as finished when it was seen
     * so, else the layer was never done (a log had the player flying from one far corner to the other and back for
     * two minutes, each corner unloading the other). The finished layers are checked again in the background anyway.
     */
    private final java.util.Set<Long> seenFinished = new java.util.HashSet<>();
    private int seenFor = Integer.MIN_VALUE;
    private int seenForTop = Integer.MIN_VALUE;

    /** True for a block in a part of the current layers that isn't loaded, but was seen finished. */
    public boolean seenFinished(BlockPos pos) {
        return active && !finishingDeferred && seenFor == layer && seenForTop == layerTop
                && seenFinished.contains(chunkKey(pos.getX(), pos.getZ()));
    }

    /** The same keys as the mover's areas: one per chunk. */
    static long chunkKey(int x, int z) {
        return ((long) (z >> 4) << 32) | ((x >> 4) & 0xFFFFFFFFL);
    }

    /** What one check found per part (chunk) of the layers. */
    private static final class Parts {
        final java.util.Set<Long> seenFinished;
        final java.util.Set<Long> loaded = new java.util.HashSet<>();
        final java.util.Set<Long> unfinished = new java.util.HashSet<>();
        final java.util.Set<Long> unloaded = new java.util.HashSet<>();
        int unloadedBlocks;

        Parts(java.util.Set<Long> seenFinished) {
            this.seenFinished = seenFinished;
        }
    }

    /** Items the current layers still need (item -> count). */
    private java.util.Map<net.minecraft.world.item.Item, Integer> layerNeeds = java.util.Map.of();

    public java.util.Map<net.minecraft.world.item.Item, Integer> layerNeeds() {
        return layerNeeds;
    }

    /** The highest Y of the layers being built now. */
    public int getLayerTop() {
        return layerTop;
    }

    /**
     * The layers being built have blocks that only air place can put in (string floating at the bottom of a cactus
     * farm), and all their missing blocks come out right placed in the air: the printer places in the air meanwhile.
     */
    private boolean airLayer;
    /** The layers that was worked out for (a new layer isn't air placed until it's been looked at). */
    private int airFor = Integer.MIN_VALUE;
    private int airForTop = Integer.MIN_VALUE;

    public boolean airLayer() {
        return active && airLayer && airFor == layer && airForTop == layerTop;
    }

    /** Blocks that hurt where they are (the whole block counts, not only what the player bumps into). */
    private static final java.util.Set<net.minecraft.world.level.block.Block> HURT_TO_TOUCH = java.util.Set.of(
            Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH);
    /** How much higher to hover over layers with those: the head still stays under the block 2 up. */
    private static final double HURT_LIFT = 0.2;
    private int liftLayer = Integer.MIN_VALUE;
    private int liftTop = Integer.MIN_VALUE;
    private double lift;

    /**
     * How far over the hover height to fly over the layers being built: a little more when the top one has a cactus
     * (flying right on top of it, the feet were a hair in a cactus's block now and then; it hurt and stopped the
     * build), else nothing (a cactus lower down is a block or more under the feet).
     */
    public double hoverLift() {
        if (!active) {
            return 0;
        }
        if (liftLayer != layer || liftTop != layerTop) {
            liftLayer = layer;
            liftTop = layerTop;
            lift = hasHurting() ? HURT_LIFT : 0;
        }
        return lift;
    }

    private boolean hasHurting() {
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return false;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            if (layerTop < area.minY() || layerTop > area.maxY()) {
                continue;
            }
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    if (HURT_TO_TOUCH.contains(schematic.getBlockState(pos.set(x, layerTop, z)).getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public int getLayer() {
        return layer;
    }

    /** Starts at the lowest unfinished layer. Returns false if there is no schematic placed. */
    public boolean start(Minecraft mc) {
        deadEndsLayer = Integer.MIN_VALUE;
        deadEndsTop = Integer.MIN_VALUE;
        airLayer = false;
        liftLayer = Integer.MIN_VALUE;
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        int[] range = SchematicHelper.yRange(areas);
        WorldSchematic schematic = SchematicHelper.world();

        if (range == null || schematic == null || mc.level == null) {
            Chat.warn("No schematic placement found. Load and place a schematic in Litematica first.");
            return false;
        }

        minY = range[0];
        maxY = range[1];
        buildFinished = false;
        checksSinceSave = 0;
        doneLayers.clear();
        // Where this build was (finished layers, blocks left out): no need to read the finished layers again first
        long started = System.nanoTime();
        retriedFromBefore = 0;
        seenFinished.clear();
        seenFor = Integer.MIN_VALUE;
        BuildProgress.Saved saved = BuildProgress.load(mc);
        if (saved != null) {
            for (int y : saved.done()) {
                if (y >= minY && y <= maxY) {
                    doneLayers.add(y);
                }
            }
            // Blocks skipped last time are tried again (starting again is the time for that): in their own layer,
            // or, on a layer that's done, when the player is near them. They used to stay left out until the
            // very end, with nothing got for them (7 blocks never placed, no piston or seeds fetched for them).
            retriedFromBefore = saved.skipped().size();
            AutoBuild.INSTANCE.deferred.addAll(saved.deferred());
            if (doneLayers.size() == maxY - minY + 1) {
                // Saved as all done: read everything again (something may have been broken since)
                doneLayers.clear();
            }
        }
        int fromSave = doneLayers.size();
        int passed = 0;
        int leftovers = 0;
        layer = minY;
        java.util.Map<Integer, LayerScan> scans = new java.util.HashMap<>();
        while (layer <= maxY) {
            if (doneLayers.contains(layer)) {
                layer++;
                continue;
            }
            LayerScan here = scans.computeIfAbsent(layer, y -> LayerScan.of(mc.level, schematic, areas, y, 20000));
            if (here.unfinished == 0 && here.unloaded == 0) {
                doneLayers.add(layer++);
                continue;
            }
            // A few left (or a part that isn't loaded) on a layer the build already went past (blocks it left out
            // before a restart): they wait until the end, the build goes on where it was
            if (layer < maxY && here.unfinished <= Math.max(PASSED_LEFTOVERS, here.blocks / 100)) {
                LayerScan above = scans.computeIfAbsent(layer + 1, y -> LayerScan.of(mc.level, schematic, areas, y, 20000));
                if (above.placed >= Math.max(16, above.blocks / 100)) {
                    AutoBuild.INSTANCE.deferred.addAll(here.positions);
                    leftovers += here.unfinished;
                    passed++;
                    doneLayers.add(layer++);
                    continue;
                }
            }
            break;
        }
        // The ones skipped last time on the layers done before this one: placed when the player is near them
        if (saved != null) {
            for (BlockPos pos : saved.skipped()) {
                if (pos.getY() < layer) {
                    AutoBuild.INSTANCE.deferred.add(pos);
                }
            }
        }
        BuildLog.line("LAYER", String.format("start: %d layers known as done from the saved progress, %d read and done, %d gone past"
                        + " (%d blocks left there wait until the end); going on at Y %d | %.1f ms | %d skipped last time, tried again",
                fromSave, doneLayers.size() - fromSave - passed, passed, leftovers, layer, (System.nanoTime() - started) / 1e6,
                retriedFromBefore));

        if (layer > maxY) {
            Chat.info("Every layer is already finished.");
            return false;
        }
        if (layer > minY) {
            Chat.info("Layers Y " + minY + " to " + (layer - 1) + " are already done: going on at Y " + layer
                    + (fromSave > 0 ? " (from where it was last time; the done layers are checked again while it builds)" : "")
                    + (leftovers > 0 ? ", " + leftovers + " block" + (leftovers == 1 ? "" : "s") + " left on them are done at the end" : "") + ".");
        }

        layerTop = topFor(mc.level, schematic, areas, layer);

        LayerRange layerRange = DataManager.getRenderLayerRange();
        previousMode = layerRange.getLayerMode();
        previousAxis = layerRange.getAxis();
        previousSingle = layerRange.getLayerSingle();

        active = true;
        applyLayer();
        resetProgress();
        Chat.info("Building layer by layer from Y " + layer + " (layers " + minY + " to " + maxY + ").");
        return true;
    }

    /** True when the current layer hasn't changed for this many ticks. */
    /** Check the layer again within a few ticks (e.g. after water went in). */
    public void checkSoon() {
        ticksUntilCheck = Math.min(ticksUntilCheck, 1);
    }

    /** Farmland of the current layers still to till (as last checked). */
    private int tillingLeft;

    public int tillingLeft() {
        return active ? tillingLeft : 0;
    }

    /** -1 until the layer was checked once; else the unfinished water blocks of the layer. */
    private int waterPending;

    public boolean stalledFor(int ticks) {
        return active && ticksWithoutProgress >= ticks;
    }

    public void stop() {
        if (active && !buildFinished) {
            saveProgress(Minecraft.getInstance());
        }
        finishingDeferred = false;
        retriedSkipped = false;
        if (active) {
            restoreLayerRange();
        }
        active = false;
        airLayer = false;
        liftLayer = Integer.MIN_VALUE;
    }

    /** A finished layer (or one the build went past). */
    public boolean isDone(int y) {
        return active && doneLayers.contains(y);
    }

    private void saveProgress(Minecraft mc) {
        checksSinceSave = 0;
        if (mc.player == null || SchematicHelper.world() == null) {
            return;
        }
        BuildProgress.save(mc, doneLayers, AutoBuild.INSTANCE.couldNotPlace, AutoBuild.INSTANCE.deferred);
    }

    /** The build is finished: the saved progress is removed (next time everything is read again). */
    private void finished(Minecraft mc) {
        buildFinished = true;
        BuildProgress.forget(mc);
    }

    /**
     * A finished layer was read again in the background (see AutoBuild#updateMissingWhole) and these blocks are
     * missing there (broken since, or never placed before a restart). A few: placed when the player is near them
     * (like the blocks that waited for something above). Many: that layer is built again first.
     */
    public void checked(Minecraft mc, int y, List<BlockPos> missing) {
        if (!active || finishingDeferred || !doneLayers.contains(y) || y >= layer || missing.isEmpty()) {
            return;
        }
        List<BlockPos> fresh = new ArrayList<>();
        for (BlockPos pos : missing) {
            if (!AutoBuild.INSTANCE.couldNotPlace.contains(pos) && !AutoBuild.INSTANCE.deferred.contains(pos)) {
                fresh.add(pos);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        StringBuilder examples = new StringBuilder();
        for (BlockPos pos : fresh.subList(0, Math.min(5, fresh.size()))) {
            examples.append(pos.toShortString()).append("; ");
        }
        if (fresh.size() <= RECHECK_DEFER_LIMIT) {
            AutoBuild.INSTANCE.deferred.addAll(fresh);
            BuildLog.line("VERIFY", "Layer Y " + y + " (done) has " + fresh.size() + " block" + (fresh.size() == 1 ? "" : "s")
                    + " missing: placed when the player is near them | " + examples);
            Chat.detail("Layer Y " + y + ": " + fresh.size() + " block" + (fresh.size() == 1 ? "" : "s")
                    + " missing there, placed on the way.");
            return;
        }
        BuildLog.line("VERIFY", "Layer Y " + y + " (done) has " + fresh.size() + " blocks missing: building it again first | " + examples);
        Chat.warn("Layer Y " + y + " is missing " + fresh.size() + " blocks (broken, or not placed before): building it again first.");
        doneLayers.remove(y);
        layer = y;
        layerTop = y;
        applyLayer();
        resetProgress();
        saveProgress(mc);
    }

    /** The player's own layer view from before auto build changed it, as text (for the resume file). */
    public String savedRange() {
        return previousMode == null ? "" : previousMode.name() + "|" + previousAxis.name() + "|" + previousSingle;
    }

    /**
     * After resuming: the view Litematica loaded on rejoining is the one auto build had set (it saved its settings
     * while building), so put back the player's own view from the resume file when the build ends.
     */
    public void useSavedRange(String text) {
        String[] parts = text.split("\\|");
        if (!active || parts.length != 3) {
            return;
        }
        try {
            previousMode = LayerMode.valueOf(parts[0]);
            previousAxis = Direction.Axis.valueOf(parts[1]);
            previousSingle = Integer.parseInt(parts[2]);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void restoreLayerRange() {
        if (previousMode == null) {
            return;
        }
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerSingle(previousSingle);
        if (range.getAxis() != previousAxis) {
            range.setAxis(previousAxis);
        }
        range.setLayerMode(previousMode, false);
        previousMode = null;
    }

    /** Runs one tick. Returns false when the whole build is finished. */
    public boolean tick(Minecraft mc) {
        if (!active || mc.level == null) {
            return true;
        }

        if (--ticksUntilCheck > 0) {
            return true;
        }
        // At least every 2 seconds: a check reads the whole layer
        int interval = Math.max(40, Configs.Layers.LAYER_CHECK_INTERVAL.getIntegerValue());
        ticksUntilCheck = interval;

        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return true;
        }

        List<SchematicHelper.Area> areas = SchematicHelper.areas();

        if (finishingDeferred) {
            return finishDeferred(mc, schematic, areas, interval);
        }
        followHandChange(mc, schematic, areas);

        // Rows of hoppers ending at an empty spot: left out once per layer, built as soon as the spot has something
        // (again every 30 s: rows in chunks that weren't loaded yet)
        if (layer != deadEndsLayer || layerTop != deadEndsTop || AutoBuild.clientTicks - deadEndsAt > 20 * 30) {
            deadEndsLayer = layer;
            deadEndsTop = layerTop;
            deadEndsAt = AutoBuild.clientTicks;
            long started = System.nanoTime();
            DeadEnds.findInLayers(mc.level, schematic, areas, layer, layerTop);
            PrinterAddon.debug("Dead-end rows looked for in {} ms", (System.nanoTime() - started) / 1_000_000);
        }
        DeadEnds.tick(mc.level);

        Findings findings = new Findings();
        if (seenFor != layer || seenForTop != layerTop) {
            seenFinished.clear();
            seenFor = layer;
            seenForTop = layerTop;
        }
        Parts parts = new Parts(seenFinished);
        List<BlockPos> left = new ArrayList<>();
        int remaining = 0;
        for (int y = layer; y <= layerTop; y++) {
            remaining += scan(mc.level, schematic, areas, y, left, findings, parts);
        }
        for (long part : parts.loaded) {
            if (parts.unfinished.contains(part)) {
                seenFinished.remove(part);
            } else {
                seenFinished.add(part);
            }
        }
        if (layerTop > layer) {
            // Built together: what stands on a block of the layer below that isn't there yet waits for it here, not
            // for something above (those wait until the layers above are built)
            findings.waiting.removeIf(p -> p.getY() > layer && BlockCheck.check(mc.level, schematic, p.below()) != BlockCheck.Result.OK);
        }
        if (waterPending > 0 && findings.water == 0 && remaining > 0) {
            BuildLog.line("LAYER", "the water on " + describeLayers() + " is all in");
        }
        waterPending = findings.water;
        int notSeen = 0;
        for (long part : parts.unloaded) {
            if (!seenFinished.contains(part)) {
                notSeen++;
            }
        }
        StringBuilder leftText = new StringBuilder();
        if (remaining > 0 && remaining <= MAX_REPORTED) {
            // The last few, with what's there: says at once why a layer isn't done
            for (BlockPos pos : left) {
                if (leftText.length() > 0) {
                    leftText.append("; ");
                }
                leftText.append(pos.toShortString()).append(' ').append(BlockCheck.check(mc.level, schematic, pos))
                        .append(' ').append(blockName(mc.level.getBlockState(pos))).append(" (wants ")
                        .append(blockName(schematic.getBlockState(pos))).append(')');
            }
        }
        BuildLog.line("LAYER", describeLayers() + ": " + remaining + " unfinished (" + findings.waiting.size()
                + " waiting for something above, " + findings.water + " water still to go in) | conflicts "
                + AutoBuild.INSTANCE.conflicts.size() + " | still needs " + findings.needs
                + (parts.unloaded.isEmpty() ? "" : " | not loaded: " + parts.unloaded.size() + " parts, "
                + (parts.unloaded.size() - notSeen) + " of them seen finished, " + parts.unloadedBlocks + " blocks in the others")
                + (leftText.length() > 0 ? " | left: " + leftText : ""));
        if (!AutoBuild.INSTANCE.deferred.isEmpty()) {
            deferredNeeds(mc.level, schematic, AutoBuild.INSTANCE.deferred, findings);
        }
        tillingLeft = findings.tilling;
        // Tilling needs a hoe: one from storage, else crafted (see Tools); enough for the tilling left
        if (findings.tilling > 0 && mc.player != null && !Tools.covered(Tools.HOE, mc.player.getInventory())) {
            findings.needs.put(Tools.HOE, Tools.hoesFor(findings.tilling - Tools.hoeUses(mc.player.getInventory()), mc.player.getInventory()));
        }
        // A pickaxe and a shovel for breaking wrong blocks (a hopper or an observer drops nothing without a pickaxe),
        // got like everything else: from storage, else crafted
        if (mc.player != null && Configs.Breaking.BREAK_WRONG_BLOCKS.getBooleanValue()) {
            for (net.minecraft.world.item.Item tool : Tools.breakingToolsMissing(AutoBuild.INSTANCE.schematicBlocks, mc.player.getInventory())) {
                findings.needs.put(tool, 1);
            }
        }
        updateShoppingList(mc, findings.needs);
        layerNeeds = findings.needs;
        boolean air = findings.floating > 0 && findings.notAirSafe == 0 && AutoBuild.INSTANCE.airPlace.allowed();
        if (air != airLayer()) {
            BuildLog.line("AIRPLACE", air ? describeLayers() + ": " + findings.floating + " blocks float (nothing beside or under"
                    + " them to place them against): the printer places in the air until they're in"
                    : describeLayers() + ": nothing left that floats, air place off");
            if (air) {
                Chat.quiet(findings.floating + " block" + (findings.floating == 1 ? "" : "s") + " of " + describeLayers()
                        + " float: placing them in the air.");
            }
        }
        airLayer = air;
        airFor = layer;
        airForTop = layerTop;

        // Everything left waits for something higher up (an ascending rail, an observer watching a block above):
        // don't hold up the layer, place those later
        if (remaining > 0 && remaining == findings.waiting.size()) {
            if (++waitingChecks >= 3) {
                AutoBuild.INSTANCE.deferred.addAll(findings.waiting);
                Chat.quiet(remaining + " block" + (remaining == 1 ? "" : "s") + " in " + describeLayers()
                        + " wait for blocks above; placed as soon as those are there.");
                remaining = 0;
                completeChecks = 2;
            }
        } else {
            waitingChecks = 0;
        }

        if (forceDone) {
            forceDone = false;
            remaining = 0;
            completeChecks = Math.max(completeChecks, 1);
        }
        if (remaining == 0) {
            // Check twice in a row so a block the server hasn't confirmed yet doesn't count: the second time as
            // soon as the server's answer is surely there (not a whole check interval later: that was 2 s per layer)
            if (++completeChecks < 2) {
                ticksUntilCheck = Math.max(10, 2 * ServerLag.pingTicks(mc) + 6);
                return true;
            }

            Chat.info(describeLayers() + " done.");
            AutoBuild.INSTANCE.stats.layerDone(describeLayers());
            RunLog.layerEnd(describeLayers());
            AutoBuild.INSTANCE.speed.newLayer();
            for (int y = layer; y <= layerTop; y++) {
                doneLayers.add(y);
            }
            layer = layerTop;
            do {
                layer++;
                if (layer <= maxY && !doneLayers.contains(layer) && scan(mc.level, schematic, areas, layer, null) == 0) {
                    doneLayers.add(layer);
                }
            } while (layer <= maxY && doneLayers.contains(layer));
            saveProgress(mc);

            if (layer > maxY) {
                if (!retriedSkipped && !AutoBuild.INSTANCE.couldNotPlace.isEmpty()) {
                    // One more go at what was skipped: with everything around it built, it often fits now
                    retriedSkipped = true;
                    Chat.info("Trying the " + AutoBuild.INSTANCE.couldNotPlace.size() + " skipped blocks again.");
                    AutoBuild.INSTANCE.deferred.addAll(AutoBuild.INSTANCE.couldNotPlace);
                    AutoBuild.INSTANCE.couldNotPlace.clear();
                }
                if (!unfinishedDeferred(mc.level, schematic).isEmpty()) {
                    // All layers done; place what waited, with the whole schematic visible
                    finishingDeferred = true;
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL, false);
                    resetProgress();
                    return true;
                }
                Chat.info("\u00a7aBuild finished!");
                Chat.announce("Build finished", "everything in the schematic is placed", true);
                Chat.info(AutoBuild.INSTANCE.stats.total());
                reportUnfinished(mc, schematic);
                finished(mc);
                stop();
                return false;
            }

            layerTop = topFor(mc.level, schematic, areas, layer);
            applyLayer();
            resetProgress();
            return true;
        }

        completeChecks = 0;
        // Where the build is, on disk now and then (a crash or a lost connection doesn't lose it)
        if (++checksSinceSave >= 5) {
            saveProgress(mc);
        }

        int placed = AutoBuild.INSTANCE.stats.totalPlaced();
        // A count going up and down (a farm at work, parts loading and unloading) isn't progress: that kept the
        // "nothing placed" report from ever coming, while the player flew around for minutes
        if (remaining < lowestRemaining || placed != lastPlaced) {
            lowestRemaining = Math.min(lowestRemaining, remaining);
            lastRemaining = remaining;
            lastPlaced = placed;
            ticksWithoutProgress = 0;
            reportedStuck = false;
        } else if (mc.player == null || !AutoBuild.INSTANCE.mover.isTravelling(mc.player)) {
            // Flying to the next blocks isn't standing still: no "nothing placed" warning for a long flight (a flight
            // that never gets anywhere is caught by the watchdog, see AutoBuild)
            ticksWithoutProgress += interval;
        }

        status = describeLayers() + ": " + remaining + " left";

        // Report when nothing has happened for a while, and again every minute after that
        int reportAfter = Configs.Layers.STUCK_REPORT_SECONDS.getIntegerValue() * 20;
        if (ticksWithoutProgress >= reportAfter && (!reportedStuck || ticksWithoutProgress - lastReportAt >= 20 * 60)) {
            reportedStuck = true;
            lastReportAt = ticksWithoutProgress;
            reportStuck(mc, schematic, areas);
        }

        // A layer is finished before the next one starts. Only when nothing can be done here for long (no block
        // the mover can go to, nothing being bought or fetched) do the last ones wait until the end, so the build
        // never stands still or flies around for good.
        int giveUpAfter = Math.max(20 * 30, Configs.Layers.SKIP_STUCK_SECONDS.getIntegerValue() * 20 * 3);
        if (ticksWithoutProgress >= giveUpAfter && remaining <= Math.max(PASSED_LEFTOVERS, 1)
                && AutoBuild.INSTANCE.mover.nothingToDo() && !AutoBuild.INSTANCE.isManualTaskRunning()) {
            List<BlockPos> all = new ArrayList<>();
            for (int y = layer; y <= layerTop; y++) {
                collectUnfinished(mc.level, schematic, areas, y, all);
            }
            AutoBuild.INSTANCE.deferred.addAll(all);
            BuildLog.line("LAYER", describeLayers() + ": nothing could be done here for " + ticksWithoutProgress / 20
                    + " s; the last " + remaining + " (" + all.size() + " of them loaded) wait until the end | " + leftText);
            Chat.warn(describeLayers() + ": the last " + remaining + " block" + (remaining == 1 ? "" : "s")
                    + " can't be done now; tried again at the end, going on.");
            completeChecks = 2;
            ticksUntilCheck = 1;
            lowestRemaining = 0;
            forceDone = true;
        }
        return true;
    }

    /** The next check finishes the layer (its leftovers wait until the end, see above). */
    private boolean forceDone;
    private int retriedFromBefore;

    /**
     * What the blocks waiting for later (deferred: from layers done before, placed when the player is near them and
     * at the end) still need: their items and, for farmland, a hoe. They were never counted, so nothing was fetched or
     * bought for them, and at the end they were listed as "couldn't finish" with 0 of their item in the inventory.
     */
    private static void deferredNeeds(Level level, WorldSchematic schematic, Iterable<BlockPos> positions, Findings findings) {
        for (BlockPos pos : positions) {
            BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
            BlockState target = schematic.getBlockState(pos);
            if (result == BlockCheck.Result.MISSING) {
                ItemStack item = StateRules.itemFor(target);
                if (!item.isEmpty()) {
                    findings.needs.merge(item.getItem(), 1, Integer::sum);
                }
            }
            if ((result == BlockCheck.Result.MISSING || result == BlockCheck.Result.PRINTER_FIXES) && target.getBlock() == Blocks.FARMLAND) {
                findings.tilling++;
            }
            if (result == BlockCheck.Result.BUCKET && Fluids.bucketFor(target) != null) {
                findings.needs.put(Fluids.bucketFor(target), 1);
            }
        }
    }

    static String blockName(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private boolean finishDeferred(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas, int interval) {
        List<BlockPos> left = unfinishedDeferred(mc.level, schematic);
        Findings findings = new Findings();
        deferredNeeds(mc.level, schematic, left, findings);
        tillingLeft = findings.tilling;
        if (findings.tilling > 0 && mc.player != null && !Tools.covered(Tools.HOE, mc.player.getInventory())) {
            findings.needs.put(Tools.HOE, Tools.hoesFor(findings.tilling - Tools.hoeUses(mc.player.getInventory()), mc.player.getInventory()));
        }
        updateShoppingList(mc, findings.needs);
        layerNeeds = findings.needs;
        if (left.isEmpty()) {
            Chat.info("\u00a7aBuild finished!");
            Chat.announce("Build finished", "everything in the schematic is placed", true);
            AutoBuild.INSTANCE.stats.layerDone("Last blocks");
            RunLog.layerEnd("Last blocks");
            Chat.info(AutoBuild.INSTANCE.stats.total());
            reportUnfinished(mc, schematic);
            finished(mc);
            stop();
            return false;
        }

        if (left.size() != lastRemaining) {
            lastRemaining = left.size();
            ticksWithoutProgress = 0;
            reportedStuck = false;
        } else {
            ticksWithoutProgress += interval;
        }
        status = "Last blocks: " + left.size() + " left";

        if (!reportedStuck && ticksWithoutProgress >= Configs.Layers.STUCK_REPORT_SECONDS.getIntegerValue() * 20) {
            reportedStuck = true;
            Chat.warn("The last " + left.size() + " blocks haven't changed for a while, for example:");
            for (BlockPos pos : left.subList(0, Math.min(MAX_REPORTED, left.size()))) {
                BlockState target = schematic.getBlockState(pos);
                Chat.warn(" - " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": "
                        + describe(mc, BlockCheck.check(mc.level, schematic, pos), target));
            }
        }

        if (ticksWithoutProgress >= Configs.Layers.SKIP_STUCK_SECONDS.getIntegerValue() * 20 * 2) {
            // Nothing more happens: finish and list what's left
            AutoBuild.INSTANCE.deferred.removeAll(left);
            AutoBuild.INSTANCE.couldNotPlace.addAll(left);
            AutoBuild.INSTANCE.stats.layerDone("Last blocks");
            RunLog.layerEnd("Last blocks");
            Chat.info(AutoBuild.INSTANCE.stats.total());
            Chat.warn("Build done, except " + left.size() + " block" + (left.size() == 1 ? "" : "s")
                    + " that couldn't be placed (listed below).");
            reportUnfinished(mc, schematic);
            stop();
            return false;
        }
        return true;
    }

    public String status() {
        return status;
    }

    private int nextNeedsFor = Integer.MIN_VALUE;
    private java.util.Map<net.minecraft.world.item.Item, Integer> nextNeeds = java.util.Map.of();

    /**
     * What the layer above the ones being built still needs (item -> count), read once per layer: bought on the last
     * trip of this layer, so the next one starts without a trip to the shop.
     */
    public java.util.Map<net.minecraft.world.item.Item, Integer> nextLayerNeeds(Minecraft mc) {
        if (nextNeedsFor == layerTop || !active || finishingDeferred || mc.level == null) {
            return nextNeeds;
        }
        nextNeedsFor = layerTop;
        nextNeeds = java.util.Map.of();
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null || layerTop + 1 > maxY || doneLayers.contains(layerTop + 1)) {
            return nextNeeds;
        }
        Findings findings = new Findings();
        scan(mc.level, schematic, SchematicHelper.areas(), layerTop + 1, null, findings);
        nextNeeds = findings.needs;
        return nextNeeds;
    }

    public boolean isFinishingDeferred() {
        return finishingDeferred;
    }

    public void moveLayer(int amount) {
        if (!active) {
            LayerRange range = DataManager.getRenderLayerRange();
            range.setLayerMode(LayerMode.SINGLE_LAYER, false);
            range.setAxis(Direction.Axis.Y);
            range.setLayerSingle(range.getLayerSingle() + amount);
            Chat.actionBar("Layer Y " + range.getLayerSingle());
            return;
        }
        layer = Math.max(minY, Math.min(maxY, layer + amount));
        WorldSchematic schematic = SchematicHelper.world();
        Minecraft mc = Minecraft.getInstance();
        layerTop = schematic == null || mc.level == null ? layer : topFor(mc.level, schematic, SchematicHelper.areas(), layer);
        applyLayer();
        resetProgress();
        Chat.actionBar(describeLayers());
    }

    public String describeLayers() {
        return layerTop == layer ? "Layer Y " + layer : "Layers Y " + layer + "-" + layerTop;
    }

    /**
     * Litematica's layer view was moved by hand while building (to get past a block, or to do another layer first):
     * that layer is built now, so the printer, the movement and the layer checks all work on the same one. What's
     * left on the layer it was on waits until the end (like blocks waiting for something above). Any other view
     * (everything at once, another axis) is put back to the layer.
     */
    private void followHandChange(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas) {
        LayerRange range = DataManager.getRenderLayerRange();
        int min;
        int max;
        if (range.getAxis() == Direction.Axis.Y && range.getLayerMode() == LayerMode.SINGLE_LAYER) {
            min = max = range.getLayerSingle();
        } else if (range.getAxis() == Direction.Axis.Y && range.getLayerMode() == LayerMode.LAYER_RANGE) {
            min = range.getLayerRangeMin();
            max = range.getLayerRangeMax();
        } else {
            BuildLog.line("LAYER", "Litematica's layer view was changed (" + range.getLayerMode() + ", axis " + range.getAxis()
                    + "): set back to " + describeLayers());
            applyLayer();
            return;
        }
        if (min == layer && max == layerTop) {
            return;
        }
        if (min > max || max < minY || min > maxY) {
            applyLayer();
            return;
        }
        String before = describeLayers();
        List<BlockPos> left = new ArrayList<>();
        if (min > layer) {
            for (int y = layer; y <= Math.min(layerTop, min - 1); y++) {
                collectUnfinished(mc.level, schematic, areas, y, left);
                doneLayers.add(y);
            }
            AutoBuild.INSTANCE.deferred.addAll(left);
        }
        layer = Math.max(minY, min);
        layerTop = Math.min(maxY, max);
        applyLayer();
        resetProgress();
        String now = describeLayers();
        Chat.info("Layer view changed by hand: building " + now + " now" + (left.isEmpty() ? "" : " (" + left.size()
                + " block" + (left.size() == 1 ? "" : "s") + " left on " + before + " are done at the end)") + ".");
        BuildLog.line("LAYER", "changed by hand from " + before + " to " + now + "; " + left.size() + " unfinished blocks of "
                + before + " wait until the end");
        RunLog.user("layer-changed", "from=\"" + before + "\" to=\"" + now + "\" leftForTheEnd=" + left.size());
    }

    /** Adds the unfinished blocks of one layer (those that are loaded) to the list. */
    private static void collectUnfinished(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y, List<BlockPos> out) {
        boolean extraCounts = Configs.Layers.EXTRA_BLOCKS_BLOCK_LAYER.getBooleanValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : areas) {
            if (y < area.minY() || y > area.maxY()) {
                continue;
            }
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    pos.set(x, y, z);
                    BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
                    if (result != BlockCheck.Result.UNLOADED && unfinished(level, schematic, pos, result, extraCounts)) {
                        out.add(pos.immutable());
                    }
                }
            }
        }
    }

    /** Whether a block with this check result still has to be done for the layer to be finished. */
    private static boolean unfinished(Level level, WorldSchematic schematic, BlockPos pos, BlockCheck.Result result, boolean extraCounts) {
        return switch (result) {
            case OK -> false;
            // A block of the "always break" list (a cactus flower where the farm has air): the layer isn't done
            // while it's there, so the player goes there and it's broken
            case EXTRA -> extraCounts || BlockBreaker.breakAnyway(level, schematic, pos);
            // Something that mustn't be broken (a container, an unbreakable block) where the schematic wants another
            // block: never done by the add-on, so it doesn't hold the layer up; listed at the end, to fix by hand (a
            // hopper of the player's own where the redstone goes kept a layer waiting 30 s, and the build 20 s more).
            // Where the schematic has air: one block too many, like EXTRA
            case PROTECTED -> {
                if (!schematic.getBlockState(pos).isAir()) {
                    AutoBuild.INSTANCE.couldNotPlace.add(pos.immutable());
                    yield false;
                }
                yield extraCounts && !AutoBuild.INSTANCE.couldNotPlace.contains(pos);
            }
            case WRONG_BLOCK -> !AutoBuild.INSTANCE.isConflict(pos, result, level.getBlockState(pos))
                    && !AutoBuild.INSTANCE.couldNotPlace.contains(pos);
            default -> !AutoBuild.INSTANCE.couldNotPlace.contains(pos);
        };
    }

    private void applyLayer() {
        LayerRange range = DataManager.getRenderLayerRange();
        if (range.getAxis() != Direction.Axis.Y) {
            range.setAxis(Direction.Axis.Y);
        }

        if (layerTop == layer) {
            if (range.getLayerMode() != LayerMode.SINGLE_LAYER) {
                range.setLayerMode(LayerMode.SINGLE_LAYER, false);
            }
            range.setLayerSingle(layer);
            return;
        }

        if (range.getLayerMode() != LayerMode.LAYER_RANGE) {
            range.setLayerMode(LayerMode.LAYER_RANGE, false);
        }
        // Each setter is limited by the other end, so move the end in the direction we're going first
        if (layer > range.getLayerRangeMax()) {
            range.setLayerRangeMax(layerTop);
            range.setLayerRangeMin(layer);
        } else {
            range.setLayerRangeMin(layer);
            range.setLayerRangeMax(layerTop);
        }
    }

    /** Built together with the layer below at least this many of the blocks still to place stand on it ... */
    private static final int TOGETHER_MIN = 32;
    /** ... and at most one in this many is anything else (the purple concrete along the edge of the player's farm). */
    private static final int TOGETHER_OTHERS = 5;

    /**
     * The top of the layers built together from this one: the next one too when what's still to place there stands
     * on this layer's blocks (seeds and stems on its farmland, redstone dust on its observers and pistons). Those go
     * in right behind the block under them, in the same lanes, instead of a whole pass over the build of their own.
     * Not when water goes in up there (it would run down where this layer isn't built yet).
     */
    private int topFor(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y) {
        if (!Configs.Layers.LAYERS_TOGETHER.getBooleanValue() || y >= maxY || doneLayers.contains(y + 1)) {
            return y;
        }
        int onTop = 0;
        int others = 0;
        int water = 0;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : areas) {
            if (y + 1 < area.minY() || y + 1 > area.maxY()) {
                continue;
            }
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    pos.set(x, y + 1, z);
                    BlockState target = schematic.getBlockState(pos);
                    if (target.isAir()) {
                        continue;
                    }
                    BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
                    if (result == BlockCheck.Result.OK || result == BlockCheck.Result.UNLOADED) {
                        continue;
                    }
                    if (!target.getFluidState().isEmpty()) {
                        water++;
                    } else if (onLowerLayer(schematic, pos, target)) {
                        onTop++;
                        kinds.merge(blockName(target), 1, Integer::sum);
                    } else {
                        others++;
                    }
                }
            }
        }
        boolean together = water == 0 && onTop >= TOGETHER_MIN && others * TOGETHER_OTHERS <= onTop;
        // Cactus on the upper layer whose sand comes one layer higher: built with that layer instead. Paired with the
        // layer below, the cactus stood open on top until the next pass, and grew a block or two with a flower on it
        // (552 flowers where string and sand go, grown cactus to break, the player brushing them)
        if (together && cactusOpenOnTop(level, schematic, areas, y + 1)) {
            BuildLog.line("LAYER", "Y " + (y + 1) + " not built together with Y " + y + ": its cactus would stand open on top until Y "
                    + (y + 2) + " is built; Y " + (y + 1) + " goes with Y " + (y + 2) + " instead");
            return y;
        }
        if (onTop > 0) {
            BuildLog.line("LAYER", String.format("Y %d %s Y %d: on Y %d, %d blocks still to place stand on Y %d %s, %d others%s",
                    y + 1, together ? "built together with" : "not built together with", y, y + 1, onTop, y, kinds, others,
                    water > 0 ? ", " + water + " with water" : ""));
        }
        return together ? y + 1 : y;
    }

    /**
     * Layer {@code y} has cactus still to go in, and the layer above it has blocks still to go in that stand on layer
     * {@code y} (the sand on its string, beside the space each cactus grows into): built with that layer, the cactus
     * can't grow while it waits.
     */
    private boolean cactusOpenOnTop(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y) {
        if (y + 1 > maxY || doneLayers.contains(y + 1)) {
            return false;
        }
        int cactus = 0;
        int above = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : areas) {
            if (y < area.minY() || y + 1 > area.maxY()) {
                continue;
            }
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    pos.set(x, y, z);
                    BlockState target = schematic.getBlockState(pos);
                    if (target.getBlock() instanceof net.minecraft.world.level.block.CactusBlock
                            && BlockCheck.check(level, schematic, pos) == BlockCheck.Result.MISSING) {
                        cactus++;
                    }
                    pos.set(x, y + 1, z);
                    BlockState up = schematic.getBlockState(pos);
                    if (!up.isAir() && up.getFluidState().isEmpty() && onLowerLayer(schematic, pos, up)
                            && BlockCheck.check(level, schematic, pos) == BlockCheck.Result.MISSING) {
                        above++;
                    }
                }
            }
        }
        return cactus >= TOGETHER_MIN && above >= TOGETHER_MIN;
    }

    /**
     * A block of the upper layer that can only go in once the lower one is there: it stands on it, or it's string
     * hanging on the side of one that does (the cactus beside it in a cactus farm).
     */
    private static boolean onLowerLayer(WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (standsOn(schematic, pos, target)) {
            return true;
        }
        if (target.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(direction);
                if (standsOn(schematic, side, schematic.getBlockState(side))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean standsOn(WorldSchematic schematic, BlockPos pos, BlockState target) {
        return !target.isAir() && PlacementOrder.standsOnBelow(target) && !schematic.getBlockState(pos.below()).isAir();
    }

    private int scanLayers(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas,
                           @Nullable List<BlockPos> examples) {
        return scanLayers(level, schematic, areas, examples, null);
    }

    private int scanLayers(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas,
                           @Nullable List<BlockPos> examples, @Nullable Findings findings) {
        int remaining = 0;
        for (int y = layer; y <= layerTop; y++) {
            remaining += scan(level, schematic, areas, y, examples, findings);
        }
        return remaining;
    }

    /** Deferred blocks that still aren't right. */
    private static List<BlockPos> unfinishedDeferred(Level level, WorldSchematic schematic) {
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos pos : AutoBuild.INSTANCE.deferred) {
            BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
            if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                left.add(pos);
            }
        }
        return left;
    }

    /**
     * The shopping list: items the current layers still need that aren't in the inventory. Said in chat when it
     * changes; later used to buy from the shop.
     */
    private long lastNeedsMessage = -100000;

        private void updateShoppingList(Minecraft mc, java.util.Map<net.minecraft.world.item.Item, Integer> needs) {
        if (mc.player == null || mc.player.getAbilities().instabuild) {
            return;
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> missing = new java.util.LinkedHashMap<>();
        for (var entry : needs.entrySet()) {
            int have = mc.player.getInventory().countItem(entry.getKey());
            if (have < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - have);
            }
        }

        if (!missing.equals(AutoBuild.INSTANCE.shoppingList)) {
            // Counts change with every block placed: say it when another item is missing, or once a minute
            boolean newItems = !AutoBuild.INSTANCE.shoppingList.keySet().containsAll(missing.keySet());
            AutoBuild.INSTANCE.shoppingList.clear();
            AutoBuild.INSTANCE.shoppingList.putAll(missing);
            if (!missing.isEmpty() && (newItems || AutoBuild.clientTicks - lastNeedsMessage > 20 * 60)) {
                lastNeedsMessage = AutoBuild.clientTicks;
                StringBuilder sb = new StringBuilder(describeLayers() + " still needs: ");
                missing.forEach((item, count) -> sb.append(count).append(' ')
                        .append(new ItemStack(item).getHoverName().getString()).append(", "));
                // The on-screen display shows what's missing, and it's fetched or bought: in chat only when the player
                // has to bring it (a water bucket or tools coming and going said it again every few seconds)
                boolean getsItself = Configs.Shop.AUTO_BUY.getBooleanValue() || ChestFetcher.hasStorage(mc);
                if (getsItself) {
                    BuildLog.line("NEEDS", sb.substring(0, sb.length() - 2));
                } else {
                    Chat.warnEvery("needs", 5 * 60_000L, sb.substring(0, sb.length() - 2));
                }
            }
        }
    }

    /** At the end: say if anything couldn't be finished (with where), so nothing is silently left out. */
    private void reportUnfinished(Minecraft mc, WorldSchematic schematic) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        List<String> examples = new ArrayList<>();

        for (BlockPos skipped : AutoBuild.INSTANCE.couldNotPlace) {
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, skipped);
            if (result != BlockCheck.Result.OK) {
                String name = schematic.getBlockState(skipped).getBlock().getName().getString();
                counts.merge(name, 1, Integer::sum);
                if (examples.size() < MAX_REPORTED * 2) {
                    examples.add(name + " at " + skipped.getX() + " " + skipped.getY() + " " + skipped.getZ());
                }
            }
        }

        if (counts.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("Couldn't finish: ");
        counts.forEach((name, count) -> sb.append(count).append(' ').append(name).append(", "));
        Chat.warn(sb.substring(0, sb.length() - 2));
        for (String example : examples) {
            Chat.warn(" - " + example);
        }
    }

    private void resetProgress() {
        completeChecks = 0;
        waitingChecks = 0;
        lastRemaining = -1;
        lowestRemaining = Integer.MAX_VALUE;
        forceDone = false;
        ticksWithoutProgress = 0;
        reportedStuck = false;
        ticksUntilCheck = 0;
        // Not known yet whether the new layer has water: nothing else goes in until the first check says
        waterPending = -1;
    }

    private void reportStuck(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas) {
        int remaining = scanLayers(mc.level, schematic, areas, null);
        // The nearest ones, not the first in the area (those were all in a far corner that wasn't loaded)
        List<BlockPos> positions = nearestUnfinished(mc, schematic, areas, MAX_REPORTED);

        Chat.warn(describeLayers() + ": nothing placed for " + ticksWithoutProgress / 20 + " s. " + remaining
                + " left. Player at " + mc.player.blockPosition().toShortString() + ", doing: " + AutoBuild.INSTANCE.doing());
        PrinterAddon.LOGGER.warn("[stuck] {}: nothing placed for {} s, {} left, player at {}, doing: {}", describeLayers(),
                ticksWithoutProgress / 20, remaining, mc.player.blockPosition().toShortString(), AutoBuild.INSTANCE.doing());
        BuildLog.line("STUCK", "mover " + AutoBuild.INSTANCE.mover.details() + " | " + AutoBuild.INSTANCE.doing()
                + " | printer: " + AutoBuild.INSTANCE.lastUnfinishedCount + " unfinished in its range, " + AutoBuild.INSTANCE.lastHeldCount
                + " held back, " + AutoBuild.INSTANCE.lastOfferedCount + " offered | last pause: " + AutoBuild.INSTANCE.pauseReason);
        int shown = 0;
        for (BlockPos pos : positions) {
            String why = Diagnose.explain(mc, schematic, pos);
            PrinterAddon.LOGGER.warn("[stuck]   {}", why);
            BuildLog.line("STUCK", why);
            if (shown++ < 4) {
                Chat.warn(" - " + why);
            }
        }
    }

    /** The unfinished blocks of the current layers nearest to the player; parts that aren't loaded only if nothing else is left. */
    private List<BlockPos> nearestUnfinished(Minecraft mc, WorldSchematic schematic, List<SchematicHelper.Area> areas, int count) {
        net.minecraft.world.phys.Vec3 from = mc.player.position();
        java.util.Comparator<BlockPos> farthestFirst = java.util.Comparator.comparingDouble(
                (BlockPos p) -> from.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))).reversed();
        java.util.PriorityQueue<BlockPos> nearest = new java.util.PriorityQueue<>(farthestFirst);
        List<BlockPos> notLoaded = new ArrayList<>();
        boolean extraCounts = Configs.Layers.EXTRA_BLOCKS_BLOCK_LAYER.getBooleanValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = layer; y <= layerTop; y++) {
            for (SchematicHelper.Area area : areas) {
                if (y < area.minY() || y > area.maxY()) {
                    continue;
                }
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (!unfinished(mc.level, schematic, pos, result, extraCounts)) {
                            continue;
                        }
                        if (result == BlockCheck.Result.UNLOADED) {
                            if (notLoaded.size() < count) {
                                notLoaded.add(pos.immutable());
                            }
                            continue;
                        }
                        nearest.add(pos.immutable());
                        if (nearest.size() > count) {
                            nearest.poll();
                        }
                    }
                }
            }
        }
        List<BlockPos> out = new ArrayList<>(nearest);
        out.sort(farthestFirst.reversed());
        return out.isEmpty() ? notLoaded : out;
    }

    private static String describe(Minecraft mc, BlockCheck.Result result, BlockState target) {
        String name = target.getBlock().getName().getString();

        return switch (result) {
            case MISSING -> {
                ItemStack needed = StateRules.itemFor(target);
                boolean have = mc.player != null && !needed.isEmpty()
                        && mc.player.getInventory().contains(s -> s.is(needed.getItem()));
                yield "needs " + name + (have ? " (in inventory, move closer?)" : " (§cnot in inventory§e)");
            }
            case WRONG_BLOCK, WRONG_STATE -> "wrong block, should be " + name + " (out of reach, or can't be broken?)";
            case PRINTER_FIXES -> "should be " + name + ", waiting for the printer to click it";
            case PROTECTED -> "wrong, but it's a container or unbreakable, fix it by hand";
            case EXTRA -> "should be air (use 'Clear extra blocks')";
            case UNLOADED -> "chunk not loaded, move closer";
            case BUCKET -> "needs a bucket of lava or water" + (Fluids.bucketFor(target) != null
                    && mc.player != null && mc.player.getInventory().contains(s -> s.is(Fluids.bucketFor(target)))
                    ? " (in inventory, move closer?)" : " (\u00a7cnot in inventory\u00a7e)");
            case OK -> "ok";
        };
    }

    /** What a scan found besides the count. */
    private static final class Findings {
        /** Missing blocks that can't be placed yet (they need something that isn't there). */
        final List<BlockPos> waiting = new ArrayList<>();
        /** Items the missing blocks need. */
        final java.util.Map<net.minecraft.world.item.Item, Integer> needs = new java.util.LinkedHashMap<>();
        /** Farmland still to till (placed as dirt and tilled): that needs a hoe. */
        int tilling;
        /** Unfinished blocks that hold water (waterlogged or water), for the build log. */
        int water;
        /** Missing blocks with nothing beside or under them to place them against, now or later (see AirPlace). */
        int floating;
        /** Missing blocks that could come out wrong placed in the air (they face a way, see AirPlace#airSafe). */
        int notAirSafe;
    }

    /**
     * Counts unfinished positions in one layer. If {@code examples} is given, the first few are added to it;
     * if {@code findings} is given, blocks that have to wait and the items needed are collected in it.
     */
    private static int scan(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y,
                            @Nullable List<BlockPos> examples) {
        return scan(level, schematic, areas, y, examples, null);
    }

    private static int scan(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y,
                            @Nullable List<BlockPos> examples, @Nullable Findings findings) {
        return scan(level, schematic, areas, y, examples, findings, null);
    }

    private static int scan(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y,
                            @Nullable List<BlockPos> examples, @Nullable Findings findings, @Nullable Parts parts) {
        boolean extraCounts = Configs.Layers.EXTRA_BLOCKS_BLOCK_LAYER.getBooleanValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int remaining = 0;

        for (SchematicHelper.Area area : areas) {
            if (y < area.minY() || y > area.maxY()) {
                continue;
            }

            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    pos.set(x, y, z);
                    BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
                    long part = parts == null ? 0 : chunkKey(x, z);
                    if (parts != null) {
                        if (result == BlockCheck.Result.UNLOADED) {
                            parts.unloaded.add(part);
                            if (parts.seenFinished.contains(part)) {
                                continue;
                            }
                            parts.unloadedBlocks++;
                        } else {
                            parts.loaded.add(part);
                        }
                    }

                    if (unfinished(level, schematic, pos, result, extraCounts)) {
                        remaining++;
                        if (parts != null && result != BlockCheck.Result.UNLOADED) {
                            parts.unfinished.add(part);
                        }
                        if (findings != null && PlacementOrder.holdsWater(schematic.getBlockState(pos))) {
                            findings.water++;
                        }
                        if (examples != null && examples.size() < MAX_REPORTED) {
                            examples.add(pos.immutable());
                        }
                        if (findings != null && result == BlockCheck.Result.BUCKET && Fluids.bucketFor(schematic.getBlockState(pos)) != null) {
                            // One bucket does them all (an infinite water bucket, or refilled from water nearby)
                            findings.needs.put(Fluids.bucketFor(schematic.getBlockState(pos)), 1);
                        }
                        if (findings != null && result == BlockCheck.Result.MISSING) {
                            BlockState target = schematic.getBlockState(pos);
                            // Blocks waiting for the layer's water aren't waiting for something above
                            if (!PlacementOrder.isReady(level, schematic, pos, target)
                                    && PlacementOrder.waterFirst(level, schematic, pos, target) == null
                                    && PlacementOrder.waterBelow(level, schematic, pos) == null) {
                                findings.waiting.add(pos.immutable());
                            }
                            ItemStack item = StateRules.itemFor(target);
                            if (!item.isEmpty()) {
                                findings.needs.merge(item.getItem(), 1, Integer::sum);
                            }
                            if (AirPlace.floatsForGood(level, schematic, pos)) {
                                findings.floating++;
                            }
                            if (!AirPlace.airSafe(target)) {
                                findings.notAirSafe++;
                            }
                        }
                        if (findings != null && (result == BlockCheck.Result.MISSING || result == BlockCheck.Result.PRINTER_FIXES)
                                && schematic.getBlockState(pos).getBlock() == Blocks.FARMLAND) {
                            findings.tilling++;
                        }
                    }
                }
            }
        }

        return remaining;
    }

    /** One layer read for the start: what's left (in the parts that are loaded), not loaded, placed. */
    private static final class LayerScan {
        int unfinished;
        int unloaded;
        int placed;
        /** Blocks of the layer in the loaded parts (not air in the schematic). */
        int blocks;
        final List<BlockPos> positions = new ArrayList<>();

        static LayerScan of(Level level, WorldSchematic schematic, List<SchematicHelper.Area> areas, int y, int keep) {
            LayerScan scan = new LayerScan();
            boolean extraCounts = Configs.Layers.EXTRA_BLOCKS_BLOCK_LAYER.getBooleanValue();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (SchematicHelper.Area area : areas) {
                if (y < area.minY() || y > area.maxY()) {
                    continue;
                }
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockCheck.Result result = BlockCheck.check(level, schematic, pos);
                        if (result == BlockCheck.Result.UNLOADED) {
                            scan.unloaded++;
                            continue;
                        }
                        boolean block = !schematic.getBlockState(pos).isAir();
                        if (block) {
                            scan.blocks++;
                        }
                        if (unfinished(level, schematic, pos, result, extraCounts)) {
                            scan.unfinished++;
                            if (scan.positions.size() < keep) {
                                scan.positions.add(pos.immutable());
                            }
                        } else if (block && result == BlockCheck.Result.OK) {
                            scan.placed++;
                        }
                    }
                }
            }
            return scan;
        }
    }
}
