package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.StatusFile;
import no.sindrens.printeraddon.util.Chat;

/**
 * Ties the parts together every tick: safety checks, layer stepping, breaking, and the printer's own switch.
 */
public class AutoBuild {
    public static final AutoBuild INSTANCE = new AutoBuild();

    /** One-off breaking started from a key, independent of auto build. */
    private enum ManualTask { NONE, FIX_WRONG, CLEAR_EXTRA }

    public final BlockBreaker breaker = new BlockBreaker();
    public final LayerManager layers = new LayerManager();
    public final Mover mover = new Mover();
    public final AirPlace airPlace = new AirPlace();
    public final Fluids fluids = new Fluids();
    /** Temporary rails are removed through the same list as misplaced air-placed blocks. */
    public final RailHelper rails = new RailHelper(airPlace.misplaced());
    public final EntityPlacer entities = new EntityPlacer();
    public final Crafter crafter = new Crafter();
    public final ChestFetcher fetcher = new ChestFetcher();
    public final InventoryWatch inventory = new InventoryWatch();
    /** What the printer was last offered (for the idle analysis in the debug log). */
    public long lastOfferedTick;
    public int lastOfferedCount;
    public int lastRawCount;
    public int lastExecutableCount;
    public final AutoEat eater = new AutoEat();
    private final HotbarPrep hotbar = new HotbarPrep();
    /** The player's render distance while a lower one is used for building (0 = not changed). */
    private int savedRenderDistance;
    /** Crafting to do once the current purchase is done. */
    private java.util.List<CraftPlan.Step> craftAfterBuying = java.util.List.of();
    /** Items a crafting round was for: if they're still missing afterwards, crafting didn't work out. */
    private java.util.Set<net.minecraft.world.item.Item> craftingFor = java.util.Set.of();
    private final InventoryCleaner cleaner = new InventoryCleaner();
    public final BuildStats stats = new BuildStats();
    public final SpeedTuner speed = new SpeedTuner();
    /** Client ticks since the game started (game time can stand still, e.g. with /tick freeze). */
    public static long clientTicks;
    /** Spots the printer just placed (game tick of the attempt): not given to it again until the server has answered. */
    private final java.util.Map<net.minecraft.core.BlockPos, Long> recentlyTried = new java.util.HashMap<>();

    /** When the printer last queued a block (for turning the head back once it pauses). */
    public long lastQueuedTick;

    /** The item the printer picked for its last block (for an empty hand at the click, see InteractActionMixin). */
    @javax.annotation.Nullable
    public net.minecraft.world.item.Item lastTriedItem;

