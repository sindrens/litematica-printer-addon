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
    /** Tills what the printer left as dirt where farmland goes (see Tiller). */
    public final Tiller tiller = new Tiller();
    /** Temporary rails are removed through the same list as misplaced air-placed blocks. */
    public final RailHelper rails = new RailHelper(airPlace.misplaced());
    public final EntityPlacer entities = new EntityPlacer();
    public final Crafter crafter = new Crafter();
    public final ChestFetcher fetcher = new ChestFetcher();
    /** Bulk buying into the vaults (see StockUp). */
    public final StockUp stock = new StockUp();
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
    final InventoryCleaner cleaner = new InventoryCleaner();
    public final BuildStats stats = new BuildStats();
    public final SpeedTuner speed = new SpeedTuner();
    /** The client tick the server was last told of another hotbar slot (see MultiPlayerGameModeMixin). */
    public static long carriedSentTick = Long.MIN_VALUE / 2;
    /** Client ticks since the game started (game time can stand still, e.g. with /tick freeze). */
    public static long clientTicks;
    /** Spots the printer just placed (game tick of the attempt): not given to it again until the server has answered. */
    private final java.util.Map<net.minecraft.core.BlockPos, Long> recentlyTried = new java.util.HashMap<>();

    /** When the printer last queued a block (for turning the head back once it pauses). */
    public long lastQueuedTick;

    /** The item the printer picked for its last block (for an empty hand at the click, see InteractActionMixin). */
    @javax.annotation.Nullable
    public net.minecraft.world.item.Item lastTriedItem;

    /**
     * The printer's click on this spot was against a block about to go (see InteractActionMixin): it didn't go out, and
     * isn't counted as a click that placed nothing.
     */
    public void clickSkipped(Minecraft mc, net.minecraft.core.BlockPos target, net.minecraft.core.BlockPos against) {
        speed.clickSkipped(target);
        // Not a try: no click went out (the tries count towards giving the block up)
        Tries tries = triesAt.get(target);
        if (tries != null) {
            if (tries.count() <= 1) {
                triesAt.remove(target);
            } else {
                triesAt.put(target.immutable(), new Tries(tries.state(), tries.count() - 1, tries.since()));
            }
        }
        if (mc.level != null) {
            BuildLog.line("CLICK-SKIP", target.toShortString().replace(" ", "") + " against=" + LayerManager.blockName(mc.level.getBlockState(against))
                    + "@" + against.toShortString().replace(" ", "") + ": it's about to go, the block would land in its spot");
        }
    }

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
                BuildLog.line("TRY", String.format(java.util.Locale.ROOT, "%s: is %s, wants %s | with %s, in hand %s | %.2f from the eye | player at %.1f %.1f %.1f",
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
            } else if (tries.count() + 1 >= MAX_TRIES && clientTicks - tries.since() >= GIVE_UP_AFTER) {
                triesAt.remove(pos);
                givenUp.put(pos.immutable(), clientTicks + GIVE_UP_TICKS);
                PrinterAddon.debug("The printer tried {} {} times and nothing changed (is {}): leaving it for 30 s",
                        pos.toShortString(), MAX_TRIES, now.getBlock().getName().getString());
                fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
                if (schematic != null) {
                    BuildLog.line("GIVEUP", pos.toShortString() + " for 30 s, clicked " + MAX_TRIES + " times without a change | "
                            + Diagnose.explain(mc, schematic, pos));
                }
                RunLog.gaveUp("clicked-" + MAX_TRIES + "-times-without-change");
                // Built over already: the block above comes off for it (and goes back after)
                unsealIfCovered(mc, pos, "clicked " + MAX_TRIES + " times without a change");
            } else {
                triesAt.put(pos.immutable(), new Tries(now, tries.count() + 1, tries.since()));
            }
        }
    }

    private record Tries(net.minecraft.world.level.block.state.BlockState state, int count, long since) {
    }

    /**
     * Given up on only after this long since the first of its tries: all 41 give-ups of a log were placed after all,
     * 35 of them within 20 ticks (4 clicks in 19 ticks on a slow server, all answered at once 5 ticks later).
     */
    private static final int GIVE_UP_AFTER = 40;

    /**
     * Whole stacks take a slot each (the rest of a part-used stack only fills that one up): rounded up, the orders may
     * not all fit together. Then the biggest orders give up their last stack (rounded down) until they do, so every
     * item still gets its stacks: the cherry leaves didn't fit after the mud and the dirt, and got a trip of their
     * own 25 s later.
     */
    private void fitWholeStacks(Minecraft mc, net.minecraft.world.entity.player.Inventory inventory,
                                java.util.Map<net.minecraft.world.item.Item, Integer> toBuy) {
        int over = -(InventoryCleaner.freeSlots(inventory) - InventoryBudget.KEEP_FREE);
        for (var entry : toBuy.entrySet()) {
            over += newSlots(inventory, entry.getKey(), entry.getValue());
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> trimmed = new java.util.LinkedHashMap<>();
        while (over > 0) {
            net.minecraft.world.item.Item best = null;
            for (var entry : toBuy.entrySet()) {
                net.minecraft.world.item.Item item = entry.getKey();
                int stack = item.getDefaultMaxStackSize();
                if (stack <= 1 || entry.getValue() < 2 * stack || !no.sindrens.printeraddon.shop.ShopBuyer.buysWholeStacks(mc, item)) {
                    continue;
                }
                if (best == null || entry.getValue() > toBuy.get(best)) {
                    best = item;
                }
            }
            if (best == null) {
                break;
            }
            int stack = best.getDefaultMaxStackSize();
            int before = newSlots(inventory, best, toBuy.get(best));
            toBuy.put(best, toBuy.get(best) - stack);
            trimmed.merge(best, stack, Integer::sum);
            over -= before - newSlots(inventory, best, toBuy.get(best));
        }
        if (!trimmed.isEmpty()) {
            BuildLog.line("PLAN", "whole stacks to fit the free slots: " + trimmed + " less, buying " + toBuy);
        }
    }

    /** The empty slots buying this many of the item takes (a part-used stack of it is filled first). */
    private static int newSlots(net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.item.Item item, int count) {
        int stack = item.getDefaultMaxStackSize();
        if (stack <= 1) {
            return count;
        }
        int have = inventory.countItem(item);
        int partRoom = have % stack == 0 ? 0 : stack - have % stack;
        return (Math.max(0, count - partRoom) + stack - 1) / stack;
    }

    /**
     * The last trip for this layer (what's left of it fits in with this trip): the room left over goes to what the next
     * layer starts with, in whole stacks, so the next layer needs no trip at its start (when in the shop anyway).
     */
    private void addNextLayer(Minecraft mc, net.minecraft.world.entity.player.Inventory inventory,
                              java.util.Map<net.minecraft.world.item.Item, Integer> layerNeeds,
                              java.util.Map<net.minecraft.world.item.Item, Integer> list) {
        for (var entry : layerNeeds.entrySet()) {
            net.minecraft.world.item.Item item = entry.getKey();
            if (!Tools.isMarker(item) && !InventoryCleaner.isBucket(item)
                    && inventory.countItem(item) + list.getOrDefault(item, 0) < entry.getValue()) {
                return;
            }
        }
        int slots = InventoryCleaner.freeSlots(inventory) - InventoryBudget.KEEP_FREE;
        for (var entry : list.entrySet()) {
            slots -= (entry.getValue() + entry.getKey().getDefaultMaxStackSize() - 1) / entry.getKey().getDefaultMaxStackSize();
        }
        if (slots <= 0) {
            return;
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> next = layers.nextLayerNeeds(mc);
        java.util.Map<net.minecraft.world.item.Item, Integer> added = new java.util.LinkedHashMap<>();
        for (var entry : next.entrySet()) {
            net.minecraft.world.item.Item item = entry.getKey();
            int stack = item.getDefaultMaxStackSize();
            int want = entry.getValue() - inventory.countItem(item) - list.getOrDefault(item, 0);
            if (slots <= 0 || want <= 0 || stack <= 1 || Tools.isMarker(item) || !gettable(mc, item)) {
                continue;
            }
            // A share of the room for each of the next layer's items
            int stacks = Math.min(slots, Math.max(1, Math.min((want + stack - 1) / stack, slots / Math.max(1, next.size()))));
            list.merge(item, stacks * stack, Integer::sum);
            added.put(item, stacks * stack);
            slots -= stacks;
        }
        if (!added.isEmpty()) {
            BuildLog.line("PLAN", "last trip for " + layers.describeLayers() + ": also " + added + " for the next layer");
        }
    }

    /**
     * The items the lane about to be flown takes more of than there are (see Mover): topped up before it, not with a
     * stop halfway along it. Looked at once, at the lane's start (until then; later the lane is under way).
     */
    private java.util.Set<net.minecraft.world.item.Item> laneShort = java.util.Set.of();
    private long laneShortUntil;

    public void beforeLane(java.util.Map<net.minecraft.world.item.Item, Integer> needs) {
        laneShort = java.util.Set.of();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        java.util.Set<net.minecraft.world.item.Item> shortNow = new java.util.HashSet<>();
        for (var entry : needs.entrySet()) {
            if (mc.player.getInventory().countItem(entry.getKey()) < entry.getValue() && gettable(mc, entry.getKey())) {
                shortNow.add(entry.getKey());
            }
        }
        if (!shortNow.isEmpty()) {
            laneShort = shortNow;
            laneShortUntil = clientTicks + 60;
            // Looked at right away (see buyMissing), not after the pause between shopping rounds
            buyCooldown = 0;
        }
    }

    /** Ticks of building (not buying, fetching or crafting) since a block was last placed, and the strikes so far. */
    private int watchTicks;
    private int watchPlaced = -1;
    private int watchStrikes;
    private static final int WATCH_TICKS = 20 * 30;

    /**
     * Nothing placed for 30 s of building while there's work: the mover is told to leave what it's at and plan
     * again (a lane end flown to every tick for minutes held up two builds until they stopped as "missing").
     */
    private void watchdog(Minecraft mc) {
        int placed = stats.totalPlaced();
        if (placed != watchPlaced || !layers.isActive() || !mover.hasWork() || breaker.isBusy() || fluids.isBusy()
                || notStuckBecause(mc) != null) {
            if (placed != watchPlaced) {
                watchStrikes = 0;
            }
            watchPlaced = placed;
            watchTicks = 0;
            return;
        }
        if (++watchTicks >= WATCH_TICKS) {
            watchTicks = 0;
            mover.watchdog(mc, ++watchStrikes);
        }
    }

    /**
     * The block above a block the layer above was already built over (a pumpkin stem under the observers and
     * pistons): it's broken so the block below can go in, then put back (held back from the printer until the one
     * below is in, at most a minute). Block above -> the block below it, and since when.
     */
    private final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> unsealFor = new java.util.HashMap<>();
    private final java.util.Map<net.minecraft.core.BlockPos, Long> unsealSince = new java.util.HashMap<>();
    private static final int UNSEAL_HOLD_TICKS = 20 * 60;
    private final java.util.Map<net.minecraft.core.BlockPos, Integer> unsealCount = new java.util.HashMap<>();

    /** Opens up a block covered by the build's own block above it, if it is one (see unsealFor). */
    public void unsealIfCovered(Minecraft mc, net.minecraft.core.BlockPos pos, String why) {
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null || mc.level == null || !running) {
            return;
        }
        net.minecraft.core.BlockPos above = pos.above();
        net.minecraft.world.level.block.state.BlockState aboveState = mc.level.getBlockState(above);
        if (unsealFor.containsKey(above) || BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.MISSING
                || BlockCheck.check(mc.level, schematic, above) != BlockCheck.Result.OK || aboveState.isAir()
                || aboveState.canBeReplaced() || BlockCheck.isProtected(mc.level, above, aboveState)) {
            return;
        }
        // Twice at most for a spot: if the block below still doesn't go in, it's left for the end of the build
        if (unsealCount.merge(above.immutable(), 1, Integer::sum) > 2) {
            return;
        }
        unsealFor.put(above.immutable(), pos.immutable());
        unsealSince.put(above.immutable(), clientTicks);
        airPlace.misplaced().add(above.immutable());
        deferred.add(above.immutable());
        BuildLog.line("UNSEAL", pos.toShortString() + " (" + why + ") has " + shortState(aboveState) + " of the build over it:"
                + " breaking that, placing " + shortState(schematic.getBlockState(pos)) + ", then putting it back");
    }

    /** Held back from the printer: the block below it is to go in first (see unsealIfCovered). */
    private boolean heldForBelow(Minecraft mc, fi.dy.masa.litematica.world.WorldSchematic schematic, net.minecraft.core.BlockPos pos) {
        net.minecraft.core.BlockPos below = unsealFor.get(pos);
        if (below == null) {
            return false;
        }
        if (BlockCheck.check(mc.level, schematic, below) != BlockCheck.Result.MISSING
                || clientTicks - unsealSince.getOrDefault(pos, 0L) > UNSEAL_HOLD_TICKS) {
            unsealFor.remove(pos);
            unsealSince.remove(pos);
            return false;
        }
        return true;
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
        items.forEach(item -> buyFailures.put(item, MAX_BUY_FAILURES));
    }

    /**
     * The shop didn't deliver these (a menu that didn't open, the wrong page, nothing arrived): tried again in a while,
     * sooner the first times. During a lag spike the shop failed for all three items of a trip, they weren't tried
     * for 5 minutes, and the build stopped for the sand meanwhile.
     */
    private void didntDeliver(java.util.Collection<net.minecraft.world.item.Item> items) {
        for (net.minecraft.world.item.Item item : items) {
            int failures = buyFailures.merge(item, 1, Integer::sum);
            long wait = RETRY_AFTER[Math.min(failures, RETRY_AFTER.length) - 1];
            couldntBuy.put(item, clientTicks + wait);
            BuildLog.line("SHOP", new net.minecraft.world.item.ItemStack(item).getHoverName().getString() + " not delivered (" + failures
                    + (failures == 1 ? " time" : " times") + "): tried again in " + wait / 20 + " s" + (failures >= MAX_BUY_FAILURES
                    ? "; the build waits for it no more once it's used up" : ""));
        }
    }

    /** How often each item wasn't delivered in a row (see didntDeliver). */
    private final java.util.Map<net.minecraft.world.item.Item, Integer> buyFailures = new java.util.HashMap<>();
    private static final long[] RETRY_AFTER = {20 * 30, 20 * 60, 20 * 120, RETRY_BUYING};
    private static final int MAX_BUY_FAILURES = 3;

    /** The shop failed for this item lately, but it's tried again soon: not reason enough to stop for it. */
    private boolean retryPending(net.minecraft.world.item.Item item) {
        return couldntBuy.containsKey(item) && buyFailures.getOrDefault(item, 0) < MAX_BUY_FAILURES;
    }

    private long lagNotedAt = -100000;
    /** How many conflicts the build log's last list of them had (see logConflicts). */
    private int conflictsLogged;

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
    /** Since when the inventory has only empty buckets (-1: not so). */
    private long emptyBucketsSince = -1;
    /** When the pause between shopping rounds was last cut short because the layer was stuck on a used up item. */
    private long stallShopAt = -100000;
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
            pausedAt = clientTicks;
        }
        return why != null;
    }

    /** When the printer was last paused (see pausedThisTick). */
    private long pausedAt = -100;

    /** Why the printer was paused this tick (or the last one), or null if it wasn't. */
    @javax.annotation.Nullable
    public String pausedThisTick() {
        return clientTicks - pausedAt <= 1 ? pauseReason : null;
    }

    /** A trip for items goes on after its current part (see buyMissing). */
    /**
     * Why nothing being placed isn't being stuck right now: the server lagging or slow to answer, or a trip for items
     * (null: none of those). A lag of 19 s raised "nothing placed for 30 s" with 13 "missing" blocks listed.
     */
    @javax.annotation.Nullable
    public String notStuckBecause(Minecraft mc) {
        if (ServerLag.hardLag(mc) || ServerLag.shouldWait(mc)) {
            return "server-lag";
        }
        if (ServerWatch.stalled()) {
            return "server-slow";
        }
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning() || fetcher.isBusy() || crafter.isBusy() || tripGoingOn()) {
            return "supply-trip";
        }
        return null;
    }

    public boolean tripGoingOn() {
        return tripGoesOn;
    }

    /** Money spent since the build started. */
    public double spentTotal() {
        return spentTotal;
    }

    /** Given up on for now (clicked again and again without a change). */
    public boolean givenUpNow(net.minecraft.core.BlockPos pos) {
        return givenUp(pos);
    }

    @javax.annotation.Nullable
    private String pauseReasonNow() {
        if (running) {
            // Not while flying to the next spot: blocks placed on the move come out wrong
            if (Minecraft.getInstance().player != null && mover.isTravelling(Minecraft.getInstance().player)) {
                return "flying";
            }
            // The server slow to answer the clicks: wait for its answer, then slower for a while (see ServerWatch)
            if (ServerWatch.stalled()) {
                return "server slow to answer";
            }
            // Optional cap for strict anticheats: no more than this many placements per second; and the speed
            // guard's limit while the server catches up
            int cap = Configs.Speed.MAX_PLACEMENTS_PER_SECOND.getIntegerValue();
            int guard = ServerWatch.cap();
            if (guard > 0 && (cap <= 0 || guard < cap)) {
                cap = guard;
            }
            if (cap > 0 && stats.placedInLastSecond() >= cap) {
                return guard == cap ? "speed guard limit" : "placement cap";
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
        if (tiller.isBusy()) {
            return "tilling";
        }
        return pausedByOthers() ? "chest/shop/crafting/rails" : null;
    }

    /**
     * Something other than the printer's own pace holds it back now (the speed guard, a placement cap, an item swap,
     * the server lagging, a chest or the shop, breaking): the mover doesn't learn the printer's speed from this time.
     */
    public boolean heldBackNow() {
        String why = pauseReasonNow();
        return why != null && !why.equals("flying") || ServerWatch.cap() > 0;
    }

    private boolean pausedByOthers() {
        return breaker.isBusy() || eater.isBusy() || fluids.isBusy() || tiller.isBusy() || rails.isBusy() || entities.isBusy() || crafter.isBusy() || fetcher.isBusy()
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
    /** Of the printer's last offer: blocks left out as not ready yet (what they stand on isn't there, see PlacementOrder). */
    public int lastNotReadyCount;
    public int lastHeldCount;

    /** What the printer reached on its last turn (its own list, its own measure of range), and when. */
    private java.util.Set<net.minecraft.core.BlockPos> printerReach = java.util.Set.of();
    private long printerReachTick = -100;

    /**
     * The positions the printer reached a moment ago, or null when that's not known (no recent turn). The mover
     * counts on these only: it measured a little further than the printer, stopped a lane for two blocks at the
     * edge that the printer never took from there, and stood still for minutes (a test of two layers at once).
     */
    @javax.annotation.Nullable
    public java.util.Set<net.minecraft.core.BlockPos> printerReachNow() {
        return clientTicks - printerReachTick <= 4 ? printerReach : null;
    }

    public java.util.List<net.minecraft.core.BlockPos> filterPrinterPositions(java.util.List<net.minecraft.core.BlockPos> positions) {
        if (running) {
            printerReach = new java.util.HashSet<>(positions);
            printerReachTick = clientTicks;
        }
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
        long holdBack = Math.max(Math.max(8, 2L * ServerLag.pingTicks(mc) + 6), ServerWatch.answerP95() + 2L);
        recentlyTried.values().removeIf(at -> now - at > holdBack);
        // Nor next to a block clicked a moment ago: the printer may click against that neighbour, and if the server
        // doesn't have it yet, the server puts the new block INTO the neighbour's spot (dirt where mud should go)
        long confirm = Math.max(3, 2L * ServerLag.pingTicks(mc) + 2);
        // Not farther than clicks have been seen to work on this server (see ReachStats): clicks over 4 blocks from
        // the eye placed nothing 3 to 4 times as often on the player's server, and each one is a turn lost
        double printerRange = me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
        double learned = ReachStats.reach(printerRange);
        double maxReachSq = learned < printerRange - 0.05 ? (learned + 0.25) * (learned + 0.25) : Double.MAX_VALUE;
        net.minecraft.world.phys.Vec3 eyeNow = mc.player.getEyePosition();
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
            if (eyeNow.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > maxReachSq) {
                RunLog.hold(pos, "beyond-learned-reach");
                continue;
            }
            // Also the fixed wait (at least as long as the server's slow answers) with the answers: 18 % of the clicks
            // of a log were on a block clicked 5 to 10 ticks before, the answer to that still to come
            boolean waiting = acks && ServerAcks.isWaiting(pos) || recentlyTried.containsKey(pos);
            boolean nextToWaiting = result == BlockCheck.Result.MISSING
                    && (acks ? ServerAcks.nextToWaiting(pos) : nextToUnconfirmed(pos, now, confirm));
            // Sand, gravel, concrete powder: only once the server surely has the block under it (string placed a
            // moment before; the sand fell or placed nothing 53 times on the player's server)
            boolean onUnconfirmed = acks && result == BlockCheck.Result.MISSING
                    && schematic.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                    && ServerAcks.unconfirmed(pos.below(), 3);
            // Not clicked against a block about to go (a flower, a grown cactus): it would land in that block's spot
            net.minecraft.core.BlockPos support = result == BlockCheck.Result.MISSING
                    && !me.aleksilassila.litematica.printer.config.Configs.PRINT_IN_AIR.getBooleanValue() ? Clicks.clickedFor(mc.level, pos) : null;
            boolean supportGoing = support != null && Clicks.goingAway(mc.level, support);
            // Not held back until what it's clicked against is confirmed (85 % of a log's refused clicks were against a
            // block placed at most 10 ticks before): on a test farm nearly every block waited so, 10 % slower in all,
            // for the 3 % of clicks the server refuses. Sand still waits for its string (it falls otherwise, above)
            boolean supportUnconfirmed = false;
            // Nor a block that can't stand there as things are (a cactus with its sand still to come, or next to a
            // block): the click is refused, and costs a turn
            boolean cantStand = result == BlockCheck.Result.MISSING && !schematic.getBlockState(pos).canSurvive(mc.level, pos);
            if (waiting || givenUp(pos) || nextToWaiting || onUnconfirmed || supportGoing || supportUnconfirmed || cantStand
                    || heldForBelow(mc, schematic, pos) || itemSettling(mc, schematic, pos)) {
                held++;
                if (RunLog.isActive()) {
                    RunLog.hold(pos, waiting ? "awaiting-answer" : givenUp(pos) ? "given-up" : nextToWaiting ? "next-to-unanswered"
                            : onUnconfirmed ? "below-unconfirmed(" + pos.below().toShortString().replace(" ", "") + ")"
                            : supportGoing ? "support-going(" + LayerManager.blockName(mc.level.getBlockState(support)) + ")"
                            : supportUnconfirmed ? "support-unconfirmed(" + support.toShortString().replace(" ", "") + ")"
                            : cantStand && Tiller.untilled(mc.level, schematic, pos.below()) ? "needs-tilling"
                            : cantStand ? "cant-stand-there-yet"
                            : itemSettling(mc, schematic, pos) ? "item-settling" : "held-for-below");
                }
                continue;
            }
            forPrinter.add(pos);
        }
        lastUnfinishedCount = unfinished;
        lastHeldCount = held;
        lastOfferedTick = clientTicks;
        lastRawCount = all.size();
        java.util.List<net.minecraft.core.BlockPos> ordered = PlacementOrder.order(mc.level, schematic, forPrinter, mover.orderFrom(mc.player),
                mc.player.getMainHandItem().isEmpty() ? null : mc.player.getMainHandItem().getItem(), mover.ticksLeftInReach(mc.player));
        lastNotReadyCount = forPrinter.size() - ordered.size();
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

    /**
     * The printer's air place (on for a layer with floating blocks) is used for this spot: only when it has nothing
     * beside or under it to be placed against, now or later. Everything else is clicked against its neighbour.
     */
    public boolean airPlaceAt(net.minecraft.core.BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (mc.level == null || schematic == null) {
            return true;
        }
        return AirPlace.needsAirPlace(mc.level, schematic, pos, schematic.getBlockState(pos));
    }

    /** An item with no stack on the hotbar for this long is taken from the inventory by the printer itself. */
    private static final int OFF_HOTBAR_WAIT = 6;
    /** Since when each item has had no stack on the hotbar (while there are some in the inventory). */
    private final java.util.Map<net.minecraft.world.item.Item, Long> offHotbarSince = new java.util.HashMap<>();

    /**
     * The item for this block was just swapped into its slot: placed in a moment, once the server surely has it. Or it
     * has no stack on the hotbar right now (used up): the add-on puts one there in a tick or two. Taking it from the
     * inventory, the printer swaps it into the hand and the item in hand goes out; the next block of that kind swaps it
     * back, each time with a pause (string and sand in a cactus farm: 16% of the printer's turns).
     */
    private boolean itemSettling(Minecraft mc, fi.dy.masa.litematica.world.WorldSchematic schematic, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.item.ItemStack stack = StateRules.itemFor(schematic.getBlockState(pos));
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.world.item.Item item = stack.getItem();
        net.minecraft.world.entity.player.Inventory inv = mc.player.getInventory();
        if (inventory.settlingFor(inv, item)) {
            return true;
        }
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.getSelectionSize(); slot++) {
            if (inv.getItem(slot).is(item)) {
                offHotbarSince.remove(item);
                return false;
            }
        }
        if (inv.countItem(item) == 0) {
            return false;
        }
        long since = offHotbarSince.computeIfAbsent(item, k -> clientTicks);
        return clientTicks - since < OFF_HOTBAR_WAIT;
    }

    /** Counts farmland still to till in the per-level needs (never an item that's bought). */
    private static final net.minecraft.world.item.Item TILLING = net.minecraft.world.item.Items.STRUCTURE_VOID;
    /** Farmland still to till in the whole schematic (as last counted): a hoe is needed while there is. */
    private int wholeTilling;

    /** True if the whole schematic still needs this (as last counted). */
    public boolean wholeNeeds(net.minecraft.world.item.Item item) {
        return neededWhole.getOrDefault(item, 0) > 0;
    }

    /** How many of this item the whole schematic still needs (as last counted). */
    public int wholeCount(net.minecraft.world.item.Item item) {
        return neededWhole.getOrDefault(item, 0);
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
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning()) {
            return "buying from /" + Configs.Shop.SHOP_COMMAND.getStringValue();
        }
        if (no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.isRunning()) {
            return "reading the shop";
        }
        if (fetcher.isBusy()) {
            return fetcher.describe();
        }
        if (crafter.isBusy()) {
            return crafter.describe();
        }
        if (eater.isBusy()) {
            return "eating";
        }
        if (breaker.isBusy()) {
            return "breaking a wrong block";
        }
        if (fluids.isBusy()) {
            return "using a bucket";
        }
        if (tiller.isBusy()) {
            return "tilling dirt the printer left";
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
        int tilling = 0;
        for (java.util.Map<net.minecraft.world.item.Item, Integer> level : wholeByY.values()) {
            for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> entry : level.entrySet()) {
                if (entry.getKey() == TILLING) {
                    tilling += entry.getValue();
                } else if (Fluids.isBucket(entry.getKey())) {
                    needs.put(entry.getKey(), 1);
                } else {
                    needs.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
        }
        wholeTilling = tilling;
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
                        if (target.getBlock() == net.minecraft.world.level.block.Blocks.FARMLAND
                                && (result == BlockCheck.Result.MISSING || result.needsBreaking() || result == BlockCheck.Result.PRINTER_FIXES)) {
                            needs.merge(TILLING, 1, Integer::sum);
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
        // Farmland is dirt tilled with a hoe (see Tools: from storage, else crafted)
        if (wholeTilling > 0 && !Tools.covered(Tools.HOE, mc.player.getInventory())) {
            needs.put(Tools.HOE, 1);
        }
        if (Configs.Breaking.BREAK_WRONG_BLOCKS.getBooleanValue()) {
            for (net.minecraft.world.item.Item tool : Tools.breakingToolsMissing(schematicBlocks, mc.player.getInventory())) {
                needs.put(tool, 1);
            }
        }
        neededWhole.clear();
        neededWhole.putAll(needs);
        cleaner.remember(needs);
        missingWhole.clear();
        needs.forEach((item, count) -> {
            int have = Tools.isMarker(item) ? Tools.count(item, mc.player.getInventory()) : mc.player.getInventory().countItem(item);
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
        if (BlockBreaker.alwaysBreak(current)) {
            // One of the "always break" blocks (a cactus flower grown where sand goes): not the player's building
            // either; 12 of them were left alone as the player's, the sand never went in and the cactus under each
            // stopped growing for good (a cactus grows only with nothing on top)
            return false;
        }
        if (conflicts.add(pos.immutable())) {
            fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
            String wanted = schematic != null ? LayerManager.blockName(schematic.getBlockState(pos)) : "?";
            ConflictGroup group = conflictGroups.computeIfAbsent(LayerManager.blockName(current) + " where " + wanted + " goes", k -> new ConflictGroup());
            group.add(pos);
            // The first few one by one; the rest in the summary (2881 lines of glass where string goes, twice)
            if (conflicts.size() <= CONFLICTS_LISTED) {
                BuildLog.line("CONFLICT", pos.toShortString() + ": the world has " + current + ", the schematic wants "
                        + (schematic != null ? schematic.getBlockState(pos) : "?") + " (left alone, not counted)");
            }
            conflictsChanged = true;
        }
        return true;
    }

    /** Conflicts written one by one to the build log, the rest only counted (see conflictSummary). */
    private static final int CONFLICTS_LISTED = 20;
    /** Conflicts by what's there and what the schematic wants: how many and where. */
    private final java.util.Map<String, ConflictGroup> conflictGroups = new java.util.LinkedHashMap<>();
    private boolean conflictsChanged;
    private long conflictsLoggedAt = -100000;
    private boolean conflictsTold;

    private static final class ConflictGroup {
        int count;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        void add(net.minecraft.core.BlockPos pos) {
            count++;
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        @Override
        public String toString() {
            return count + " (Y " + (minY == maxY ? minY : minY + " to " + maxY) + ", x " + minX + " to " + maxX + ", z " + minZ + " to " + maxZ + ")";
        }
    }

    /** "1440 purple_stained_glass where tripwire goes (Y 286 to 293, ...); ..." */
    private String conflictSummary() {
        StringBuilder sb = new StringBuilder();
        conflictGroups.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue().count, a.getValue().count))
                .forEach(e -> sb.append(sb.length() == 0 ? "" : "; ").append(e.getValue().count).append(' ').append(e.getKey())
                        .append(e.getValue().toString().substring(String.valueOf(e.getValue().count).length())));
        return sb.toString();
    }

    /**
     * The conflicts found so far, in the build log when they're first found and again once there are twice as many
     * (the whole list, 9 KB, came 23 times in an hour), and at the end; said once in chat.
     */
    private void logConflicts() {
        if (!conflictsChanged || clientTicks - conflictsLoggedAt < 20 * 30) {
            return;
        }
        conflictsChanged = false;
        conflictsLoggedAt = clientTicks;
        if (conflicts.size() >= 2 * conflictsLogged || conflictsLogged == 0) {
            conflictsLogged = conflicts.size();
            BuildLog.line("CONFLICT", conflicts.size() + " so far, left alone: " + conflictSummary());
        }
        if (!conflictsTold && conflicts.size() >= 10) {
            conflictsTold = true;
            ConflictGroup biggest = conflictGroups.values().stream().max(java.util.Comparator.comparingInt(g -> g.count)).orElse(null);
            String what = conflictGroups.entrySet().stream().max(java.util.Comparator.comparingInt(e -> e.getValue().count))
                    .map(java.util.Map.Entry::getKey).orElse("?");
            Chat.warn(conflicts.size() + " blocks of yours stand where the schematic wants something else (most: " + biggest.count
                    + " " + what + " at Y " + (biggest.minY == biggest.maxY ? biggest.minY : biggest.minY + "-" + biggest.maxY)
                    + "). They're left alone and skipped; move the schematic or the blocks if that's wrong.");
        }
    }

    /**
     * A pumpkin or melon that's the farm's, not the player's building: one with a stem next to it in the world (grown),
     * or one where the schematic wants a stem (six were left alone on the player's farm, their stems never planted:
     * put there from an older copy of the schematic).
     */
    private static boolean grownFruit(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState current) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.level.block.Block fruit = current.getBlock();
        if (mc.level == null || (fruit != net.minecraft.world.level.block.Blocks.PUMPKIN && fruit != net.minecraft.world.level.block.Blocks.MELON)) {
            return false;
        }
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (schematic != null) {
            net.minecraft.world.level.block.Block wanted = schematic.getBlockState(pos).getBlock();
            if (wanted instanceof net.minecraft.world.level.block.StemBlock || wanted instanceof net.minecraft.world.level.block.AttachedStemBlock) {
                return true;
            }
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
        if (tiller.isBusy()) {
            return BuildStats.Activity.PLACING;
        }
        return mover.activity();
    }

    /** A trip (storage, then the shop) goes on after each part, until everything is up to its share. */
    private boolean tripGoesOn;
    /** When the shop was last scanned again by itself (an item it needed wasn't in the scan). */
    private long lastAutoRescan = -100000000L;
    /** Storage was looked in on this trip already (once per trip: a vault with nothing that fits isn't opened again). */
    private boolean fetchedThisTrip;
    /** The vaults were filled on this trip already (once per trip, see StockUp). */
    private boolean stockedThisTrip;
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
        boolean dueAnyway = false;
        boolean outOfSomething = false;
        java.util.Set<net.minecraft.world.item.Item> forLane = clientTicks <= laneShortUntil ? laneShort : java.util.Set.of();
        laneShort = java.util.Set.of();
        for (var entry : layerNeeds.entrySet()) {
            net.minecraft.world.item.Item item = entry.getKey();
            // Buckets aren't bought, and an empty one is a water bucket again a moment later (an infinite water
            // bucket refills itself, another is filled from water nearby): never a reason for a trip (each use
            // of the bucket used to start one, topping up 1 cherry leaf or a stack of mud on the way)
            if (InventoryCleaner.isBucket(item)) {
                continue;
            }
            // A tool: only the plain ones count (an enchanted diamond hoe of the player's isn't "a hoe" here)
            int have = Tools.isMarker(item) ? Tools.count(item, inventory) : inventory.countItem(item);
            if (have >= entry.getValue()) {
                continue;
            }
            // A tool that can't be had (a recipe the server doesn't allow) never stops the build: tilling or
            // breaking waits for it, everything else goes on (a stone shovel that couldn't be crafted stopped a build)
            if (have == 0 && !Tools.isMarker(item)) {
                outOfSomething |= !autoBuy || !gettable(mc, item) && !retryPending(item);
            }
            // Only once it's (nearly) used up: this many or fewer left; or the lane about to be flown takes more than
            // is left (topped up before it, not with a stop halfway along it: 14 s in the middle of a lane once)
            if (gettable(mc, item) && (have <= buyWhenLeft || forLane.contains(item))
                    && (storage || autoBuy && no.sindrens.printeraddon.shop.ShopBuyer.room(mc, item) > 0)) {
                due = true;
                if (have <= buyWhenLeft) {
                    dueAnyway = true;
                }
            }
        }
        // The layer's water needs a bucket and there's none at all (not even an empty one), or only empty ones for
        // minutes (no water nearby to fill them): looked for in storage, then bought (once a minute at most), and
        // said (once in two minutes); the layer waited minutes for water without one once
        int waterBuckets = inventory.countItem(net.minecraft.world.item.Items.WATER_BUCKET);
        int emptyBuckets = inventory.countItem(net.minecraft.world.item.Items.BUCKET);
        if (waterBuckets > 0 || emptyBuckets == 0) {
            emptyBucketsSince = -1;
        } else if (emptyBucketsSince < 0) {
            emptyBucketsSince = clientTicks;
        }
        boolean noBucket = layerNeeds.containsKey(net.minecraft.world.item.Items.WATER_BUCKET) && waterBuckets == 0
                && (emptyBuckets == 0 || emptyBucketsSince >= 0 && clientTicks - emptyBucketsSince > 20 * 60 * 3);
        boolean canBuyBucket = autoBuy && no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, net.minecraft.world.item.Items.WATER_BUCKET)
                && !cantGet(net.minecraft.world.item.Items.WATER_BUCKET);
        if (noBucket && (storage || canBuyBucket) && clientTicks - bucketLookedFor > 20 * 60) {
            bucketLookedFor = clientTicks;
            due = true;
        }
        if (noBucket && clientTicks - bucketWarned > 20 * 60 * 2) {
            bucketWarned = clientTicks;
            Chat.warn("No water bucket" + (emptyBuckets > 0 ? " with water" : "") + " in your inventory: the water on this layer can't go in"
                    + (storage || canBuyBucket ? " (" + (storage ? "looking in your vaults and chests" : "") + (storage && canBuyBucket ? ", then " : "")
                    + (canBuyBucket ? "buying one" : "") + ")." : ". Put one in your inventory."));
            BuildLog.line("PLAN", "no water bucket in the inventory, the layer's water waits for one" + (storage ? ": looking in storage" : "")
                    + (canBuyBucket ? ", else buying one" : ""));
        }
        boolean goesOn = tripGoesOn;
        tripGoesOn = false;
        if (!due && !goesOn) {
            pauseIfOut(mc, outOfSomething);
            return false;
        }
        // Not to the vaults or the shop while the server lags hard: its menus didn't open, or opened the wrong page,
        // and a trip came back with nothing after 94 s. Building waits for the server meanwhile anyway
        if (ServerLag.hardLag(mc)) {
            if (clientTicks - lagNotedAt > 20 * 10) {
                lagNotedAt = clientTicks;
                BuildLog.line("SUPPLY", String.format(java.util.Locale.ROOT, "waiting: the server lags (%.0f ticks per second, last word %.1f s ago)",
                        ServerLag.tps(), ServerLag.ticksSinceTimeUpdate() / 20.0));
            }
            tripGoesOn = goesOn;
            buyCooldown = 20;
            return goesOn;
        }
        if (!goesOn) {
            fetchedThisTrip = false;
            stockedThisTrip = false;
            RunLog.supplyStart(mc, supplyTrigger(inventory, layerNeeds, buyWhenLeft, forLane, noBucket));
        }

        // Filling the vaults (see StockUp): one step a round (buy, put away...) until it's done, nothing else of the
        // trip in between (it took what it had just put away straight back out); then the trip takes from the vaults
        if (stock.isActive()) {
            if (!stock.step(mc, this)) {
                fetchedThisTrip = false;
            }
            tripGoesOn = true;
            buyCooldown = 0;
            mover.stop(mc);
            return true;
        }

        InventoryBudget.Plan plan = InventoryBudget.plan(inventory, layerNeeds, neededWhole, cleaner.storable(inventory));
        // A trip only for the lane: only if the lane's item is below its share (else it can't take more anyway)
        if (due && !dueAnyway && !goesOn && !noBucket && forLane.stream().noneMatch(plan.wants()::containsKey)) {
            return false;
        }
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
        // The hoe won't last the layer: another one on this trip, instead of a trip of its own when it breaks
        int tilling = layers.tillingLeft();
        int hoeUses = Tools.hoeUses(inventory) + Tools.privateHoeUses(inventory);
        if (tilling > hoeUses && hoeUses < 150 && !wants.containsKey(Tools.HOE)) {
            wants.put(Tools.HOE, Tools.hoesFor(tilling - hoeUses, inventory));
        }
        // Only empty buckets for minutes: a water bucket is got as if there were none
        if (noBucket && !wants.containsKey(net.minecraft.world.item.Items.WATER_BUCKET)) {
            wants.put(net.minecraft.world.item.Items.WATER_BUCKET, 1);
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> list = InventoryBudget.fitNow(inventory, wants);
        addNextLayer(mc, inventory, layerNeeds, list);
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
        // A tool: a plain one in storage; and what a diamond one is made of, in case there's none (diamonds or a
        // block of diamond, sticks), so it's crafted from those and not bought
        int diamonds = 0;
        int tools = 0;
        for (var entry : list.entrySet()) {
            if (Tools.isMarker(entry.getKey())) {
                diamonds += Tools.diamondsFor(entry.getKey()) * entry.getValue();
                tools += entry.getValue();
            }
        }
        if (diamonds > 0) {
            int knownDiamonds = fetcher.knownCount(net.minecraft.world.item.Items.DIAMOND);
            if (knownDiamonds > 0) {
                fromStorage.merge(net.minecraft.world.item.Items.DIAMOND, Math.min(knownDiamonds, diamonds), Integer::sum);
            }
            if (knownDiamonds < diamonds && fetcher.knownCount(net.minecraft.world.item.Items.DIAMOND_BLOCK) > 0) {
                fromStorage.merge(net.minecraft.world.item.Items.DIAMOND_BLOCK, (diamonds - knownDiamonds + 8) / 9, Integer::sum);
            }
            if (fetcher.knownCount(net.minecraft.world.item.Items.STICK) > 0) {
                fromStorage.merge(net.minecraft.world.item.Items.STICK, 2 * tools, Integer::sum);
            }
        }
        // Something the vaults are known to be out of, to be bought in bulk into them: that first, before taking from
        // them (taken out, it was all put away again to make room for the purchase, and taken out once more)
        if (!fetchedThisTrip && !stockedThisTrip && autoBuy && storage) {
            java.util.Map<net.minecraft.world.item.Item, Integer> shortfall = new java.util.LinkedHashMap<>();
            list.forEach((item, count) -> {
                int known = fetcher.knownCount(item);
                if (count > known) {
                    shortfall.put(item, count - known);
                }
            });
            if (!shortfall.isEmpty()) {
                stockedThisTrip = true;
                if (stock.start(mc, shortfall, list, neededWhole, fetcher, layerNeeds.keySet())) {
                    stock.step(mc, this);
                    tripGoesOn = true;
                    buyCooldown = 0;
                    mover.stop(mc);
                    return true;
                }
            }
        }
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

        // The shop has to be used and the build needs much more than fits: one whole inventory of each into the vaults
        // first, one step a round (make room, buy, put away, buy...); then the trip takes its share from the vaults
        if (!stockedThisTrip && stock.start(mc, list, list, neededWhole, fetcher, layerNeeds.keySet())) {
            stockedThisTrip = true;
            if (!stock.step(mc, this)) {
                fetchedThisTrip = false;
            }
            tripGoesOn = true;
            buyCooldown = 0;
            mover.stop(mc);
            return true;
        }
        stockedThisTrip = true;

        // In the shop only what's worth a purchase: what's (nearly) used up, a stack or more, or the rest of the
        // layer; by whole stacks where that's about the same (a purchase by the stack is one click)
        java.util.Map<net.minecraft.world.item.Item, Integer> toBuy = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map.Entry<net.minecraft.world.item.Item, Integer>> byUrgency = new java.util.ArrayList<>(list.entrySet());
        byUrgency.sort(java.util.Comparator.comparingInt(e -> inventory.countItem(e.getKey())));
        for (var entry : byUrgency) {
            net.minecraft.world.item.Item item = entry.getKey();
            int want = entry.getValue();
            int stack = item.getDefaultMaxStackSize();
            int have = Tools.isMarker(item) ? Tools.count(item, inventory) : inventory.countItem(item);
            // Buckets only when there's none with water at all (storage was looked in above, or had none)
            boolean bucketNeeded = item == net.minecraft.world.item.Items.WATER_BUCKET && noBucket && canBuyBucket;
            if (!gettable(mc, item) || InventoryCleaner.isBucket(item) && !bucketNeeded) {
                continue;
            }
            // Not running low: only a stack or more is worth a purchase (it bought 1 cherry leaf at 127 of 128,
            // a stack of mud at 700 of 704); the rest comes on the trip when it does run low
            if (have > buyWhenLeft && want < stack) {
                continue;
            }
            if (stack > 1 && no.sindrens.printeraddon.shop.ShopBuyer.buysWholeStacks(mc, item)) {
                // By the whole stack, rounded up: what's left over goes into storage for later (setting an exact
                // amount took many clicks: 102 mud)
                want = (want + stack - 1) / stack * stack;
            } else if (want >= stack / 2 && stack > 1) {
                // Never more than the whole build still needs
                int useful = Math.max(want, neededWhole.getOrDefault(item, 0) - have);
                want = Math.min(useful, Math.max(stack, Math.round(want / (float) stack) * stack));
            }
            toBuy.put(item, want);
        }

        fitWholeStacks(mc, inventory, toBuy);

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
                Chat.warn(String.format(java.util.Locale.ROOT, "Spending limit reached ($%,.0f of $%,.0f): nothing more is bought. Start again (J+B) for a new limit,"
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
        didntDeliver(no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.notBought());
        // Nothing to buy after all (e.g. no shop scan): still craft what can be crafted from the inventory
        for (CraftPlan.Step step : craftAfterBuying) {
            crafter.craft(step.item(), step.count());
        }
        craftAfterBuying = java.util.List.of();
        return false;
    }

    /** What started a trip for items, for the log: "string 8 left", "the lane takes more sand", "no water bucket". */
    private static String supplyTrigger(net.minecraft.world.entity.player.Inventory inventory, java.util.Map<net.minecraft.world.item.Item, Integer> needs,
                                        int buyWhenLeft, java.util.Set<net.minecraft.world.item.Item> forLane, boolean noBucket) {
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.world.item.Item item : needs.keySet()) {
            int have = Tools.isMarker(item) ? Tools.count(item, inventory) : inventory.countItem(item);
            if (have <= buyWhenLeft && have < needs.get(item) && !InventoryCleaner.isBucket(item)) {
                sb.append(sb.length() == 0 ? "" : ", ").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath())
                        .append(' ').append(have).append(" left");
            }
        }
        if (sb.length() == 0 && !forLane.isEmpty()) {
            sb.append("the lane takes more ").append(forLane.stream().map(i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath()).toList());
        }
        if (noBucket) {
            sb.append(sb.length() == 0 ? "" : ", ").append("no water bucket");
        }
        return sb.length() == 0 ? "topping up" : sb.toString();
    }

    /** A purchase for StockUp, within the spending limit. False when nothing could be started. */
    boolean startPurchase(Minecraft mc, java.util.Map<net.minecraft.world.item.Item, Integer> toBuy) {
        double budget = spendLimit < 0 ? Double.MAX_VALUE : spendLimit - spentTotal;
        if (budget <= 1) {
            return false;
        }
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.start(mc, toBuy, budget)) {
            buying = true;
            mover.stop(mc);
            return true;
        }
        return false;
    }

    /**
     * Two stacks of what the inventory has most of (a building item, never a tool or bucket) into storage, so a
     * crafted tool has a slot. Without storage: said once, nothing is thrown away.
     */
    private void makeRoomForCrafting(Minecraft mc) {
        net.minecraft.world.entity.player.Inventory inventory = mc.player.getInventory();
        java.util.Map<net.minecraft.world.item.Item, Integer> stacks = new java.util.HashMap<>();
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getMaxStackSize() > 1 && !InventoryCleaner.isBucket(stack.getItem())) {
                stacks.merge(stack.getItem(), 1, Integer::sum);
            }
        }
        net.minecraft.world.item.Item most = stacks.entrySet().stream().filter(e -> e.getValue() >= 3)
                .max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
        if (most != null && fetcher.startStore(mc, java.util.Map.of(most, stacks.get(most) - 2))) {
            BuildLog.line("CRAFT", "inventory full: 2 stacks of " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(most).getPath()
                    + " into storage to make room for a crafted tool");
            Chat.info("Inventory full: putting 2 stacks of " + new net.minecraft.world.item.ItemStack(most).getHoverName().getString()
                    + " in storage to make room for a tool.");
            buyCooldown = 0;
            return;
        }
        Chat.warn("Inventory full: no room for a crafted tool, and nowhere to put things (link a chest or make room in your vaults).");
    }

    /** An item the layer needs (not a bucket) is used up and can be got. */
    private boolean layerItemOut(Minecraft mc) {
        for (var entry : layers.layerNeeds().entrySet()) {
            net.minecraft.world.item.Item item = entry.getKey();
            if (entry.getValue() > 0 && !InventoryCleaner.isBucket(item) && !Tools.isMarker(item)
                    && mc.player.getInventory().countItem(item) == 0 && gettable(mc, item)) {
                return true;
            }
        }
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

    /** Building items are being put into storage after auto build stopped (see putAway). */
    private boolean puttingAway;
    /** The add-on was switched off during auto build: off once the items are put away. */
    private boolean offAfterPutAway;

    public boolean isPuttingAway() {
        return puttingAway;
    }

    /**
     * After auto build stops (by the player, or with the build done), or from the J+B menu: the building materials,
     * what the add-on crafts with and its hoes go into the vaults (and linked chests within reach), so the inventory
     * is free and the next start takes what it needs again. Returns true if it started.
     */
    public boolean putAway(Minecraft mc, boolean byHand) {
        if (running || puttingAway || mc.player == null || mc.gameMode == null || mc.player.getAbilities().instabuild) {
            return false;
        }
        if (!ChestFetcher.hasStorage(mc)) {
            if (byHand) {
                Chat.warn("Nowhere to put them: set your vaults (/pv numbers) in the settings, or link a chest (J+B).");
            }
            return false;
        }
        java.util.Set<net.minecraft.world.item.Item> items = cleaner.putAway(mc.player.getInventory());
        if (items.isEmpty()) {
            if (byHand) {
                Chat.info("No building items to put away.");
            }
            return false;
        }
        fetcher.reset();
        if (!fetcher.startPutAway(mc, items)) {
            if (byHand) {
                Chat.warn("No vault and no linked chest within reach to put them in.");
            }
            return false;
        }
        puttingAway = true;
        BuildLog.lineOrKeep("STORAGE", "putting away after the build: " + items);
        Chat.info("Putting the building items away in your storage (J+Q stops it).");
        return true;
    }

    /** Stops putting items away (J+Q, or auto build starting again). */
    public void cancelPutAway() {
        if (puttingAway) {
            puttingAway = false;
            fetcher.reset();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        }
        if (offAfterPutAway) {
            offAfterPutAway = false;
            switchOff(Minecraft.getInstance());
        }
    }

    private void tickPutAway(Minecraft mc) {
        if (running || mc.player == null || mc.gameMode == null || mc.level == null) {
            puttingAway = false;
            return;
        }
        if (!fetcher.tick(mc, mover)) {
            puttingAway = false;
            if (offAfterPutAway) {
                offAfterPutAway = false;
                switchOff(mc);
            }
        }
    }

    public void start(Minecraft mc) {
        if (running) {
            return;
        }
        if (AreaBreaker.INSTANCE.isRunning()) {
            Chat.warn("Auto break is running: stop it first (J+B, Auto break tab), then start auto build.");
            return;
        }
        if (puttingAway) {
            // Starting again while the items go into storage: they stay (the trip takes what it needs anyway)
            puttingAway = false;
            offAfterPutAway = false;
            fetcher.reset();
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
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
        BuildLog.open("auto build " + placementNames());
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
        RunLog.start(mc);
        int renderDistance = Configs.Generic.BUILD_RENDER_DISTANCE.getIntegerValue();
        if (renderDistance > 0 && mc.options.renderDistance().get() != renderDistance) {
            savedRenderDistance = mc.options.renderDistance().get();
            mc.options.renderDistance().set(renderDistance);
        }
        safety.reset();
        emptyDuringBuild.clear();
        triedDuringBuild.clear();
        conflicts.clear();
        conflictsLogged = 0;
        conflictGroups.clear();
        conflictsChanged = false;
        conflictsTold = false;
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
        buyFailures.clear();
        warnedLimit = false;
        buying = false;
        buyCooldown = 0;
        cleaner.reset();
        StackMerge.reset();
        crafter.reset();
        fetcher.reset();
        eater.reset(mc);
        craftAfterBuying = java.util.List.of();
        craftingFor = java.util.Set.of();
        stats.start();
        speed.reset();
        recentlyTried.clear();
        ServerAcks.reset();
        ServerWatch.reset();
        triesAt.clear();
        givenUp.clear();
        unsealFor.clear();
        unsealSince.clear();
        unsealCount.clear();
        watchTicks = 0;
        watchStrikes = 0;
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
        stock.reset();
        fluids.reset();
        tiller.reset();
        rails.reset();
        entities.reset();
        breaker.setForced(airPlace.misplaced());

        // Which kinds of blocks the schematic has, from its palettes (to tell the player's own buildings from wrong
        // blocks before anything is broken); what it needs is read in the background from the first tick on
        schematicBlocks.addAll(SchematicHelper.blockKinds());
        wholeFirstPass = true;
        wholeNextY = Integer.MIN_VALUE;
        if (Configs.Layers.AUTO_LAYER.getBooleanValue() && !layers.start(mc)) {
            // Nothing to build (no placement, or every layer done): said in chat, nothing ran to ask about
            stopNote = "could-not-start";
            stop(mc, null);
            return;
        }
        // What this run is, for a shared log's summary (see LogShare)
        int[] yRange = SchematicHelper.yRange(SchematicHelper.areas());
        RunLog.what("Auto build: " + placementNames() + (yRange == null ? "" : ", Y " + yRange[0] + " to " + yRange[1])
                + (layers.isActive() ? ", started at " + layers.describeLayers() : ""));

        Chat.info("Auto build §astarted§r.");
    }

    /** The names of the schematic placements switched on ("Pumpkin_farm"), for the log's name and summary. */
    public static String placementNames() {
        StringBuilder names = new StringBuilder();
        for (fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement
                : fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (placement.isEnabled()) {
                names.append(names.isEmpty() ? "" : ", ").append(placement.getName());
            }
        }
        return names.isEmpty() ? "?" : names.length() > 40 ? names.substring(0, 40) : names.toString();
    }

    /** Why auto build stops when no reason is shown: "finished", "could-not-start", or null (the player stopped it). */
    @javax.annotation.Nullable
    private String stopNote;

    /** Stops auto build. {@code reason} is shown as a warning when it isn't the player's own choice. */
    public void stop(Minecraft mc, String reason) {
        stop(mc, reason, true);
    }

    /** {@code mayPutAway}: stopped by the player or done, the building items go into storage (see putAway). */
    private void stop(Minecraft mc, String reason, boolean mayPutAway) {
        boolean wasRunning = running;
        // Never left standing in a cactus: up and out of it first, whatever the reason for stopping (a stop for the
        // damage it did left the player in it, taking more)
        if (wasRunning && mc.player != null && mc.level != null && Mover.harmNear(mc.level, mc.player.position()) != null) {
            escapeTicks = ESCAPE_TICKS;
            BuildLog.line("SAFETY", "stopping next to " + LayerManager.blockName(mc.level.getBlockState(Mover.harmNear(mc.level, mc.player.position())))
                    + ": flying up and out of it first");
        }
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
        stock.reset();
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
                    : resumeLater ? (disconnectReason.isEmpty() ? "left the build's world (disconnected, or moved to another of the network's servers)"
                    : "disconnected: " + disconnectReason) + " (picks up again once back at the build)"
                    : "stopped by you";
            BuildLog.line("END", why + " | " + stats.total() + " | " + spendingLine());
            if (tiller.tilledCount() > 0 || !schematicBlocks.isEmpty() && schematicBlocks.contains(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                BuildLog.line("TILL", tiller.summary());
            }
            if (reason == null && !resumeLater) {
                RunLog.user("stop", "");
            } else if (resumeLater) {
                RunLog.event("disconnect", "reason=\"" + (disconnectReason.isEmpty() ? "left the build's world" : disconnectReason) + "\"");
            }
            // Every run's log is worth having: asked about after every stop, the player's own too. Not for a disconnect
            // (it picks up again once back) and not when nothing could start
            String ended = reason != null ? reason : resumeLater ? "disconnected" : stopNote != null ? stopNote : "stopped-by-you";
            RunLog.end(mc, ended, !resumeLater && !"could-not-start".equals(stopNote));
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
        stopNote = null;
        BuildLog.close();
        // Stopped by the player or done (not a disconnect, an error or a safety stop): the building items go into
        // storage; the next start takes what it needs again
        if (wasRunning && mayPutAway && reason == null && !resumeLater && Configs.Shop.PUT_AWAY_ON_STOP.getBooleanValue()) {
            putAway(mc, false);
        }
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
        if (!conflicts.isEmpty()) {
            BuildLog.line("CONFLICT", conflicts.size() + " in all, left alone: " + conflictSummary());
        }
    }

    /** Stops everything at once (J+Q, the shop scan, the add-on switched off): nothing is put away. */
    public void stopEverything(Minecraft mc) {
        manualTask = ManualTask.NONE;
        AreaBreaker.INSTANCE.stop(mc, null);
        stop(mc, null, false);
    }

    /** Disconnected: stop without forgetting what was being built (see Resume). */
    /** Why the connection last ended (shown in the END line). */
    private String disconnectReason = "";

    /** The connection ended (see ClientCommonPacketListenerMixin): the reason goes into the build log at once. */
    public void disconnected(String reason) {
        if (disconnectTold) {
            return;
        }
        disconnectTold = true;
        disconnectReason = reason;
        BuildLog.lineOrKeep("DISCONNECT", "the connection ended: " + reason + " | " + ServerLag.describe());
        // Kicked or the server restarting while building, or on the way back to it: join again by itself
        if (running || buildingWhenLeft || Resume.pending()) {
            Reconnect.connectionEnded(Minecraft.getInstance(), reason);
        }
    }

    /** Told already why this connection ended (by the game's hook, or from the "Disconnected" screen). */
    private boolean disconnectTold;
    /** When the world was left (-1: in one), and whether auto build was running (or on its way back to the build). */
    private long leftAt = -1;
    private boolean buildingWhenLeft;

    /** Every tick in a world: the server is remembered for joining again. */
    public void inWorld(Minecraft mc) {
        leftAt = -1;
        disconnectTold = false;
        Reconnect.remember(mc);
    }

    /**
     * Every tick out of a world, before auto build stops for it. When the game's own hook didn't say why the
     * connection ended (the player's logs had no reason and no joining again after "Timed out", three times), the
     * reason is read from the "Disconnected" screen and joining again is planned from here.
     */
    public void outOfWorld(Minecraft mc) {
        if (leftAt < 0) {
            leftAt = clientTicks;
            buildingWhenLeft = running || Resume.pending();
        }
        if (disconnectTold || !buildingWhenLeft) {
            return;
        }
        // Only on the "Disconnected" screen: out of a world for a moment is also the network moving you to another
        // of its servers (/server Skyblock), and that's no reason to join again
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen screen)) {
            return;
        }
        net.minecraft.network.DisconnectionDetails details = ((no.sindrens.printeraddon.mixin.DisconnectedScreenAccessor) screen).printer_addon$details();
        disconnected(details == null || details.reason() == null ? "?" : details.reason().getString());
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
        // Putting away ends with the connection (a vault command on the next server would go nowhere)
        puttingAway = false;
        offAfterPutAway = false;
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
            Chat.info(limit < 0 ? "Spending limit: none." : String.format(java.util.Locale.ROOT, "Spending limit for this build: $%,.0f.", limit));
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
        return spendLimit < 0 ? String.format(java.util.Locale.ROOT, "Spent $%,.0f (no limit)", spentTotal)
                : String.format(java.util.Locale.ROOT, "Spent $%,.0f of $%,.0f", spentTotal, spendLimit);
    }

    /**
     * The whole add-on on or off (J+A, or the button in the J+B menu). Off: everything it does stops and every hook
     * into Litematica Printer passes straight through, so the printer works exactly as without the add-on.
     */
    public void setAddonEnabled(Minecraft mc, boolean on) {
        if (!on && running && !puttingAway) {
            // Off during auto build: the building items are put away first, then it's off
            manualTask = ManualTask.NONE;
            stop(mc, null);
            no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.cancel(null);
            no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.cancel(null);
            if (puttingAway) {
                offAfterPutAway = true;
                Chat.info("Printer Addon switches off once the building items are in storage (J+A again: off right away).");
                return;
            }
        }
        if (!on && puttingAway) {
            puttingAway = false;
            offAfterPutAway = false;
            fetcher.reset();
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        }
        if (!on) {
            switchOff(mc);
            return;
        }
        Configs.Generic.ADDON_ENABLED.setBooleanValue(true);
        Chat.info("Printer Addon \u00a7aon.");
    }

    private void switchOff(Minecraft mc) {
        Configs.Generic.ADDON_ENABLED.setBooleanValue(false);
        stopEverything(mc);
        no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.cancel(null);
        no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.cancel(null);
        Chat.info("Printer Addon \u00a7coff\u00a7r: Litematica Printer works on its own (J+A or J+B to switch it on again).");
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

    /** Ticks left to get the player away from a cactus (or other block that hurts), see escapeFromHarm. */
    private int escapeTicks;

    /** Still busy after a stop: flying out of a cactus, or putting the building items away (vault screens). */
    public boolean busyAfterStop() {
        return escapeTicks > 0 || puttingAway;
    }
    private static final int ESCAPE_TICKS = 80;

    /**
     * Straight up while the body touches a block that hurts, until it's clear (at most 4 s); flying is switched on
     * for it when allowed. Runs whether auto build runs or not.
     */
    private void escapeFromHarm(Minecraft mc) {
        if (escapeTicks <= 0 || mc.player == null || mc.level == null) {
            escapeTicks = 0;
            return;
        }
        escapeTicks--;
        net.minecraft.client.player.LocalPlayer player = mc.player;
        if (Mover.harmNear(mc.level, player.position()) == null) {
            escapeTicks = 0;
            BuildLog.lineOrKeep("SAFETY", String.format(java.util.Locale.ROOT, "clear of it now at %.1f %.1f %.1f (health %.1f)",
                    player.getX(), player.getY(), player.getZ(), player.getHealth()));
            player.setDeltaMovement(0, 0, 0);
            return;
        }
        if (!player.getAbilities().flying && player.getAbilities().mayfly) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        player.setDeltaMovement(0, 0.42, 0);
    }

    public void tick(Minecraft mc) {
        escapeFromHarm(mc);
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
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            StackMerge.containerOpen();
        }
        if (puttingAway) {
            tickPutAway(mc);
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
        BuildLog.line("SNAP", String.format(java.util.Locale.ROOT, "at %.1f %.1f %.1f | %s | placed %d in 5 s (%d total) | %s | mover %s | printer busy %d%% (%s) | interval %d | free slots %d | fps %d",
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
            if (safety.hurtByPlant()) {
                // Hurt by a cactus while building: up and out of it right away (building goes on from there)
                escapeTicks = Math.max(escapeTicks, 20);
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
        RunLog.tick(mc);
        // Nothing placed for a moment: the head turns back to where the camera looks, smoothly
        if (PrinterBridge.isIdle() && clientTicks - lastQueuedTick > 8 && !breaker.isBusy() && !fluids.isBusy()
                && !rails.isBusy() && !entities.isBusy() && !airPlace.isActive()) {
            PrinterBridge.easeLookBack(mc.player);
        }
        // The pause between shopping rounds runs every tick (it used to stand still while nothing was missing, and
        // a new layer then waited out the rest: 28 s in a log); a new layer may shop at once
        if (buyCooldown > 0) {
            buyCooldown--;
            // Nothing placed for a few seconds and an item of the layer is used up: get it now, not when the pause
            // between shopping rounds is over (at most every 10 s)
            if (buyCooldown > 0 && layers.stalledFor(20 * 3) && clientTicks - stallShopAt > 20 * 10 && layerItemOut(mc)) {
                stallShopAt = clientTicks;
                buyCooldown = 0;
            }
        }
        if (layers.getLayer() != shoppingLayer) {
            shoppingLayer = layers.getLayer();
            buyCooldown = 0;
        }

        speed.tick(mc);
        ServerWatch.tick(mc);
        logConflicts();

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
            didntDeliver(buyer.notBought());
            // Bought, and none of it failed: buyable again whenever needed (a purchase that stopped halfway, out
            // of money, isn't tried again right away)
            for (net.minecraft.world.item.Item item : buyer.bought().keySet()) {
                if (!buyer.notBought().contains(item)) {
                    couldntBuy.remove(item);
                    buyFailures.remove(item);
                }
            }
            if (!buyer.notBought().isEmpty()) {
                // Bought part of the list: the rest (other items, or the same from what it's made of) right away
                tripGoesOn = true;
            }
            buyCooldown = 40;
            if (stock.isActive()) {
                // Filling the vaults: on to putting it away at once
                buyer.notBought().forEach(stock::notBought);
                tripGoesOn = true;
                buyCooldown = 0;
            }
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
        } else if (crafter.needsRoom()) {
            // The inventory was too full for what was crafted: room first (two stacks into storage), then it's
            // crafted again on the next round; not counted as "can't be crafted"
            craftingFor = java.util.Set.of();
            makeRoomForCrafting(mc);
        } else if (!craftingFor.isEmpty()) {
            for (net.minecraft.world.item.Item item : craftingFor) {
                boolean got = Tools.isMarker(item) ? Tools.has(item, mc.player.getInventory()) : mc.player.getInventory().countItem(item) > 0;
                if (shoppingList.containsKey(item) && !got) {
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
        // Dirt the printer left where farmland goes: tilled here (see Tiller), between the printer's clicks
        if (!breaker.isBusy() && !airPlace.isActive() && !rails.isBusy() && !fluids.isBusy() && (tiller.isBusy() || PrinterBridge.isIdle())) {
            tiller.tick(mc);
        }
        if (addonPlaces && !breaker.isBusy() && !airPlace.isActive() && !fluids.isBusy() && !tiller.isBusy() && (rails.isBusy() || PrinterBridge.isIdle())) {
            rails.tick(mc);
        }
        if (addonPlaces && !breaker.isBusy() && !airPlace.isActive() && !fluids.isBusy() && !tiller.isBusy() && !rails.isBusy()
                && (entities.isBusy() || PrinterBridge.isIdle())) {
            entities.tick(mc);
        }
        if (!breaker.isBusy() && !airPlace.isActive() && !fluids.isBusy() && !tiller.isBusy() && !rails.isBusy() && !entities.isBusy()
                && PrinterBridge.isIdle()) {
            hotbar.tick(mc, layers.layerNeeds(), !mover.isTravelling(mc.player) && mc.player.getDeltaMovement().lengthSqr() < 0.0004);
        }

        long layersStarted = Perf.start();
        boolean layersGoOn = !layers.isActive() || layers.tick(mc);
        Perf.end("layer check", layersStarted);
        if (!layersGoOn) {
            stopNote = "finished";
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

        watchdog(mc);
        long moverStarted = Perf.start();
        mover.tick(mc);
        Perf.end("movement", moverStarted);
        long statusStarted = Perf.start();
        updateStatus(mc);
        Perf.end("status and needs", statusStarted);
    }
}
