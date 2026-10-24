package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the items the current layer needs most on the hotbar (slots 1-7; 8 and 9 stay free for tools and food).
 * Then the printer only changes the selected slot instead of swapping items in from the inventory, which is faster
 * and can't race with the server.
 */
public class HotbarPrep {
    private static final int SLOTS = 7;
    private static final int CHECK_EVERY = 10;

    private long tick;

    /** Moves at most one item onto the hotbar. Returns true if it did. */
    public boolean tick(Minecraft mc, Map<Item, Integer> layerNeeds) {
        LocalPlayer player = mc.player;
        // Also with the chat or the pause menu open (alt-tabbed): the printer goes on placing then, and an empty
        // hotbar stopped it. Not while a container or the inventory is open (the player may be moving items)
        if (player == null || mc.gameMode == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || layerNeeds.isEmpty() || ++tick % CHECK_EVERY != 0) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (topUp(mc, player, inventory, layerNeeds)) {
            return true;
        }
        if (mergeSmallStacks(mc, player, inventory)) {
            return true;
        }

        // The items this layer needs most, that are in the inventory at all
        List<Map.Entry<Item, Integer>> byNeed = new ArrayList<>(layerNeeds.entrySet());
        byNeed.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Item> wanted = new ArrayList<>(SLOTS);
        for (Map.Entry<Item, Integer> entry : byNeed) {
            if (entry.getValue() > 0 && inventory.countItem(entry.getKey()) > 0) {
                wanted.add(entry.getKey());
                if (wanted.size() == SLOTS) {
                    break;
                }
            }
        }
        Set<Item> wantedSet = new HashSet<>(wanted);

        for (Item item : wanted) {
            if (hotbarSlotOf(inventory, item) != -1) {
                continue;
            }
            int from = -1;
            for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
                if (inventory.getItem(slot).is(item)) {
                    from = slot;
                    break;
                }
            }
            int to = freeHotbarSlot(inventory, wantedSet);
            if (from == -1 || to == -1) {
                continue;
            }
            // Swap: inventory slot <-> hotbar slot (inventory menu slots 9-35 are the same numbers)
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, to, ContainerInput.SWAP, player);
            AutoBuild.INSTANCE.inventory.moved();
            PrinterAddon.trace("Hotbar: moved {} to slot {} (the layer needs {})", item, to + 1, layerNeeds.get(item));
            return true;
        }
        return false;
    }

    /** A stack on the hotbar with this many or fewer left is swapped for a fuller one before it runs out. */
    private static final int TOP_UP_AT = 16;

    /**
     * A stack of the layer's blocks on the hotbar running low: swapped with the fullest stack of the same item from
     * the inventory, before it runs out. Both slots keep their kind of item (only the counts change), so nothing has
     * to settle: no pause for the printer (a stack running out, then the refill, each paused it 4 or 5 ticks: 8 in
     * 100 of its turns in a log), no race with the server, and the hand doesn't run empty while there's more.
     */
    private static boolean topUp(Minecraft mc, LocalPlayer player, Inventory inventory, Map<Item, Integer> layerNeeds) {
        for (int hotbar = 0; hotbar < Inventory.getSelectionSize(); hotbar++) {
            ItemStack low = inventory.getItem(hotbar);
            if (low.isEmpty() || low.isDamageableItem() || low.getCount() > TOP_UP_AT || low.getMaxStackSize() <= TOP_UP_AT
                    || !layerNeeds.containsKey(low.getItem())) {
                continue;
            }
            int best = -1;
            for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.getCount() > low.getCount() && ItemStack.isSameItemSameComponents(stack, low)
                        && (best < 0 || stack.getCount() > inventory.getItem(best).getCount())) {
                    best = slot;
                }
            }
            if (best < 0) {
                continue;
            }
            int count = low.getCount();
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, best, hotbar, ContainerInput.SWAP, player);
            PrinterAddon.trace("Hotbar: {} in slot {} was down to {}: swapped with the {} in slot {}", low.getItem(),
                    hotbar + 1, count, inventory.getItem(hotbar).getCount(), best + 1);
            return true;
        }
        return false;
    }

    /**
     * Puts a small stack onto another stack of the same item (the refill before the last item leaves a single one
     * behind every stack). Only in the main inventory (never the hotbar, which the printer changes all the time),
     * at most every 10 seconds, and never again in this build if a merge didn't do what was expected (on a server
     * the client's view can be behind: then the clicks swap two different items back and forth).
     */
    private static long lastMerge;
    private static boolean mergeBroken;
    private static int mergeCheckSlot = -1;
    private static net.minecraft.world.item.Item mergeCheckItem;

    public static void resetMerging() {
        mergeBroken = false;
        mergeCheckSlot = -1;
        lastMerge = 0;
    }

    private boolean mergeSmallStacks(Minecraft mc, LocalPlayer player, Inventory inventory) {
        if (mergeCheckSlot >= 0) {
            // The slot merged onto must still hold the same item
            if (!inventory.getItem(mergeCheckSlot).is(mergeCheckItem)) {
                mergeBroken = true;
                PrinterAddon.debug("Merging stacks went wrong (slot {} now holds {}): not merging any more in this build",
                        mergeCheckSlot, inventory.getItem(mergeCheckSlot).getItem());
            }
            mergeCheckSlot = -1;
        }
        if (mergeBroken || AutoBuild.clientTicks - lastMerge < 200) {
            return false;
        }
        for (int a = Inventory.getSelectionSize(); a < Inventory.INVENTORY_SIZE; a++) {
            ItemStack small = inventory.getItem(a);
            if (small.isEmpty() || small.getCount() >= small.getMaxStackSize() || small.isDamageableItem()) {
                continue;
            }
            for (int b = Inventory.getSelectionSize(); b < Inventory.INVENTORY_SIZE; b++) {
                ItemStack other = inventory.getItem(b);
                if (b == a || other.getCount() >= other.getMaxStackSize() || other.getCount() < small.getCount()
                        || !ItemStack.isSameItemSameComponents(small, other)) {
                    continue;
                }
                int count = small.getCount();
                net.minecraft.world.item.Item item = small.getItem();
                int id = player.inventoryMenu.containerId;
                mc.gameMode.handleContainerInput(id, a, 0, ContainerInput.PICKUP, player);
                mc.gameMode.handleContainerInput(id, b, 0, ContainerInput.PICKUP, player);
                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    mc.gameMode.handleContainerInput(id, a, 0, ContainerInput.PICKUP, player);
                }
                AutoBuild.INSTANCE.inventory.moved();
                lastMerge = AutoBuild.clientTicks;
                mergeCheckSlot = b;
                mergeCheckItem = item;
                PrinterAddon.trace("Merged {} {} from slot {} onto slot {}", count, item, a, b);
                return true;
            }
        }
        return false;
    }

    private static int hotbarSlotOf(Inventory inventory, Item item) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * A hotbar slot (1-7) that's empty or holds a block this layer doesn't need much. Tools stay where they are:
     * swapping a tool away and back (the hoe on farmland layers) is a slot change right where the printer clicks.
     */
    private static int freeHotbarSlot(Inventory inventory, Set<Item> wanted) {
        int fallback = -1;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                return slot;
            }
            if (fallback == -1 && !wanted.contains(stack.getItem()) && !stack.isDamageableItem()) {
                fallback = slot;
            }
        }
        return fallback;
    }
}