    /** The printer is about to click this spot with this item (null: none in particular). */
    public void printerTried(net.minecraft.core.BlockPos pos, @javax.annotation.Nullable net.minecraft.world.item.Item item) {
        Minecraft mc = Minecraft.getInstance();
        lastTriedItem = item;
        if (mc.level != null) {
            ServerAcks.tried(pos);
            recentlyTried.put(pos.immutable(), clientTicks);
            lastTried.put(pos.immutable(), clientTicks);
            triedDuringBuild.add(pos.immutable());
            if (BuildLog.isOpen()) {
                fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
                BuildLog.line("TRY", String.format("%s: is %s, wants %s | with %s, in hand %s | %.2f from the eye | player at %.1f %.1f %.1f",
                        pos.toShortString(), shortState(mc.level.getBlockState(pos)),
                        schematic != null ? shortState(schematic.getBlockState(pos)) : "?",
                        item == null ? "nothing" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath(),
                        mc.player.getMainHandItem().isEmpty() ? "nothing" : mc.player.getMainHandItem().getCount() + " "
                                + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).getPath(),
                        Math.sqrt(mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))),
                        mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            }
            speed.picked(mc, pos, item);
            // The same click again and again with nothing changing (the server refuses it): leave that block for a
            // while instead of standing there; the player moves on and it's tried again later, from elsewhere
            net.minecraft.world.level.block.state.BlockState now = mc.level.getBlockState(pos);
            Tries tries = triesAt.get(pos);
            if (tries == null || tries.state() != now || clientTicks - tries.since() > 20 * 30) {
                triesAt.put(pos.immutable(), new Tries(now, 1, clientTicks));
            } else if (tries.count() + 1 >= MAX_TRIES) {
                triesAt.remove(pos);
                givenUp.put(pos.immutable(), clientTicks + GIVE_UP_TICKS);
                PrinterAddon.debug("The printer tried {} {} times and nothing changed (is {}): leaving it for 30 s",
                        pos.toShortString(), MAX_TRIES, now.getBlock().getName().getString());
                fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
                if (schematic != null) {
                    BuildLog.line("GIVEUP", pos.toShortString() + " for 30 s, clicked " + MAX_TRIES + " times without a change | "
                            + Diagnose.explain(mc, schematic, pos));
                }
            } else {
                triesAt.put(pos.immutable(), new Tries(now, tries.count() + 1, tries.since()));
            }
        }
    }

    private record Tries(net.minecraft.world.level.block.state.BlockState state, int count, long since) {
    }

    /** When the printer last clicked each spot (kept a few seconds, for telling its blocks from other changes). */
    private final java.util.Map<net.minecraft.core.BlockPos, Long> lastTried = new java.util.HashMap<>();

    /** True if the printer clicked this spot within the last {@code ticks}. */
    public boolean triedRecently(net.minecraft.core.BlockPos pos, long ticks) {
        Long at = lastTried.get(pos);
        if (lastTried.size() > 512) {
            lastTried.values().removeIf(t -> clientTicks - t > 20 * 10);
        }
        return at != null && clientTicks - at <= ticks;
    }

    private static final int MAX_TRIES = 4;
    private static final int GIVE_UP_TICKS = 20 * 30;
    private final java.util.Map<net.minecraft.core.BlockPos, Tries> triesAt = new java.util.HashMap<>();
    private final java.util.Map<net.minecraft.core.BlockPos, Long> givenUp = new java.util.HashMap<>();

    /** True if the printer gave up on any block for now. */
    public boolean hasGivenUp() {
        givenUp.values().removeIf(until -> until < clientTicks);
        return !givenUp.isEmpty();
    }

    /** Nothing else is left to do: the blocks the printer gave up on are tried again now (see Mover). */
    public void forgetGivenUp() {
        givenUp.clear();
        triesAt.clear();
    }

    /** True for a block the printer kept trying without anything changing (left alone for 30 s). */
    public boolean givenUp(net.minecraft.core.BlockPos pos) {
        Long until = givenUp.get(pos);
        if (until == null) {
            return false;
        }
        if (until < clientTicks) {
            givenUp.remove(pos);
            return false;
        }
        return true;
    }
    /** What the whole schematic still needs (item -> count), including what's in the inventory. */
    private final java.util.Map<net.minecraft.world.item.Item, Integer> neededWhole = new java.util.HashMap<>();
    /** Items the shop couldn't sell or deliver during this build: not tried again. */
    private final java.util.Map<net.minecraft.world.item.Item, Long> couldntBuy = new java.util.HashMap<>();
    /** An item the shop couldn't deliver is tried again after this long (a menu that didn't open once isn't forever). */
    private static final long RETRY_BUYING = 20 * 60 * 5;

    private void cantBuy(java.util.Collection<net.minecraft.world.item.Item> items) {
        items.forEach(item -> couldntBuy.put(item, clientTicks + RETRY_BUYING));
    }

    /** True while the shop is known not to deliver this item (for a few minutes after it didn't). */
    private boolean cantGet(net.minecraft.world.item.Item item) {
        Long until = couldntBuy.get(item);
        if (until == null) {
            return false;
        }
        if (clientTicks >= until) {
            couldntBuy.remove(item);
            return false;
        }
        return true;
    }

    /** The item can be got: bought as it is, or made from something the shop delivers (dust from redstone blocks). */
    private boolean gettable(Minecraft mc, net.minecraft.world.item.Item item) {
        if (!cantGet(item)) {
            return true;
        }
        net.minecraft.world.item.Item from = CraftPlan.madeFrom(item);
        return from != null && !cantGet(from) && no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, from);
    }
    private boolean buying;
    private int buyCooldown;
    /** When storage was last looked through for a water bucket, and when its lack was last said. */
    private long bucketLookedFor = -100000;
    private long bucketWarned = -100000;
    private int shoppingLayer = Integer.MIN_VALUE;
    private final Safety safety = new Safety();

    private boolean running;
    private boolean printerWasOn;
    private boolean airPlaceWasOn;
    private ManualTask manualTask = ManualTask.NONE;
    /**
     * Spots that were empty at some point during this build. A container there was placed during the build
     * (by the printer, in the wrong spot): it holds nothing of the player's and may be broken.
     */
    private final java.util.Set<net.minecraft.core.BlockPos> emptyDuringBuild = new java.util.HashSet<>();
    /**
     * Blocks whose layer was finished while they still waited for something in a higher layer (an ascending
     * rail, an observer watching a block above). They are placed as soon as what they need is there.
     */
    public final java.util.Set<net.minecraft.core.BlockPos> deferred = new java.util.LinkedHashSet<>();
    /** Blocks the printer couldn't place although the items were there: listed at the end to do by hand. */
    public final java.util.Set<net.minecraft.core.BlockPos> couldNotPlace = new java.util.LinkedHashSet<>();
    /** The block the printer will place next, for the status line. */
    @javax.annotation.Nullable
    private net.minecraft.core.BlockPos nextBlock;
    /** Items the whole schematic still needs that aren't in the inventory. */
    public final java.util.Map<net.minecraft.world.item.Item, Integer> missingWhole = new java.util.LinkedHashMap<>();
    private int statusTicks;
    /** Items the current layers need that aren't in the inventory (what to buy). */
    public final java.util.Map<net.minecraft.world.item.Item, Integer> shoppingList = new java.util.LinkedHashMap<>();
    private int debugTicks;

    private AutoBuild() {
    }

    /** The printer is paused while a block is being broken, so the two don't swap the held item. */
    /** Why the printer was last paused (for the stats). */
    public String pauseReason = "-";

    public boolean shouldPausePrinter() {
        String why = pauseReasonNow();
        if (why != null) {
            pauseReason = why;
        }
        return why != null;
    }

    @javax.annotation.Nullable
    private String pauseReasonNow() {
        if (running) {
            // Not while flying to the next spot: blocks placed on the move come out wrong
            if (Minecraft.getInstance().player != null && mover.isTravelling(Minecraft.getInstance().player)) {
                return "flying";
            }
            // Optional cap for strict anticheats: no more than this many placements per second
            int cap = Configs.Speed.MAX_PLACEMENTS_PER_SECOND.getIntegerValue();
            if (cap > 0 && stats.placedInLastSecond() >= cap) {
                return "placement cap";
            }
            // A swap still on its way to the server would make the printer click with the wrong item
            inventory.update(Minecraft.getInstance());
            if (inventory.settling()) {
                return "item swap settling";
            }
            if (ServerLag.shouldWait(Minecraft.getInstance())) {
                return "server lag";
            }
        }
        if (breaker.isBusy()) {
            return "breaking";
        }
        if (eater.isBusy()) {
            return "eating";
        }
        if (fluids.isBusy()) {
            return "bucket";
        }
        return pausedByOthers() ? "chest/shop/crafting/rails" : null;
    }

    private boolean pausedByOthers() {
        return breaker.isBusy() || eater.isBusy() || fluids.isBusy() || rails.isBusy() || entities.isBusy() || crafter.isBusy() || fetcher.isBusy()
                || no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning() || no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.isRunning();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * What the printer gets to see: during auto build only blocks that are ready, full blocks first, plus deferred
     * blocks within reach that have become ready. During air place only the one block it's meant for.
     */
    private boolean nextToUnconfirmed(net.minecraft.core.BlockPos pos, long now, long confirm) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            Long at = recentlyTried.get(pos.relative(direction));
            if (at != null && now - at < confirm) {
                return true;
            }
        }
        return false;
    }

    /** Of the printer's last offer: unfinished blocks in its range, and of those held back as just tried. */
    public int lastUnfinishedCount;
    public int lastHeldCount;

    public java.util.List<net.minecraft.core.BlockPos> filterPrinterPositions(java.util.List<net.minecraft.core.BlockPos> positions) {
        long started = Perf.start();
        java.util.List<net.minecraft.core.BlockPos> result = filterPositions(positions);
        if (running) {
            Perf.end("printer's block list", started);
        }
        return result;
    }

    private java.util.List<net.minecraft.core.BlockPos> filterPositions(java.util.List<net.minecraft.core.BlockPos> positions) {
        Minecraft mc = Minecraft.getInstance();
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (!running || mc.player == null || mc.level == null || schematic == null) {
            return positions;
        }
        if (airPlace.hasAnchor()) {
            return airPlace.filterPrinterPositions(positions);
        }

        java.util.List<net.minecraft.core.BlockPos> all = positions;
        if (!deferred.isEmpty()) {
            all = new java.util.ArrayList<>(positions);
            double range = me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
            net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
            for (net.minecraft.core.BlockPos pos : deferred) {
                if (eye.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= range * range && !all.contains(pos)) {
                    all.add(pos);
                }
            }
        }
        java.util.List<net.minecraft.core.BlockPos> forPrinter = new java.util.ArrayList<>(all.size());
        // A spot tried a moment ago may just not have the server's answer yet: trying it again places a second
        // block next to it (the wrong blocks seen at fast intervals)
        long now = AutoBuild.clientTicks;
        // Long enough for the server's answer to arrive, also with a high ping
        // (the client shows a placed block at once, so this only matters for clicks that didn't work out; on farmland
        // the same spot is clicked twice: dirt, then tilling)
        long holdBack = Math.max(8, 2L * ServerLag.pingTicks(mc) + 6);
        recentlyTried.values().removeIf(at -> now - at > holdBack);
        // Nor next to a block clicked a moment ago: the printer may click against that neighbour, and if the server
        // doesn't have it yet, the server puts the new block INTO the neighbour's spot (dirt where mud should go)
        long confirm = Math.max(3, 2L * ServerLag.pingTicks(mc) + 2);
        int unfinished = 0;
        int held = 0;
        // When the server answers each click (it does from 1.19 on): exactly until it has, else the fixed waits
        boolean acks = ServerAcks.works();
        for (net.minecraft.core.BlockPos pos : all) {
            if (schematic.getBlockState(pos).isAir()) {
                continue;
            }
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            if (result == BlockCheck.Result.OK) {
                continue;
            }
            unfinished++;
            boolean waiting = acks ? ServerAcks.isWaiting(pos) : recentlyTried.containsKey(pos);
            boolean nextToWaiting = result == BlockCheck.Result.MISSING
                    && (acks ? ServerAcks.nextToWaiting(pos) : nextToUnconfirmed(pos, now, confirm));
            if (waiting || givenUp(pos) || nextToWaiting) {
                held++;
                continue;
            }
            forPrinter.add(pos);
        }
        lastUnfinishedCount = unfinished;
        lastHeldCount = held;
        lastOfferedTick = clientTicks;
        lastRawCount = all.size();
        java.util.List<net.minecraft.core.BlockPos> ordered = PlacementOrder.order(mc.level, schematic, forPrinter, mover.orderFrom(mc.player),
                mc.player.getMainHandItem().isEmpty() ? null : mc.player.getMainHandItem().getItem());
        if (!ordered.isEmpty()) {
            nextBlock = ordered.getFirst();
        }
        lastOfferedCount = ordered.size();
        // For the idle analysis: how many of them a printer guide can actually do now
        lastExecutableCount = 0;
        {
            // Only whether there's at least one (cheap: stops at the first)
            for (net.minecraft.core.BlockPos pos : ordered) {
                if (Clicks.printerCan(mc, pos)) {
                    lastExecutableCount++;
                    break;
                }
            }
        }
        return ordered;
    }

    /** Blocks still to place in the whole schematic (as last counted). */
    public int blocksLeft() {
        int left = 0;
        for (int count : neededWhole.values()) {
            left += count;
        }
        return left;
    }

    /** What auto build is doing right now, in words. */
    public String doing() {
        if (breaker.isBusy()) {
            return "breaking a wrong block";
        }
        if (fluids.isBusy()) {
            return "using a bucket";
        }
        if (rails.isBusy()) {
            return "placing a temporary rail";
        }
        if (airPlace.hasAnchor()) {
            return "air placing a floating block";
        }
        return mover.describe();
    }

    /** Status line above the hotbar, the whole-build shopping list and the status file. */
    private void updateStatus(Minecraft mc) {
        statusTicks++;
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return;
        }

        String next = "-";
        if (nextBlock != null) {
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, nextBlock);
            if (result == BlockCheck.Result.OK) {
                nextBlock = null;
            } else {
                next = schematic.getBlockState(nextBlock).getBlock().getName().getString() + " (" + nextBlock.toShortString() + ")";
            }
        }

        if (statusTicks % 10 == 0) {
            Chat.actionBar(layers.status() + "  |  Next: " + next + "  |  " + doing());
        }
        if (wholeFirstPass || statusTicks % 5 == 1) {
            long wholeStarted = Perf.start();
            updateMissingWhole(mc, schematic);
            Perf.end("whole schematic", wholeStarted);
        }
        if (statusTicks % 40 == 0 && Configs.Generic.STATUS_FILE.getBooleanValue()) {
            StatusFile.write(mc, layers.status(), next, nextBlock, doing(), shoppingList, missingWhole);
        }
    }

    /**
     * What the whole schematic still needs, per Y level. Read in small pieces (30 columns of one level per call), so
     * it never makes a lag spike: the first time through as many pieces per tick as fit in 3 ms (a 50 layer build is
     * read in a second or two), then a piece every 5 ticks. The finished layers are checked on the way (see
     * LayerManager#checked): a block missing there (broken, or never placed before a restart) is found.
     */
    private final java.util.Map<Integer, java.util.Map<net.minecraft.world.item.Item, Integer>> wholeByY = new java.util.HashMap<>();
    private final java.util.Map<net.minecraft.world.item.Item, Integer> wholePartial = new java.util.HashMap<>();
    private final java.util.List<net.minecraft.core.BlockPos> wholeMissing = new java.util.ArrayList<>();
    private int wholeNextY = Integer.MIN_VALUE;
    private int wholeNextX = Integer.MIN_VALUE;
    /** The level being read is a finished layer: its missing blocks are collected. */
    private boolean wholeChecking;
    /** Until every level has been read once. */
    private boolean wholeFirstPass = true;
    private static final int WHOLE_COLUMNS_PER_STEP = 30;
    private static final long WHOLE_FIRST_PASS_NANOS = 3_000_000L;

    /** What the whole schematic needs is known (every level read at least once). */
    public boolean wholeKnown() {
        return !wholeFirstPass;
    }

    private void updateMissingWhole(Minecraft mc, fi.dy.masa.litematica.world.WorldSchematic schematic) {
        if (schematic == null || mc.level == null) {
            return;
        }
        java.util.List<SchematicHelper.Area> areas = SchematicHelper.areas();
        int minY = areas.stream().mapToInt(SchematicHelper.Area::minY).min().orElse(0);
        int maxY = areas.stream().mapToInt(SchematicHelper.Area::maxY).max().orElse(-1);
        int minX = areas.stream().mapToInt(SchematicHelper.Area::minX).min().orElse(0);
        int maxX = areas.stream().mapToInt(SchematicHelper.Area::maxX).max().orElse(-1);
        if (maxY < minY) {
            return;
        }
        if (wholeNextY < minY || wholeNextY > maxY || wholeNextX < minX || wholeNextX > maxX) {
            wholeNextY = minY;
            wholeNextX = minX;
            wholePartial.clear();
            wholeMissing.clear();
        }
        long until = System.nanoTime() + WHOLE_FIRST_PASS_NANOS;
        boolean levelDone = false;
        do {
            if (wholeNextX == minX) {
                wholeChecking = layers.isActive() && layers.isDone(wholeNextY);
                wholeMissing.clear();
            }
            int toX = Math.min(maxX, wholeNextX + WHOLE_COLUMNS_PER_STEP - 1);
            needsAt(mc, schematic, areas, wholeNextY, wholeNextX, toX, wholeChecking ? wholeMissing : null)
                    .forEach((item, count) -> wholePartial.merge(item, count, Integer::sum));
            wholeNextX = toX + 1;
            if (wholeNextX <= maxX) {
                continue;
            }
            // This level is read through
            wholeByY.put(wholeNextY, new java.util.HashMap<>(wholePartial));
            wholePartial.clear();
            if (wholeChecking) {
                layers.checked(mc, wholeNextY, wholeMissing);
            }
            wholeMissing.clear();
            levelDone = true;
            wholeNextX = minX;
            if (++wholeNextY > maxY) {
                wholeNextY = minY;
                if (wholeFirstPass) {
                    wholeFirstPass = false;
                    BuildLog.line("SCAN", "the whole schematic is read: " + (maxY - minY + 1) + " levels");
                }
            }
        } while (wholeFirstPass && System.nanoTime() < until);
        if (!levelDone && !neededWhole.isEmpty()) {
            return;
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> needs = new java.util.LinkedHashMap<>();
        wholeByY.values().forEach(level -> level.forEach((item, count) -> {
            if (Fluids.isBucket(item)) {
                needs.put(item, 1);
            } else {
                needs.merge(item, count, Integer::sum);
            }
        }));
        publishWholeNeeds(mc, schematic, needs);
    }

    /** Items still needed in a part of one level; with {@code missing}, the blocks the printer still has to do there too. */
    private static java.util.Map<net.minecraft.world.item.Item, Integer> needsAt(Minecraft mc,
            fi.dy.masa.litematica.world.WorldSchematic schematic, java.util.List<SchematicHelper.Area> areas, int y,
            int fromX, int toX, @javax.annotation.Nullable java.util.List<net.minecraft.core.BlockPos> missing) {
        java.util.Map<net.minecraft.world.item.Item, Integer> needs = new java.util.HashMap<>();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : areas) {
            if (y < area.minY() || y > area.maxY()) {
                continue;
            }
            {
                for (int x = Math.max(fromX, area.minX()); x <= Math.min(toX, area.maxX()); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        net.minecraft.world.level.block.state.BlockState target = schematic.getBlockState(pos);
                        if (!target.isAir()) {
                            INSTANCE.schematicBlocks.add(target.getBlock());
                        }
                        if (result.needsBreaking() && INSTANCE.isConflict(pos, result, mc.level.getBlockState(pos))) {
                            continue;
                        }
                        if (missing != null && missing.size() < 4096
                                && (result == BlockCheck.Result.MISSING || result == BlockCheck.Result.PRINTER_FIXES)) {
                            missing.add(pos.immutable());
                        }
                        net.minecraft.world.item.Item item = null;
                        if (result == BlockCheck.Result.MISSING || result.needsBreaking()) {
                            net.minecraft.world.item.ItemStack stack =
                                    StateRules.itemFor(target);
                            item = stack.isEmpty() ? null : stack.getItem();
                        } else if (result == BlockCheck.Result.BUCKET) {
                            // One bucket does them all
                            if (Fluids.bucketFor(target) != null) {
                                needs.put(Fluids.bucketFor(target), 1);
                            }
                        }
                        if (item != null) {
                            needs.merge(item, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return needs;
    }

    private void publishWholeNeeds(Minecraft mc, fi.dy.masa.litematica.world.WorldSchematic schematic,
                                   java.util.Map<net.minecraft.world.item.Item, Integer> needs) {
        EntityPlacer.missing(mc, schematic).forEach((item, count) -> needs.merge(item, count, Integer::sum));
        // Farmland is dirt tilled with a hoe
        if (needs.containsKey(net.minecraft.world.item.Items.DIRT)
                && !StateRules.hasUsableHoe(mc.player.getInventory())) {
            needs.put(net.minecraft.world.item.Items.DIAMOND_HOE, mc.player.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND_HOE) + 1);
        }
        neededWhole.clear();
        neededWhole.putAll(needs);
        cleaner.remember(needs);
        missingWhole.clear();
        needs.forEach((item, count) -> {
            int have = mc.player.getInventory().countItem(item);
            if (count > have) {
                missingWhole.put(item, count - have);
            }
        });
    }

    public void markEmpty(net.minecraft.core.BlockPos pos) {
        if (running) {
            emptyDuringBuild.add(pos.immutable());
        }
    }

    /** A block state in short: "hopper[facing=down,enabled=true]" without "minecraft:". */
    public static String shortState(net.minecraft.world.level.block.state.BlockState state) {
        return state.toString().replace("Block{minecraft:", "").replace("}", "");
    }

    /** Every kind of block the schematic has (filled while the whole schematic is read, see needsAt). */
    public final java.util.Set<net.minecraft.world.level.block.Block> schematicBlocks = new java.util.HashSet<>();
    /** Spots the printer clicked during this build. */
    private final java.util.Set<net.minecraft.core.BlockPos> triedDuringBuild = new java.util.HashSet<>();
    /** Schematic conflicts found (logged once each). */
    public final java.util.Set<net.minecraft.core.BlockPos> conflicts = new java.util.HashSet<>();

    /**
     * A block that was already in the world, isn't one the schematic uses and that the build didn't touch (the
     * player's own building standing where the schematic wants something else): never broken, never counted as
     * unfinished, logged once as a schematic conflict. Wrong blocks of the schematic's own kinds (a printer mistake:
     * mud where dirt goes, a hopper facing the wrong way) and anything placed during the build are broken as before.
     */
    public boolean isConflict(net.minecraft.core.BlockPos pos, BlockCheck.Result result,
                              net.minecraft.world.level.block.state.BlockState current) {
        if (!running || result != BlockCheck.Result.WRONG_BLOCK) {
            return false;
        }
        if (schematicBlocks.isEmpty()) {
            // Not known yet which blocks are the schematic's: left alone until it is
            return !wholeKnown();
        }
        if (emptyDuringBuild.contains(pos) || triedDuringBuild.contains(pos) || schematicBlocks.contains(current.getBlock())) {
            return false;
        }
        if (grownFruit(pos, current)) {
            // A pumpkin or melon a stem grew onto a spot of the farm: not the player's building
            return false;
        }
        if (conflicts.add(pos.immutable())) {
            fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
            BuildLog.line("CONFLICT", pos.toShortString() + ": the world has " + current + ", the schematic wants "
                    + (schematic != null ? schematic.getBlockState(pos) : "?") + " (left alone, not counted)");
        }
        return true;
    }

    /** A pumpkin or melon with a stem of its kind next to it in the world (grown, not built). */
    private static boolean grownFruit(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState current) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.level.block.Block fruit = current.getBlock();
        if (mc.level == null || (fruit != net.minecraft.world.level.block.Blocks.PUMPKIN && fruit != net.minecraft.world.level.block.Blocks.MELON)) {
            return false;
        }
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            net.minecraft.world.level.block.Block next = mc.level.getBlockState(pos.relative(direction)).getBlock();
            if (next instanceof net.minecraft.world.level.block.StemBlock || next instanceof net.minecraft.world.level.block.AttachedStemBlock) {
                return true;
            }
        }
        return false;
    }

    /** True for a protected (container) block that wasn't there when the build began. */
    public boolean placedDuringBuild(net.minecraft.core.BlockPos pos) {
        return running && emptyDuringBuild.contains(pos);
    }

    /** True while a key-started "fix wrong" / "clear extra" is still working. */
    public boolean isManualTaskRunning() {
        return manualTask != ManualTask.NONE;
    }

    /** What this tick goes to, for the build stats. */
    private BuildStats.Activity activity() {
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning()) {
            return BuildStats.Activity.BUYING;
        }
        if (breaker.isBusy()) {
            return BuildStats.Activity.BREAKING;
        }
        if (fluids.isBusy()) {
            return BuildStats.Activity.BUCKET;
        }
        if (rails.isBusy()) {
            return BuildStats.Activity.RAILS;
        }
        return mover.activity();
    }

    /** A trip (storage, then the shop) goes on after each part, until everything is up to its share. */
    private boolean tripGoesOn;
    /** When the shop was last scanned again by itself (an item it needed wasn't in the scan). */
    private long lastAutoRescan = -100000000L;
    /** Storage was looked in on this trip already (once per trip: a vault with nothing that fits isn't opened again). */
    private boolean fetchedThisTrip;
    /** The last inventory plan written to the log (written again only when it changes, or after a while). */
    private String lastPlanLine = "";
    private long lastPlanLineAt = -100000;
    /** When the last excess was put away to make room (not again within a minute if that didn't help). */
    private long excessStoredAt = -100000;

    /**
     * Gets what the current layer needs, from storage first, then /shop. A trip starts when one of the layer's items
     * is (nearly) used up; then every item the layer needs is topped up to its share of the inventory (see
     * {@link InventoryBudget}), so the next trip is when they all run low together. When the layer can't go on
     * because of items the shop doesn't have (or couldn't deliver), auto build pauses and says what to bring.
     */
    private boolean buyMissing(Minecraft mc) {
        java.util.Map<net.minecraft.world.item.Item, Integer> layerNeeds = layers.layerNeeds();
        if (layerNeeds.isEmpty() || mc.player.getAbilities().instabuild) {
            tripGoesOn = false;
            return false;
        }
        if (buyCooldown > 0) {
            return false;
        }
        net.minecraft.world.entity.player.Inventory inventory = mc.player.getInventory();
        boolean storage = ChestFetcher.hasStorage(mc);
        boolean autoBuy = Configs.Shop.AUTO_BUY.getBooleanValue();
        int buyWhenLeft = Configs.Shop.BUY_WHEN_LEFT.getIntegerValue();
        boolean due = false;
        boolean outOfSomething = false;
        for (var entry : layerNeeds.entrySet()) {
            net.minecraft.world.item.Item item = entry.getKey();
            // Buckets aren't bought, and an empty one is a water bucket again a moment later (an infinite water
            // bucket refills itself, another is filled from water nearby): never a reason for a trip (each use
            // of the bucket used to start one, topping up 1 cherry leaf or a stack of mud on the way)
            if (InventoryCleaner.isBucket(item)) {
                continue;
            }
            int have = inventory.countItem(item);
            if (have >= entry.getValue()) {
                continue;
            }
            if (have == 0) {
                outOfSomething |= !gettable(mc, item) || !autoBuy;
            }
            // Only once it's (nearly) used up: this many or fewer left
            if (gettable(mc, item) && have <= buyWhenLeft
                    && (storage || autoBuy && no.sindrens.printeraddon.shop.ShopBuyer.room(mc, item) > 0)) {
                due = true;
            }
        }
        // The layer's water needs a bucket and there's none at all (not even an empty one): looked for in storage
        // (once a minute), and said (once in two minutes); the layer waited minutes for water without it once
        boolean noBucket = layerNeeds.containsKey(net.minecraft.world.item.Items.WATER_BUCKET)
                && inventory.countItem(net.minecraft.world.item.Items.WATER_BUCKET) + inventory.countItem(net.minecraft.world.item.Items.BUCKET) == 0;
        if (noBucket && storage && clientTicks - bucketLookedFor > 20 * 60) {
            bucketLookedFor = clientTicks;
            due = true;
        }
        if (noBucket && clientTicks - bucketWarned > 20 * 60 * 2) {
            bucketWarned = clientTicks;
            Chat.warn("No water bucket in your inventory: the water on this layer can't go in"
                    + (storage ? " (looking in your vaults and chests for one)." : ". Put one in your inventory."));
            BuildLog.line("PLAN", "no water bucket in the inventory, the layer's water waits for one" + (storage ? ": looking in storage" : ""));
        }
        boolean goesOn = tripGoesOn;
        tripGoesOn = false;
        if (!due && !goesOn) {
            pauseIfOut(mc, outOfSomething);
            return false;
        }
        if (!goesOn) {
            fetchedThisTrip = false;
        }

        InventoryBudget.Plan plan = InventoryBudget.plan(inventory, layerNeeds, neededWhole, cleaner.storable(inventory));
        // Not room for all of it: what there's too much of, or what this layer doesn't need, goes into storage first
        if (plan.shortSlots() > 0 && !plan.excess().isEmpty() && storage && clientTicks - excessStoredAt > 20 * 60) {
            excessStoredAt = clientTicks;
            if (fetcher.startStore(mc, plan.excess())) {
                BuildLog.line("PLAN", "inventory for " + layers.describeLayers() + ": " + plan.describe(inventory) + " | "
                        + plan.pool() + " building slots, " + plan.shortSlots() + " short: putting away first " + stacks(plan.excess()));
                tripGoesOn = true;
                mover.stop(mc);
                return true;
            }
        }
        // Storage is asked for all of it (a water bucket put there, say); the shop below only for what it delivers
        java.util.Map<net.minecraft.world.item.Item, Integer> wants = new java.util.LinkedHashMap<>();
        plan.wants().forEach((item, count) -> {
            if (gettable(mc, item) || (storage && !fetchedThisTrip)) {
                wants.put(item, count);
            }
        });
        java.util.Map<net.minecraft.world.item.Item, Integer> list = InventoryBudget.fitNow(inventory, wants);
        String planLine = "inventory for " + layers.describeLayers() + ": " + plan.describe(inventory) + " | "
                + plan.pool() + " building slots" + (plan.shortSlots() > 0 ? ", " + plan.shortSlots() + " short" : "")
                + " | getting " + (list.isEmpty() ? "nothing" : list) + (goesOn ? " (same trip)" : "");
        if (!planLine.equals(lastPlanLine) || clientTicks - lastPlanLineAt > 20 * 30) {
            lastPlanLine = planLine;
            lastPlanLineAt = clientTicks;
            BuildLog.line("PLAN", planLine);
        }
        if (list.isEmpty()) {
            // Nothing fits (or nothing can be got): looked at again in a moment, not every tick
            buyCooldown = 40;
            pauseIfOut(mc, outOfSomething);
            return false;
        }

        buyCooldown = 20 * 30;

        // First what's in storage (vaults, linked chests); the same trip then buys what wasn't there. Also what
        // they're made of: the player may have put pumpkins (for seeds) or redstone blocks (for dust) in storage
        java.util.Map<net.minecraft.world.item.Item, Integer> fromStorage = new java.util.LinkedHashMap<>(list);
        list.forEach((item, count) -> {
            net.minecraft.world.item.Item from = CraftPlan.madeFrom(item);
            if (from != null) {
                fromStorage.merge(from, CraftPlan.madeFromCount(item, count), Integer::sum);
            }
        });
        if (!fetchedThisTrip && fetcher.start(mc, fromStorage)) {
            fetchedThisTrip = true;
            mover.stop(mc);
            buyCooldown = 0;
            tripGoesOn = true;
            return true;
        }
        if (!autoBuy) {
            pauseIfOut(mc, outOfSomething);
            return false;
        }

        // In the shop only what's worth a purchase: what's (nearly) used up, a stack or more, or the rest of the
        // layer; by whole stacks where that's about the same (a purchase by the stack is one click)
        java.util.Map<net.minecraft.world.item.Item, Integer> toBuy = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map.Entry<net.minecraft.world.item.Item, Integer>> byUrgency = new java.util.ArrayList<>(list.entrySet());
        byUrgency.sort(java.util.Comparator.comparingInt(e -> inventory.countItem(e.getKey())));
        for (var entry : byUrgency) {
            net.minecraft.world.item.Item item = entry.getKey();
            int want = entry.getValue();
            int stack = item.getDefaultMaxStackSize();
            int have = inventory.countItem(item);
            if (!gettable(mc, item) || InventoryCleaner.isBucket(item)) {
                continue;
            }
            // Not running low: only a stack or more is worth a purchase (it bought 1 cherry leaf at 127 of 128,
            // a stack of mud at 700 of 704); the rest comes on the trip when it does run low
            if (have > buyWhenLeft && want < stack) {
                continue;
            }
            if (want >= stack / 2 && stack > 1) {
                // Never more than the whole build still needs
                int useful = Math.max(want, neededWhole.getOrDefault(item, 0) - have);
                want = Math.min(useful, Math.max(stack, Math.round(want / (float) stack) * stack));
            }
            toBuy.put(item, want);
        }

        // Rails, powered rails and seeds are crafted: buy what they're made of, then craft them
        java.util.Map<net.minecraft.world.item.Item, Integer> toCraft = new java.util.LinkedHashMap<>();
        toBuy.entrySet().removeIf(entry -> {
            if (CraftPlan.crafted(entry.getKey())) {
                toCraft.put(entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });
        if (!toCraft.isEmpty()) {
            CraftPlan plan2 = CraftPlan.of(mc, toCraft, item -> no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, item) && !cantGet(item),
                    Crafter.tableNearby(mc) || Crafter.craftCommand());
            plan2.buy.forEach((item, count) -> toBuy.merge(item, count, Integer::sum));
            craftAfterBuying = plan2.craft;
            craftingFor = toCraft.keySet();
            PrinterAddon.debug("To craft {}: buy {}, then craft {}", toCraft, plan2.buy, plan2.craft);
        }
        if (toBuy.isEmpty()) {
            for (CraftPlan.Step step : craftAfterBuying) {
                crafter.craft(step.item(), step.count());
            }
            craftAfterBuying = java.util.List.of();
            // Only crafting this round: looked at again soon after, not in 30 s
            buyCooldown = 40;
            return crafter.isBusy();
        }
        // Within the spending limit: what's left of it goes into this trip (the shop cuts the list to fit)
        double budget = spendLimit < 0 ? Double.MAX_VALUE : spendLimit - spentTotal;
        if (budget <= 1) {
            if (!warnedLimit) {
                warnedLimit = true;
                Chat.warn(String.format("Spending limit reached ($%,.0f of $%,.0f): nothing more is bought. Start again (J+B) for a new limit,"
                        + " or bring the items.", spentTotal, spendLimit));
                BuildLog.line("SHOP", "spending limit reached: " + spendingLine());
            }
            cantBuy(toBuy.keySet());
            craftAfterBuying = java.util.List.of();
            return false;
        }
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.start(mc, toBuy, budget)) {
            buying = true;
            mover.stop(mc);
            return true;
        }
        // Not in the shop scan at all (an old scan, or one that missed a category): the shop is scanned again, once
        // in a while, and the trip goes on with the new scan
        java.util.List<net.minecraft.world.item.Item> unscanned = no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.missingFromScan();
        if (!unscanned.isEmpty() && clientTicks - lastAutoRescan > 20L * 60 * 30) {
            lastAutoRescan = clientTicks;
            BuildLog.line("SHOP", unscanned + " not in the shop scan: scanning the shop again");
            Chat.info("Not in the shop scan: scanning /" + Configs.Shop.SHOP_COMMAND.getStringValue() + " again, then buying.");
            mover.stop(mc);
            buyCooldown = 0;
            tripGoesOn = true;
            no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.start(mc);
            return true;
        }
        cantBuy(no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.notSold());
        cantBuy(no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.notBought());
        // Nothing to buy after all (e.g. no shop scan): still craft what can be crafted from the inventory
        for (CraftPlan.Step step : craftAfterBuying) {
            crafter.craft(step.item(), step.count());
        }
        craftAfterBuying = java.util.List.of();
        return false;
    }

    /** Pause only when something is used up that can't be got, and the layer can't go on without it. */
    private void pauseIfOut(Minecraft mc, boolean outOfSomething) {
        if (outOfSomething && layers.stalledFor(20 * 20)) {
            StringBuilder sb = new StringBuilder();
            shoppingList.forEach((item, count) -> sb.append(count).append(' ')
                    .append(new net.minecraft.world.item.ItemStack(item).getHoverName().getString()).append(", "));
            stop(mc, "missing " + (sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "items")
                    + ". Put them in your inventory and start auto build again (J+B menu)");
        }
    }

    /** "observer: keep 12 stacks, hopper: all" for the build log. */
    private static String stacks(java.util.Map<net.minecraft.world.item.Item, Integer> keep) {
        StringBuilder sb = new StringBuilder();
        keep.forEach((item, stacks) -> sb.append(item.toString().replace("minecraft:", "")).append(": ")
                .append(stacks == 0 ? "all" : "keep " + stacks + " stack" + (stacks == 1 ? "" : "s")).append(", "));
        return sb.isEmpty() ? "-" : sb.substring(0, sb.length() - 2);
    }

    public void start(Minecraft mc) {
        if (running) {
            return;
        }
        if (!Configs.enabled()) {
            Chat.warn("The add-on is switched off (J+A to switch it on).");
            Configs.Generic.AUTO_BUILD.setBooleanValue(false);
            return;
        }
        if (SchematicHelper.world() == null || SchematicHelper.areas().isEmpty()) {
            Chat.warn("No schematic placement found. Load and place a schematic in Litematica first.");
            Configs.Generic.AUTO_BUILD.setBooleanValue(false);
            return;
        }

        running = true;
        Configs.Generic.AUTO_BUILD.setBooleanValue(true);
        BuildLog.open();
        Perf.report();
        logTicks = 0;
        placedAtSnapshot = 0;
        BuildLog.line("START", "Printer Addon " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(no.sindrens.printeraddon.PrinterAddon.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
                + " | server " + no.sindrens.printeraddon.util.ServerName.current(mc)
                + " | schematic areas " + SchematicHelper.areas()
                + " | player at " + mc.player.blockPosition().toShortString()
                + " | printer interval " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.getIntegerValue()
                + ", range " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue()
                + " | movement " + Configs.Movement.mode() + " | fps " + mc.getFps());
        BuildLog.line("SETTINGS", BuildLog.settings());
        BuildLog.line("SETTINGS", "printer: interval " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.getIntegerValue()
                + ", range " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue()
                + ", rotate " + me.aleksilassila.litematica.printer.config.Configs.ROTATE.getBooleanValue()
                + ", interact blocks " + me.aleksilassila.litematica.printer.config.Configs.INTERACT_BLOCKS.getBooleanValue()
                + ", print in air " + me.aleksilassila.litematica.printer.config.Configs.PRINT_IN_AIR.getBooleanValue()
                + " | player reach " + mc.player.blockInteractionRange() + " | ping " + ServerLag.pingTicks(mc) + " ticks"
                + " | linked chests " + ChestLinks.list(mc).size() + " | vaults " + ChestFetcher.vaults());
        BuildLog.line("INVENTORY", BuildLog.inventory(mc.player));
        int renderDistance = Configs.Generic.BUILD_RENDER_DISTANCE.getIntegerValue();
        if (renderDistance > 0 && mc.options.renderDistance().get() != renderDistance) {
            savedRenderDistance = mc.options.renderDistance().get();
            mc.options.renderDistance().set(renderDistance);
        }
        safety.reset();
        emptyDuringBuild.clear();
        triedDuringBuild.clear();
        conflicts.clear();
        schematicBlocks.clear();
        deferred.clear();
        couldNotPlace.clear();
        DeadEnds.reset();
        shoppingList.clear();
        missingWhole.clear();
        neededWhole.clear();
        wholeByY.clear();
        wholePartial.clear();
        couldntBuy.clear();
        warnedLimit = false;
        buying = false;
        buyCooldown = 0;
        cleaner.reset();
        HotbarPrep.resetMerging();
        crafter.reset();
        fetcher.reset();
        eater.reset(mc);
        craftAfterBuying = java.util.List.of();
        craftingFor = java.util.Set.of();
        stats.start();
        speed.reset();
        recentlyTried.clear();
        ServerAcks.reset();
        triesAt.clear();
        givenUp.clear();
        nextBlock = null;
        breaker.reset();
        mover.reset();
        mover.takeOff(mc.player);
        PrinterBridge.saveSettings();
        PrinterBridge.applySettings(mc.player);
        printerWasOn = PrinterBridge.isPrinting();
        PrinterBridge.setPrinting(true);
        // Air place only gets switched on for single floating blocks (see AirPlace)
        airPlaceWasOn = PrinterBridge.isAirPlace();
        PrinterBridge.setAirPlace(false);
        airPlace.reset();
        fluids.reset();
        rails.reset();
        entities.reset();
        breaker.setForced(airPlace.misplaced());

        // Which kinds of blocks the schematic has, from its palettes (to tell the player's own buildings from wrong
        // blocks before anything is broken); what it needs is read in the background from the first tick on
        schematicBlocks.addAll(SchematicHelper.blockKinds());
        wholeFirstPass = true;
        wholeNextY = Integer.MIN_VALUE;
        if (Configs.Layers.AUTO_LAYER.getBooleanValue() && !layers.start(mc)) {
            stop(mc, null);
            return;
        }

        Chat.info("Auto build §astarted§r.");
    }

    /** Stops auto build. {@code reason} is shown as a warning when it isn't the player's own choice. */
    public void stop(Minecraft mc, String reason) {
        boolean wasRunning = running;
        if (wasRunning) {
            speed.remember();
        }
        running = false;
        no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.cancel(null);
        buying = false;
        Configs.Generic.AUTO_BUILD.setBooleanValue(false);
        layers.stop();
        breaker.stop(mc);
        mover.stop(mc);
        airPlace.stop();
        eater.reset(mc);
        if (savedRenderDistance > 0) {
            mc.options.renderDistance().set(savedRenderDistance);
            savedRenderDistance = 0;
        }
        crafter.reset();
        fetcher.reset();

        if (wasRunning && !resumeLater) {
            Resume.forget();
        }
        if (wasRunning) {
            String why = reason != null ? "stopped: " + reason
                    : resumeLater ? "disconnected" + (disconnectReason.isEmpty() ? "" : ": " + disconnectReason) + " (picks up again after rejoining)"
                    : "stopped by you";
            BuildLog.line("END", why + " | " + stats.total() + " | " + spendingLine());
            disconnectReason = "";
            logIgnored(mc);
            BuildLog.line("PERF", Perf.report());
            ReachStats.save();
            PrinterBridge.setPrinting(printerWasOn);
            PrinterBridge.setAirPlace(airPlaceWasOn);
            PrinterBridge.restoreSettings();
            if (reason != null) {
                Chat.warn("Auto build stopped: " + reason + ".");
                Chat.announce("Auto build stopped", reason, false);
            } else {
                Chat.info("Auto build §cstopped§r.");
            }
        }
        BuildLog.close();
    }

    /** At the end of the build: every block that was left out, and why. */
    private void logIgnored(Minecraft mc) {
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null || mc.level == null || mc.player == null) {
            return;
        }
        java.util.Set<net.minecraft.core.BlockPos> all = new java.util.LinkedHashSet<>();
        all.addAll(givenUp.keySet());
        all.addAll(couldNotPlace);
        all.addAll(mover.skippedNow());
        // Only what's still not right
        all.removeIf(pos -> BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.OK);
        BuildLog.line("IGNORED", all.size() + " blocks left for later or not placeable, " + conflicts.size()
                + " schematic conflicts (the world has another block that isn't the build's)");
        int n = 0;
        for (net.minecraft.core.BlockPos pos : all) {
            if (n++ >= 2000) {
                BuildLog.line("IGNORED", "... and " + (all.size() - 2000) + " more");
                break;
            }
            BuildLog.line("IGNORED", Diagnose.explain(mc, schematic, pos));
        }
        n = 0;
        for (net.minecraft.core.BlockPos pos : conflicts) {
            if (n++ >= 2000) {
                break;
            }
            BuildLog.line("CONFLICT", pos.toShortString() + ": is " + shortState(mc.level.getBlockState(pos)) + ", schematic wants "
                    + shortState(schematic.getBlockState(pos)));
        }
    }

    public void stopEverything(Minecraft mc) {
        manualTask = ManualTask.NONE;
        stop(mc, null);
    }

    /** Disconnected: stop without forgetting what was being built (see Resume). */
    /** Why the connection last ended (shown in the END line). */
    private String disconnectReason = "";

    /** The connection ended (see ClientCommonPacketListenerMixin): the reason goes into the build log at once. */
    public void disconnected(String reason) {
        disconnectReason = reason;
        if (running) {
            BuildLog.line("DISCONNECT", "the connection ended: " + reason);
        }
        // Kicked or the server restarting while building, or on the way back to it: join again by itself
        if (running || Resume.pending()) {
            Reconnect.connectionEnded(Minecraft.getInstance(), reason);
        }
    }

    /** Errors already written, so one that repeats every tick isn't written every tick. */
    private final java.util.Map<String, Long> errorsLogged = new java.util.HashMap<>();

    /**
     * Something in the add-on went wrong: written to the build log and the game log with where it happened, auto
     * build stops, and the game goes on (it doesn't crash for the add-on).
     */
    public void error(String where, Throwable e) {
        String key = where + "|" + e;
        Long at = errorsLogged.get(key);
        boolean first = at == null || clientTicks - at > 20 * 60;
        if (first) {
            errorsLogged.put(key, clientTicks);
            PrinterAddon.LOGGER.error("Printer Addon error in {}", where, e);
            java.io.StringWriter trace = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(trace));
            BuildLog.line("ERROR", "in " + where + ": " + trace.toString().replace(System.lineSeparator(), " | "));
        }
        if (running) {
            Minecraft mc = Minecraft.getInstance();
            try {
                stop(mc, "an error in the add-on (" + e.getClass().getSimpleName() + " in " + where + "), it's in the build log");
            } catch (RuntimeException again) {
                running = false;
                PrinterAddon.LOGGER.error("Printer Addon: stopping after an error failed too", again);
            }
        } else if (first) {
            Chat.warn("Printer Addon error (" + e.getClass().getSimpleName() + " in " + where + "): the game log has the details.");
        }
    }

    public void stopForDisconnect(Minecraft mc) {
        if (!running && manualTask == ManualTask.NONE) {
            return;
        }
        resumeLater = true;
        stopEverything(mc);
        resumeLater = false;
    }

    private boolean resumeLater;

    /** The most auto build may spend in the shop on this build (-1: no limit), and what it has spent on it. */
    private double spendLimit = -1;
    private double spentTotal;
    private boolean warnedLimit;

    /** Starting by hand: with buying on, the spending limit is asked first (see SpendingLimitGui). */
    public void askAndStart(Minecraft mc) {
        if (running) {
            return;
        }
        if (!Configs.Shop.AUTO_BUY.getBooleanValue() || mc.player == null || mc.player.getAbilities().instabuild) {
            spendLimit = -1;
            start(mc);
            return;
        }
        fi.dy.masa.malilib.gui.GuiBase.openGui(new no.sindrens.printeraddon.gui.SpendingLimitGui());
    }

    public void startWithLimit(Minecraft mc, double limit) {
        spendLimit = limit;
        spentTotal = 0;
        start(mc);
        if (running) {
            Chat.info(limit < 0 ? "Spending limit: none." : String.format("Spending limit for this build: $%,.0f.", limit));
        }
    }

    /** For the resume file: "limit spent". */
    public String spendingText() {
        return spendLimit + " " + spentTotal;
    }

    /** After rejoining: the same limit, with what was spent already. */
    public void restoreSpending(String text) {
        try {
            String[] parts = text.trim().split(" ");
            spendLimit = Double.parseDouble(parts[0]);
            spentTotal = parts.length > 1 ? Double.parseDouble(parts[1]) : 0;
        } catch (RuntimeException ignored) {
        }
    }

    /** "Spent $1,234 of $50,000" for the on-screen display. */
    public String spendingLine() {
        return spendLimit < 0 ? String.format("Spent $%,.0f (no limit)", spentTotal)
                : String.format("Spent $%,.0f of $%,.0f", spentTotal, spendLimit);
    }

    /**
     * The whole add-on on or off (J+A, or the button in the J+B menu). Off: everything it does stops and every hook
     * into Litematica Printer passes straight through, so the printer works exactly as without the add-on.
     */
    public void setAddonEnabled(Minecraft mc, boolean on) {
        Configs.Generic.ADDON_ENABLED.setBooleanValue(on);
        if (!on) {
            stopEverything(mc);
            no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.cancel(null);
            no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.cancel(null);
        }
        Chat.info("Printer Addon " + (on ? "\u00a7aon" : "\u00a7coff\u00a7r: Litematica Printer works on its own (J+A or J+B to switch it on again)") + ".");
    }

    public void toggle(Minecraft mc) {
        if (running) {
            stop(mc, null);
        } else {
            askAndStart(mc);
        }
    }

    public void fixWrongNow() {
        manualTask = ManualTask.FIX_WRONG;
        breaker.reset();
        breaker.resetBrokenCount();
    }

    public void clearExtraNow() {
        manualTask = ManualTask.CLEAR_EXTRA;
        breaker.reset();
        breaker.resetBrokenCount();
    }

    /**
     * A container menu (hopper, chest...) that opened during auto build blocks the printer: the printer clicked
     * a container instead of placing against it. Close it.
     */
    private static void closeUnwantedMenu(Minecraft mc) {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> && !(mc.gui.screen() instanceof InventoryScreen)) {
            String title = mc.gui.screen().getTitle().getString();
            PrinterAddon.debug("Closing {} that opened during auto build", title);
            mc.player.closeContainer();
            // Again and again: the server doesn't see the printer sneaking when it clicks, so blocks that go against
            // a container (hoppers into hoppers) never get placed. Say so instead of building nothing silently
            long now = clientTicks;
            unwantedMenus.addLast(now);
            while (!unwantedMenus.isEmpty() && now - unwantedMenus.getFirst() > 20 * 60) {
                unwantedMenus.removeFirst();
            }
            if (unwantedMenus.size() >= 5 && now - lastUnwantedMenuWarning > 20 * 120) {
                lastUnwantedMenuWarning = now;
                Chat.warn(unwantedMenus.size() + " times in a minute a menu (" + title + ") opened instead of a block being placed"
                        + " against it: the server doesn't see the printer sneak (details in logs/latest.log with Debug log on).");
            }
        }
    }

    private static final java.util.ArrayDeque<Long> unwantedMenus = new java.util.ArrayDeque<>();
    private static long lastUnwantedMenuWarning = -100000;

    /** The world auto build runs in: another one means the server moved you (to a hub, say). */
    @javax.annotation.Nullable
    private net.minecraft.client.multiplayer.ClientLevel builtIn;

    public void tick(Minecraft mc) {
        if (running && mc.level != builtIn) {
            if (builtIn != null) {
                // Moved to another world (another server of the network): stop, then go back (see Resume)
                BuildLog.line("WORLD", "the world changed (moved to another server?): stopping, going back to the build after");
                stopForDisconnect(mc);
                return;
            }
            builtIn = mc.level;
        } else if (!running) {
            builtIn = null;
        }
        long started = Perf.start();
        tickParts(mc);
        if (running) {
            Perf.end("add-on tick (all)", started);
            Perf.tick();
            if (++logTicks % 100 == 0) {
                snapshot(mc);
            }
            if (logTicks % 1200 == 0 && mc.player != null) {
                BuildLog.line("INVENTORY", BuildLog.inventory(mc.player));
            }
        }
    }

    private long logTicks;
    private int placedAtSnapshot;

    /** Every 5 seconds in the build log: where the player is and how it goes. */
    private void snapshot(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        int placed = stats.totalPlaced();
        BuildLog.line("SNAP", String.format("at %.1f %.1f %.1f | %s | placed %d in 5 s (%d total) | %s | mover %s | printer busy %d%% (%s) | interval %d | free slots %d | fps %d",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), layers.status(), placed - placedAtSnapshot, placed,
                doing(), mover.details(), stats.printerBusyPercent(), stats.printerIdleReason(), speed.interval(),
                InventoryCleaner.freeSlots(mc.player.getInventory()), mc.getFps()));
        placedAtSnapshot = placed;
    }

    private void tickParts(Minecraft mc) {
        // The settings screen (or a hotkey) can flip the switch too
        if (Configs.Generic.AUTO_BUILD.getBooleanValue() != running) {
            if (running) {
                stop(mc, null);
            } else {
                // Switched on in the settings: the spending limit is asked first, like with the Start button
                Configs.Generic.AUTO_BUILD.setBooleanValue(false);
                askAndStart(mc);
            }
        }

        if (running && !Configs.enabled()) {
            stop(mc, "the add-on was switched off");
            return;
        }
        if (running) {
            inventory.update(mc);
        }
        if (running) {
            String reason = safety.check(mc);
            if (reason != null) {
                stop(mc, reason);
                return;
            }
        }

        if (manualTask != ManualTask.NONE) {
            BlockBreaker.Mode mode = manualTask == ManualTask.FIX_WRONG ? BlockBreaker.Mode.WRONG : BlockBreaker.Mode.EXTRA;
            if (!breaker.tick(mc, mode)) {
                int count = breaker.getBrokenCount();
                Chat.info(count == 0 ? "Nothing to break within reach." : "Broke " + count + " block" + (count == 1 ? "" : "s") + ".");
                manualTask = ManualTask.NONE;
            }
            return;
        }

        if (!running) {
            return;
        }

        PrinterBridge.applySettings(mc.player);
        stats.tick(activity());
        // Nothing placed for a moment: the head turns back to where the camera looks, smoothly
        if (PrinterBridge.isIdle() && clientTicks - lastQueuedTick > 8 && !breaker.isBusy() && !fluids.isBusy()
                && !rails.isBusy() && !entities.isBusy() && !airPlace.isActive()) {
            PrinterBridge.easeLookBack(mc.player);
        }
        // The pause between shopping rounds runs every tick (it used to stand still while nothing was missing, and
        // a new layer then waited out the rest: 28 s in a log); a new layer may shop at once
        if (buyCooldown > 0) {
            buyCooldown--;
        }
        if (layers.getLayer() != shoppingLayer) {
            shoppingLayer = layers.getLayer();
            buyCooldown = 0;
        }

        speed.tick(mc);

        // Eating: nothing else happens meanwhile (only between other jobs, never in the middle of one)
        if (eater.isBusy() || (!breaker.isBusy() && !crafter.isBusy() && !fetcher.isBusy() && !airPlace.isActive()
                && !fluids.isBusy() && !rails.isBusy() && PrinterBridge.isIdle()
                && !no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning())) {
            if (eater.tick(mc)) {
                mover.stop(mc);
                return;
            }
        }

        // Buying from the shop: nothing else happens meanwhile
        no.sindrens.printeraddon.shop.ShopBuyer buyer = no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE;
        if (buyer.isRunning()) {
            buyer.tick(mc);
            return;
        }
        if (buying) {
            buying = false;
            spentTotal += buyer.spent();
            cantBuy(buyer.notSold());
            cantBuy(buyer.notBought());
            // Bought, and none of it failed: buyable again whenever needed (a purchase that stopped halfway, out
            // of money, isn't tried again right away)
            for (net.minecraft.world.item.Item item : buyer.bought().keySet()) {
                if (!buyer.notBought().contains(item)) {
                    couldntBuy.remove(item);
                }
            }
            if (!buyer.notBought().isEmpty()) {
                // Bought part of the list: the rest (other items, or the same from what it's made of) right away
                tripGoesOn = true;
            }
            buyCooldown = 40;
            // Then craft what the purchase was for
            for (CraftPlan.Step step : craftAfterBuying) {
                crafter.craft(step.item(), step.count());
            }
            craftAfterBuying = java.util.List.of();
        }

        // Taking items from chests: nothing else happens meanwhile
        if (fetcher.isBusy()) {
            if (fetcher.tick(mc, mover)) {
                return;
            }
            buyCooldown = 0;
        }

        // Crafting: nothing else happens meanwhile
        if (crafter.isBusy()) {
            if (crafter.tick(mc)) {
                return;
            }
        } else if (!craftingFor.isEmpty()) {
            for (net.minecraft.world.item.Item item : craftingFor) {
                if (shoppingList.containsKey(item) && mc.player.getInventory().countItem(item) == 0) {
                    // Crafting it didn't work: not again from the same ingredients for a while either
                    cantBuy(java.util.List.of(item));
                    if (CraftPlan.madeFrom(item) != null) {
                        cantBuy(java.util.List.of(CraftPlan.madeFrom(item)));
                    }
                }
            }
            craftingFor = java.util.Set.of();
        }

        closeUnwantedMenu(mc);

        if (++debugTicks % 40 == 0) {
            if (Configs.Generic.DEBUG_LOG.getBooleanValue()) {
                PrinterAddon.LOGGER.info("[debug] Tick: printer idle {}, breaker busy {}, layer {}", PrinterBridge.isIdle(),
                        breaker.isBusy(), layers.getLayer());
            }
        }

        boolean breakSomething = Configs.Breaking.BREAK_WRONG_BLOCKS.getBooleanValue() || !airPlace.misplaced().isEmpty();
        if (breakSomething && PrinterBridge.isIdle() && !airPlace.isActive()) {
            breaker.tick(mc, BlockBreaker.Mode.WRONG);
        } else if (breaker.isBusy()) {
            breaker.tick(mc, BlockBreaker.Mode.WRONG);
        }

        airPlace.tick(mc, breaker.isBusy());

        // Buckets (water in waterlogged blocks, so farmland is wet before it's tilled) always: the printer has no
        // buckets. The add-on's own placing of rails and minecarts only when switched on
        boolean addonPlaces = Configs.Layers.ADDON_PLACES.getBooleanValue();
        if (!breaker.isBusy() && !airPlace.isActive() && !rails.isBusy() && (fluids.isBusy() || PrinterBridge.isIdle())) {
            fluids.tick(mc);
        }
        if (addonPlaces && !breaker.isBusy() && !airPlace.isActive() && !fluids.isBusy() && (rails.isBusy() || PrinterBridge.isIdle())) {
            rails.tick(mc);
        }
        if (addonPlaces && !breaker.isBusy() && !airPlace.isActive() && !fluids.isBusy() && !rails.isBusy()
                && (entities.isBusy() || PrinterBridge.isIdle())) {
            entities.tick(mc);
        }
        if (!breaker.isBusy() && !airPlace.isActive() && !fluids.isBusy() && !rails.isBusy() && !entities.isBusy()
                && PrinterBridge.isIdle()) {
            hotbar.tick(mc, layers.layerNeeds());
        }

        long layersStarted = Perf.start();
        boolean layersGoOn = !layers.isActive() || layers.tick(mc);
        Perf.end("layer check", layersStarted);
        if (!layersGoOn) {
            stop(mc, null);
            return;
        }

        if (PrinterBridge.isIdle() && !breaker.isBusy() && !fluids.isBusy() && !rails.isBusy() && !entities.isBusy()) {
            long itemsStarted = Perf.start();
            boolean busy = cleaner.tick(mc, neededWhole, fetcher) || buyMissing(mc);
            Perf.end("inventory and buying", itemsStarted);
            if (busy) {
                return;
            }
        }

        long moverStarted = Perf.start();
        mover.tick(mc);
        Perf.end("movement", moverStarted);
        long statusStarted = Perf.start();
        updateStatus(mc);
        Perf.end("status and needs", statusStarted);
    }
}
