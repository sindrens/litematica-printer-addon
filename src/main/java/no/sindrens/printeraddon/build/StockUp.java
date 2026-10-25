package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fills the vaults from the shop a whole inventory at a time, so the trips after it only open a vault: each purchase
 * is one item, as much of it as the free slots take, and all of it goes into the vaults; the trip then takes the
 * inventory's share from the vaults as usual. Never more than the build still needs, nor than the vaults have room
 * for. On the player's server a purchase took about 7 s however much it was, and the inventory held about 1300
 * items of a cactus farm's string and sand: a trip to the shop every 2 minutes, 14 to 18 s each (11% of the build).
 */
public final class StockUp {
    /** Worth it only when the build still needs at least this many stacks more of an item than the trip takes. */
    private static final int MIN_STACKS = 4;
    /** Vault slots kept free (the leftovers at the end, a tool put away). */
    private static final int KEEP_FREE_IN_VAULTS = 4;

    /** Item -> how many more to put into the vaults this round. */
    private final Map<Item, Integer> toStock = new LinkedHashMap<>();
    /** Just bought: put into the vaults next. */
    private Item storing;
    private boolean active;
    private int purchases;
    private int stored;

    public boolean isActive() {
        return active;
    }

    public void reset() {
        toStock.clear();
        storing = null;
        active = false;
    }

    /**
     * Starts a round when it's worth it: storage to put things in with room, and the build needing many more stacks of
     * what the trip is about to buy than the inventory takes. {@code wants}: what the trip buys now.
     */
    public boolean start(Minecraft mc, Map<Item, Integer> wants, Map<Item, Integer> neededWhole, ChestFetcher fetcher) {
        if (!Configs.Shop.STOCK_UP.getBooleanValue() || !Configs.Shop.AUTO_BUY.getBooleanValue() || mc.player == null
                || !ChestFetcher.hasStorage(mc) || fetcher.storageFull()) {
            return false;
        }
        int vaultSlots = fetcher.knownFreeSlots() - KEEP_FREE_IN_VAULTS;
        if (vaultSlots < MIN_STACKS) {
            return false;
        }
        Inventory inventory = mc.player.getInventory();
        // How many more stacks of each the build needs than the inventory and the vaults hold, besides this trip's
        Map<Item, Integer> more = new LinkedHashMap<>();
        long total = 0;
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
                total += stacks;
            }
        }
        if (more.isEmpty()) {
            return false;
        }
        // The vault room shared by what each still needs
        toStock.clear();
        for (Map.Entry<Item, Integer> entry : more.entrySet()) {
            int share = (int) Math.min(entry.getValue(), Math.max(1, vaultSlots * (long) entry.getValue() / total));
            toStock.put(entry.getKey(), share * entry.getKey().getDefaultMaxStackSize());
        }
        active = true;
        purchases = 0;
        stored = 0;
        BuildLog.line("STOCK", "filling the vaults (" + vaultSlots + " slots free): " + toStock + " | the build still needs "
                + more + " stacks more");
        Chat.quiet("Buying in bulk into your vaults: " + describe(toStock) + ". The next trips take from there.");
        return true;
    }

    /**
     * The next step of the round: put away what was just bought, or buy the next inventory of an item. False when the
     * round is over (all in, the vaults full, or nothing more could be bought).
     */
    public boolean step(Minecraft mc, AutoBuild build) {
        if (!active || mc.player == null) {
            return false;
        }
        Inventory inventory = mc.player.getInventory();
        if (storing != null) {
            Item item = storing;
            storing = null;
            if (build.fetcher.startStore(mc, Map.of(item, 0))) {
                stored++;
                return true;
            }
            BuildLog.line("STOCK", "nowhere to put the " + name(item) + ": stopping");
            return finish();
        }
        if (build.fetcher.storageFull()) {
            BuildLog.line("STOCK", "the vaults are full");
            return finish();
        }
        int free = InventoryCleaner.freeSlots(inventory) - InventoryBudget.KEEP_FREE;
        for (Map.Entry<Item, Integer> entry : toStock.entrySet()) {
            Item item = entry.getKey();
            int stack = item.getDefaultMaxStackSize();
            int stacks = Math.min(entry.getValue() / stack, free);
            if (stacks < 1) {
                continue;
            }
            Map<Item, Integer> buy = Map.of(item, stacks * stack);
            if (!build.startPurchase(mc, buy)) {
                entry.setValue(0);
                continue;
            }
            entry.setValue(entry.getValue() - stacks * stack);
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
            BuildLog.line("STOCK", "done: " + purchases + " purchases, " + stored + " times into the vaults");
        }
        reset();
        return false;
    }

    private static String describe(Map<Item, Integer> items) {
        StringBuilder sb = new StringBuilder();
        items.forEach((item, count) -> sb.append(sb.length() == 0 ? "" : ", ").append(count).append(' ').append(name(item)));
        return sb.toString();
    }

    private static String name(Item item) {
        return new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
    }
}
