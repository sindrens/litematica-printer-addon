package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buys in bulk into the vaults when a trip has to buy something the vaults are out of: one purchase per item, as much
 * of it as the inventory takes (on the player's server a purchase took 3 to 7 s however much it was), all of it into
 * the vaults; the trip then takes the inventory's share from the vaults as usual, and the next trips only open a vault.
 * With little room in the inventory, the layer's items go into the vaults first (the trip takes them back after), so a
 * purchase is a whole inventory and not 3 stacks. Never more than the build still needs, and a part of the vaults is
 * always left free (leftovers at a layer change didn't fit once the vaults had been filled to the last slot).
 * Before, the vaults were filled with several purchases of each item at the start: 65 s before the build got going.
 */
public final class StockUp {
    /** Worth it only when the build still needs at least this many stacks more of an item than the trip takes. */
    private static final int MIN_STACKS = 4;
    /** Vault slots always left free: this many, or this share of them if that's more. */
    private static final int KEEP_FREE_IN_VAULTS = 9;
    private static final double KEEP_FREE_SHARE = 0.15;
    /** Fewer free inventory slots than this: the layer's items go into the vaults before buying... */
    private static final int ROOM_FOR_BULK = 18;
    /**
     * ...but only for a big purchase, this many stacks or more: putting 27 stacks away and taking them back for 8
     * stacks of string cost 11 s more than buying the trip's 64 (a test with the fake shop).
     */
    private static final int CLEAR_WORTH = 16;

    /** Item -> how many to buy in its one purchase this round. */
    private final Map<Item, Integer> toStock = new LinkedHashMap<>();
    /** The layer's items to put into the vaults before the first purchase (empty: none). */
    private final Map<Item, Integer> clearFirst = new LinkedHashMap<>();
    /** Just bought: put into the vaults next. */
    private Item storing;
    /**
     * What the trip wants each of the layer's items to be at in the inventory when it's done: the share of what was
     * just bought stays in the inventory, and the last visit to the vaults takes the rest of the trip's items (it took
     * them out again in a visit of its own, after putting everything away).
     */
    private final Map<Item, Integer> target = new LinkedHashMap<>();
    private boolean active;
    private int purchases;
    private int stored;
    private final Map<Item, Integer> bought = new LinkedHashMap<>();
    private long startedAt;
    private int vaultFreeBefore;
    private int inventoryFreeBefore;
    private String trigger = "";

    public boolean isActive() {
        return active;
    }

    public void reset() {
        toStock.clear();
        clearFirst.clear();
        bought.clear();
        target.clear();
        storing = null;
        active = false;
    }

    /** Vault slots to leave free with this many in all. */
    static int keepFree(int total) {
        return Math.max(KEEP_FREE_IN_VAULTS, (int) Math.ceil(total * KEEP_FREE_SHARE));
    }

    /**
     * Starts a round when it's worth it: storage with room, and the build needing many more stacks of what the trip is
     * about to buy than it takes. {@code wants}: what the trip buys now (what the vaults don't have); {@code tripWants}:
     * all the trip gets (also from the vaults); {@code layerItems}: the items the layer is built of (put away first when
     * the inventory has little room).
     */
    public boolean start(Minecraft mc, Map<Item, Integer> wants, Map<Item, Integer> tripWants, Map<Item, Integer> neededWhole,
                         ChestFetcher fetcher, Collection<Item> layerItems) {
        if (!Configs.Shop.STOCK_UP.getBooleanValue() || !Configs.Shop.AUTO_BUY.getBooleanValue() || mc.player == null
                || !ChestFetcher.hasStorage(mc)) {
            return false;
        }
        int[] level = ChestFetcher.fillLevel();
        int total = level == null ? 0 : level[1];
        int vaultFree = fetcher.knownFreeSlots();
        int keep = keepFree(total);
        int room = vaultFree - keep;
        Inventory inventory = mc.player.getInventory();
        // The layer's items in the inventory, if they have to go into the vaults first to make room for a purchase
        Map<Item, Integer> clear = new LinkedHashMap<>();
        int clearStacks = 0;
        int invFree = InventoryCleaner.freeSlots(inventory);
        if (invFree < ROOM_FOR_BULK) {
            for (Item item : layerItems) {
                if (item.getDefaultMaxStackSize() <= 1 || Tools.isMarker(item) || InventoryCleaner.isBucket(item)) {
                    continue;
                }
                int stacks = stacksOf(inventory, item);
                if (stacks > 0) {
                    clear.put(item, 0);
                    clearStacks += stacks;
                }
            }
        }
        if (room - clearStacks < MIN_STACKS) {
            if (room >= MIN_STACKS && !clear.isEmpty()) {
                // No room for the layer's items as well: buy with the room the inventory has
                clear.clear();
                clearStacks = 0;
            } else {
                BuildLog.line("STOCK", "skip reason=" + (fetcher.storageFull() ? "vaults-full" : "vault-room") + " vaultFree="
                        + vaultFree + " keepFree=" + keep + " total=" + total);
                return false;
            }
        }
        // How many more stacks of each the build needs than the inventory and the vaults hold, besides this trip's
        Map<Item, Integer> more = new LinkedHashMap<>();
        long totalStacks = 0;
        for (Map.Entry<Item, Integer> entry : wants.entrySet()) {
            Item item = entry.getKey();
            int stack = item.getDefaultMaxStackSize();
            if (stack <= 1 || Tools.isMarker(item) || !no.sindrens.printeraddon.shop.ShopBuyer.buysWholeStacks(mc, item)
                    || !no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, item)) {
                continue;
            }
            int left = neededWhole.getOrDefault(item, 0) - inventory.countItem(item) - fetcher.knownCount(item) - entry.getValue();
            int stacks = left / stack;
            if (stacks >= MIN_STACKS) {
                more.put(item, stacks);
                totalStacks += stacks;
            }
        }
        if (more.isEmpty()) {
            StringBuilder why = new StringBuilder();
            for (Map.Entry<Item, Integer> entry : wants.entrySet()) {
                Item item = entry.getKey();
                why.append(why.length() == 0 ? "" : ", ").append(name(item)).append(": build needs ")
                        .append(neededWhole.getOrDefault(item, 0)).append(", have ").append(inventory.countItem(item))
                        .append(", in storage ").append(fetcher.knownCount(item)).append(", this trip ").append(entry.getValue())
                        .append(no.sindrens.printeraddon.shop.ShopBuyer.buysWholeStacks(mc, item) ? "" : ", not by the stack");
            }
            BuildLog.line("STOCK", "skip reason=not-worth-it (" + why + ")");
            return false;
        }
        // The vault room shared by what each still needs; one purchase each
        int vaultStacks = room - clearStacks;
        toStock.clear();
        int planned = 0;
        int biggest = 0;
        for (Map.Entry<Item, Integer> entry : more.entrySet()) {
            int share = (int) Math.min(entry.getValue(), Math.max(1, vaultStacks * (long) entry.getValue() / totalStacks));
            toStock.put(entry.getKey(), share * entry.getKey().getDefaultMaxStackSize());
            planned += share;
            biggest = Math.max(biggest, share);
        }
        // Making room is only worth it for a big purchase; else what fits now, or nothing this trip
        int roomNow = invFree - InventoryBudget.KEEP_FREE;
        if (!clear.isEmpty() && (biggest < CLEAR_WORTH || roomNow >= biggest)) {
            clear.clear();
        }
        if (clear.isEmpty() && roomNow < MIN_STACKS) {
            BuildLog.line("STOCK", "skip reason=inventory-room invFree=" + invFree + " plannedStacks=" + planned
                    + " (making room is only worth it for " + CLEAR_WORTH + " stacks or more of one item)");
            toStock.clear();
            return false;
        }
        clearFirst.clear();
        clearFirst.putAll(clear);
        bought.clear();
        target.clear();
        for (Item item : layerItems) {
            target.put(item, inventory.countItem(item) + tripWants.getOrDefault(item, 0));
        }
        tripWants.forEach((item, count) -> target.putIfAbsent(item, inventory.countItem(item) + count));
        active = true;
        purchases = 0;
        stored = 0;
        startedAt = AutoBuild.clientTicks;
        vaultFreeBefore = vaultFree;
        inventoryFreeBefore = invFree;
        trigger = "buying " + describe(wants);
        BuildLog.line("STOCK", "start vaultFree=" + vaultFree + " keepFree=" + keep + " invFree=" + invFree
                + (clear.isEmpty() ? "" : " clearFirst=" + clearStacks + "stacks") + " plan={" + describe(toStock) + "} buildNeedsStacks=" + more);
        Chat.quiet("Buying in bulk into your vaults: " + describe(toStock) + ". The next trips take from there.");
        return true;
    }

    /**
     * The next step of the round: make room, put away what was just bought, or buy the next item. False when the round
     * is over (all in, the vaults full, or nothing more could be bought).
     */
    public boolean step(Minecraft mc, AutoBuild build) {
        if (!active || mc.player == null) {
            return false;
        }
        Inventory inventory = mc.player.getInventory();
        if (!clearFirst.isEmpty()) {
            Map<Item, Integer> clear = new LinkedHashMap<>(clearFirst);
            clearFirst.clear();
            if (build.fetcher.startStore(mc, clear)) {
                stored++;
                BuildLog.line("STOCK", "step=make-room items=" + clear.keySet().stream().map(StockUp::name).toList()
                        + " invFree=" + InventoryCleaner.freeSlots(inventory));
                return true;
            }
        }
        if (storing != null) {
            Item item = storing;
            storing = null;
            // The trip's share of it stays; the last purchase's extra goes in with the trip's other items coming out
            // A part-used stack among the kept ones holds less than a stack: one more stays (64 string were short
            // after a round, and a vault visit of their own took them out again)
            int keepStacks = stacksFor(item, target.getOrDefault(item, 0));
            if (keepStacks > 0 && inventory.countItem(item) % Math.max(1, item.getDefaultMaxStackSize()) != 0) {
                keepStacks++;
            }
            Map<Item, Integer> keep = Map.of(item, keepStacks);
            boolean last = toStock.values().stream().noneMatch(count -> count > 0);
            Map<Item, Integer> take = new LinkedHashMap<>();
            if (last) {
                target.forEach((other, want) -> {
                    int missing = want - inventory.countItem(other);
                    if (!bought.containsKey(other) && missing > 0) {
                        take.put(other, missing);
                    }
                });
            }
            if (take.isEmpty() ? build.fetcher.startStore(mc, keep) : build.fetcher.startSwap(mc, keep, take)) {
                stored++;
                BuildLog.line("STOCK", "step=put-away item=" + name(item) + " keepStacks=" + keep.get(item)
                        + (take.isEmpty() ? "" : " andTake={" + describe(take) + "}"));
                return true;
            }
            BuildLog.line("STOCK", "stop reason=nowhere-to-put item=" + name(item));
            return finish();
        }
        if (build.fetcher.storageFull()) {
            BuildLog.line("STOCK", "stop reason=vaults-full");
            return finish();
        }
        int free = InventoryCleaner.freeSlots(inventory) - InventoryBudget.KEEP_FREE;
        for (Map.Entry<Item, Integer> entry : toStock.entrySet()) {
            Item item = entry.getKey();
            int stack = item.getDefaultMaxStackSize();
            int stacks = Math.min(entry.getValue() / stack, free);
            if (entry.getValue() <= 0) {
                continue;
            }
            // One purchase per item: a second one of 3 stacks costs as long as the first of 20
            entry.setValue(0);
            if (stacks < MIN_STACKS) {
                BuildLog.line("STOCK", "skip item=" + name(item) + " reason=inventory-room freeSlots=" + free);
                continue;
            }
            Map<Item, Integer> buy = Map.of(item, stacks * stack);
            if (!build.startPurchase(mc, buy)) {
                continue;
            }
            bought.merge(item, stacks * stack, Integer::sum);
            storing = item;
            purchases++;
            return true;
        }
        return finish();
    }

    /** The purchase of this item stopped short (no money, not sold): no more of it this round. */
    public void notBought(Item item) {
        if (toStock.containsKey(item)) {
            toStock.put(item, 0);
        }
    }

    private boolean finish() {
        if (active) {
            Minecraft mc = Minecraft.getInstance();
            BuildLog.line("STOCK-END", "trigger=\"" + trigger + "\" time=" + String.format(java.util.Locale.ROOT, "%.1fs",
                    (AutoBuild.clientTicks - startedAt) / 20.0) + " purchases=" + purchases + " vaultVisits=" + stored
                    + " bought={" + describe(bought) + "} vaultFreeBefore=" + vaultFreeBefore + " vaultFreeAfter="
                    + AutoBuild.INSTANCE.fetcher.knownFreeSlots() + " invFreeBefore=" + inventoryFreeBefore + " invFreeAfter="
                    + (mc.player == null ? -1 : InventoryCleaner.freeSlots(mc.player.getInventory())));
        }
        reset();
        return false;
    }

    /** Stacks that this many of the item take. */
    private static int stacksFor(Item item, int count) {
        int stack = Math.max(1, item.getDefaultMaxStackSize());
        return Math.max(0, (count + stack - 1) / stack);
    }

    private static int stacksOf(Inventory inventory, Item item) {
        int stacks = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(item)) {
                stacks++;
            }
        }
        return stacks;
    }

    private static String describe(Map<Item, Integer> items) {
        StringBuilder sb = new StringBuilder();
        items.forEach((item, count) -> sb.append(sb.length() == 0 ? "" : ", ").append(count).append(' ').append(name(item)));
        return sb.toString();
    }

    private static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }
}
