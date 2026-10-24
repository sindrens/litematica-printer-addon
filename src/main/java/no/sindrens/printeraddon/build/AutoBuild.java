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

    public void printerTried(net.minecraft.core.BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            recentlyTried.put(pos.immutable(), clientTicks);
            triedDuringBuild.add(pos.immutable());
            if (BuildLog.isOpen()) {
                fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
                BuildLog.line("TRY", String.format("%s: is %s, wants %s | in hand %s | %.2f from the eye | player at %.1f %.1f %.1f",
                        pos.toShortString(), shortState(mc.level.getBlockState(pos)),
                        schematic != null ? shortState(schematic.getBlockState(pos)) : "?",
                        mc.player.getMainHandItem().isEmpty() ? "nothing" : mc.player.getMainHandItem().getCount() + " "
                                + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).getPath(),
                        Math.sqrt(mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))),
                        mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            }
            speed.attempted();
            // The same click again and again with nothing changing (the server refuses it): leave that block for a
            // while instead of standing there; the player moves on and it's tried again later, from elsewhere
            net.minecraft.world.level.block.state.BlockState now = mc.level.getBlockState(pos);
            Tries tries = triesAt.get(pos);
            if (tries == null || tries.state() != now || clientTicks - tries.since() > 20 * 30) {
                triesAt.put(pos.immutable(), new Tries(now, 1, clientTicks));
            } else if (tries.count() + 1 >= MAX_TRIES) {
                triesAt.remove(pos);
                givenUp.put(pos.immutable(), clientTicks + GIVE_UP_TICKS);
                Mover.missedAt(Math.sqrt(mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))));
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

    private static final int MAX_TRIES = 4;
    private static final int GIVE_UP_TICKS = 20 * 30;
    private final java.util.Map<net.minecraft.core.BlockPos, Tries> triesAt = new java.util.HashMap<>();
    private final java.util.Map<net.minecraft.core.BlockPos, Long> givenUp = new java.util.HashMap<>();

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
    private final java.util.Set<net.minecraft.world.item.Item> couldntBuy = new java.util.HashSet<>();
    private boolean buying;
    private int buyCooldown;
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
                || no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * What the printer gets to see: during auto build only blocks that are ready, full blocks first, plus deferred
     * blocks within reach that have become ready. During air place only the one block it's meant for.
     */
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
        int unfinished = 0;
        int held = 0;
        for (net.minecraft.core.BlockPos pos : all) {
            if (schematic.getBlockState(pos).isAir() || BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.OK) {
                continue;
            }
            unfinished++;
            if (recentlyTried.containsKey(pos) || givenUp(pos)) {
                held++;
                continue;
            }
            forPrinter.add(pos);
        }
        lastUnfinishedCount = unfinished;
        lastHeldCount = held;
        lastOfferedTick = clientTicks;
        lastRawCount = all.size();
        java.util.List<net.minecraft.core.BlockPos> ordered = PlacementOrder.order(mc.level, schematic, forPrinter, mc.player.position(),
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
        if (statusTicks % 5 == 1) {
            updateMissingWhole(mc, schematic);
        }
        if (statusTicks % 40 == 0 && Configs.Generic.STATUS_FILE.getBooleanValue()) {
            StatusFile.write(mc, layers.status(), next, nextBlock, doing(), shoppingList, missingWhole);
        }
    }

    /**
     * What the whole schematic still needs, per Y level. Read again in small pieces (30 columns of one level per
     * call), so it never makes a lag spike.
     */
    private final java.util.Map<Integer, java.util.Map<net.minecraft.world.item.Item, Integer>> wholeByY = new java.util.HashMap<>();
    private final java.util.Map<net.minecraft.world.item.Item, Integer> wholePartial = new java.util.HashMap<>();
    private int wholeNextY = Integer.MIN_VALUE;
    private int wholeNextX = Integer.MIN_VALUE;
    private static final int WHOLE_COLUMNS_PER_STEP = 30;

    private void updateMissingWhole(Minecraft mc, fi.dy.masa.litematica.world.WorldSchematic schematic) {
        java.util.List<SchematicHelper.Area> areas = SchematicHelper.areas();
        int minY = areas.stream().mapToInt(SchematicHelper.Area::minY).min().orElse(0);
        int maxY = areas.stream().mapToInt(SchematicHelper.Area::maxY).max().orElse(-1);
        int minX = areas.stream().mapToInt(SchematicHelper.Area::minX).min().orElse(0);
        int maxX = areas.stream().mapToInt(SchematicHelper.Area::maxX).max().orElse(-1);
        if (wholeByY.isEmpty()) {
            // The first time all at once
            for (int y = minY; y <= maxY; y++) {
                wholeByY.put(y, needsAt(mc, schematic, areas, y, minX, maxX));
            }
            wholeNextY = minY;
            wholeNextX = minX;
            wholePartial.clear();
        } else {
            if (wholeNextY < minY || wholeNextY > maxY || wholeNextX < minX) {
                wholeNextY = minY;
                wholeNextX = minX;
                wholePartial.clear();
            }
            int toX = Math.min(maxX, wholeNextX + WHOLE_COLUMNS_PER_STEP - 1);
            needsAt(mc, schematic, areas, wholeNextY, wholeNextX, toX).forEach((item, count) -> wholePartial.merge(item, count, Integer::sum));
            wholeNextX = toX + 1;
            if (wholeNextX <= maxX) {
                return;
            }
            // This level is read through
            wholeByY.put(wholeNextY, new java.util.HashMap<>(wholePartial));
            wholePartial.clear();
            wholeNextX = minX;
            wholeNextY++;
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

    private static java.util.Map<net.minecraft.world.item.Item, Integer> needsAt(Minecraft mc,
            fi.dy.masa.litematica.world.WorldSchematic schematic, java.util.List<SchematicHelper.Area> areas, int y,
            int fromX, int toX) {
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
                && !mc.player.getInventory().contains(net.minecraft.tags.ItemTags.HOES)) {
            needs.put(net.minecraft.world.item.Items.DIAMOND_HOE, 1);
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
        if (!running || result != BlockCheck.Result.WRONG_BLOCK || schematicBlocks.isEmpty()) {
            return false;
        }
        if (emptyDuringBuild.contains(pos) || triedDuringBuild.contains(pos) || schematicBlocks.contains(current.getBlock())) {
            return false;
        }
        if (conflicts.add(pos.immutable())) {
            fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
            BuildLog.line("CONFLICT", pos.toShortString() + ": the world has " + current + ", the schematic wants "
                    + (schematic != null ? schematic.getBlockState(pos) : "?") + " (left alone, not counted)");
        }
        return true;
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

    /**
     * Buys what the current layer still needs from /shop (as much as fits). When the layer can't go on because of
     * items the shop doesn't have (or couldn't deliver), auto build pauses and says what to bring.
     */
    private boolean buyMissing(Minecraft mc) {
        if (shoppingList.isEmpty() || mc.player.getAbilities().instabuild) {
            return false;
        }
        if (buyCooldown > 0) {
            buyCooldown--;
            return false;
        }
        // Buy when an item is (nearly) used up
        net.minecraft.world.entity.player.Inventory inventory = mc.player.getInventory();
        java.util.Map<net.minecraft.world.item.Item, Integer> list = new java.util.LinkedHashMap<>();
        boolean outOfSomething = false;
        for (var entry : shoppingList.entrySet()) {
            net.minecraft.world.item.Item item = entry.getKey();
            int have = inventory.countItem(item);
            int need = have + entry.getValue();
            if (have == 0) {
                outOfSomething |= couldntBuy.contains(item) || !Configs.Shop.AUTO_BUY.getBooleanValue();
            }
            // Only once it's (nearly) used up: this many or fewer left
            if (!couldntBuy.contains(item) && have <= Configs.Shop.BUY_WHEN_LEFT.getIntegerValue()
                    && no.sindrens.printeraddon.shop.ShopBuyer.room(mc, item) > 0) {
                list.put(item, entry.getValue());
            }
        }
        // Never more than fits: the free slots are shared between the items, a few always stay free
        java.util.Map<net.minecraft.world.item.Item, Integer> fitted = InventoryBudget.fit(inventory, list);
        list.clear();
        list.putAll(fitted);

        if (list.isEmpty() || !Configs.Shop.AUTO_BUY.getBooleanValue()) {
            // Pause only when something is used up that can't be bought, and the layer can't go on
            if (outOfSomething && layers.stalledFor(20 * 20)) {
                StringBuilder sb = new StringBuilder();
                shoppingList.forEach((item, count) -> sb.append(count).append(' ')
                        .append(new net.minecraft.world.item.ItemStack(item).getHoverName().getString()).append(", "));
                stop(mc, "missing " + sb.substring(0, sb.length() - 2)
                        + ". Put them in your inventory and start auto build again (J+B menu)");
                return true;
            }
            return false;
        }

        buyCooldown = 20 * 30;

        // First what's in storage (vaults, linked chests); the next round buys what wasn't there. For seeds also
        // pumpkins: the player may have put pumpkins in storage to craft seeds from
        java.util.Map<net.minecraft.world.item.Item, Integer> fromStorage = new java.util.LinkedHashMap<>(list);
        Integer seeds = list.get(net.minecraft.world.item.Items.PUMPKIN_SEEDS);
        if (seeds != null) {
            fromStorage.merge(net.minecraft.world.item.Items.PUMPKIN, (seeds + 3) / 4, Integer::sum);
        }
        if (fetcher.start(mc, fromStorage)) {
            mover.stop(mc);
            buyCooldown = 0;
            return true;
        }

        // Rails, powered rails and seeds are crafted: buy what they're made of, then craft them
        java.util.Map<net.minecraft.world.item.Item, Integer> toCraft = new java.util.LinkedHashMap<>();
        list.entrySet().removeIf(entry -> {
            if (CraftPlan.crafted(entry.getKey())) {
                toCraft.put(entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });
        if (!toCraft.isEmpty()) {
            CraftPlan plan = CraftPlan.of(mc, toCraft, item -> no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, item),
                    Crafter.tableNearby(mc));
            plan.buy.forEach((item, count) -> list.merge(item, count, Integer::sum));
            craftAfterBuying = plan.craft;
            craftingFor = toCraft.keySet();
            PrinterAddon.debug("To craft {}: buy {}, then craft {}", toCraft, plan.buy, plan.craft);
        }
        if (list.isEmpty()) {
            for (CraftPlan.Step step : craftAfterBuying) {
                crafter.craft(step.item(), step.count());
            }
            craftAfterBuying = java.util.List.of();
            return crafter.isBusy();
        }
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.start(mc, list)) {
            buying = true;
            mover.stop(mc);
            return true;
        }
        couldntBuy.addAll(no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.notSold());
        couldntBuy.addAll(no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.notBought());
        // Nothing to buy after all (e.g. no shop scan): still craft what can be crafted from the inventory
        for (CraftPlan.Step step : craftAfterBuying) {
            crafter.craft(step.item(), step.count());
        }
        craftAfterBuying = java.util.List.of();
        return false;
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
        shoppingList.clear();
        missingWhole.clear();
        neededWhole.clear();
        wholeByY.clear();
        wholePartial.clear();
        couldntBuy.clear();
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

        // Read the whole schematic once now: what it needs, and which kinds of blocks it has (to tell the player's
        // own buildings from wrong blocks before anything is broken)
        updateMissingWhole(mc, SchematicHelper.world());
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
            BuildLog.line("END", (reason != null ? "stopped: " + reason : "stopped") + " | " + stats.total());
            logIgnored(mc);
            BuildLog.line("PERF", Perf.report());
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
    public void stopForDisconnect(Minecraft mc) {
        if (!running && manualTask == ManualTask.NONE) {
            return;
        }
        resumeLater = true;
        stopEverything(mc);
        resumeLater = false;
    }

    private boolean resumeLater;

    public void toggle(Minecraft mc) {
        if (running) {
            stop(mc, null);
        } else {
            start(mc);
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

    public void tick(Minecraft mc) {
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
                start(mc);
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
            couldntBuy.addAll(buyer.notSold());
            couldntBuy.addAll(buyer.notBought());
            couldntBuy.removeAll(buyer.bought().keySet());
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
                    couldntBuy.add(item);
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
