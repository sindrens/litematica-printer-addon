package no.sindrens.printeraddon.build;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of each item to get (from chests or the shop) so the inventory never fills up with one of them: the free
 * slots are shared between every item the layer is short of, and a few slots always stay empty (for crafting
 * results, picked up items, a tool from a chest). Tools, food and everything else already there keep their slots.
 */
public final class InventoryBudget {
    /** Slots that are never filled by buying or fetching. */
    public static final int KEEP_FREE = 3;

    private InventoryBudget() {
    }

    /** The list (item -> count wanted) cut down to what fits. Items that get nothing are left out. */
    public static Map<Item, Integer> fit(Inventory inventory, Map<Item, Integer> wanted) {
        Map<Item, Integer> fitted = new LinkedHashMap<>();
        int free = Math.max(0, InventoryCleaner.freeSlots(inventory) - KEEP_FREE);
        // Topping up stacks that are already there costs no slot
        Map<Item, Integer> needSlots = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
            Item item = entry.getKey();
            int topUp = Math.min(entry.getValue(), topUpRoom(inventory, item));
            int rest = entry.getValue() - topUp;
            if (topUp > 0) {
                fitted.put(item, topUp);
            }
            if (rest > 0) {
                needSlots.put(item, rest);
            }
        }
        int reserve = InventoryCleaner.freeSlots(inventory) - 1 - Math.max(0, free);
        if (free > 0 && !needSlots.isEmpty()) {
            // Fair share: every short item gets the same number of slots, slots an item doesn't need go to the others
            int share = Math.max(1, free / needSlots.size());
            for (Map.Entry<Item, Integer> entry : needSlots.entrySet()) {
                if (free <= 0) {
                    break;
                }
                int max = entry.getKey().getDefaultMaxStackSize();
                int slots = Math.min(Math.min(share, free), (entry.getValue() + max - 1) / max);
                free -= slots;
                fitted.merge(entry.getKey(), Math.min(entry.getValue(), slots * max), Integer::sum);
            }
        }
        // An item that's not in the inventory at all may use the kept free slots (one stack, one slot always stays)
        for (Map.Entry<Item, Integer> entry : needSlots.entrySet()) {
            if (reserve > 0 && !fitted.containsKey(entry.getKey()) && inventory.countItem(entry.getKey()) == 0) {
                reserve--;
                fitted.put(entry.getKey(), Math.min(entry.getValue(), entry.getKey().getDefaultMaxStackSize()));
            }
        }
        return fitted;
    }

    private static int topUpRoom(Inventory inventory, Item item) {
        int room = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && stack.getComponents().equals(new ItemStack(item).getComponents())) {
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }
}
