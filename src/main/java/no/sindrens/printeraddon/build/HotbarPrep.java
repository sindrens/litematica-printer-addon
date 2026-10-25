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
 * and can't race with the server. Small stacks are used up first and put together (see {@link StackMerge}): swapping
 * a nearly empty hotbar stack for a full one left a small stack behind every 50 blocks, until the inventory was full
 * of stacks of 1 to 8 and a purchase had no room.
 */
public class HotbarPrep {
    private static final int SLOTS = 7;
    private static final int CHECK_EVERY = 10;
    /** An item missing from the hotbar is put there this often (a used-up stack was swapped in by the printer itself meanwhile). */
    private static final int REFILL_EVERY = 2;

    private long tick;

    /** Moves at most one item. Returns true if it did. {@code still}: the player isn't flying or walking anywhere. */
    public boolean tick(Minecraft mc, Map<Item, Integer> layerNeeds, boolean still) {
        LocalPlayer player = mc.player;
        // Also with the chat or the pause menu open (alt-tabbed): the printer goes on placing then, and an empty
        // hotbar stopped it. Not while a container or the inventory is open (the player may be moving items)
        if (player == null || mc.gameMode == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || layerNeeds.isEmpty() || ++tick % REFILL_EVERY != 0) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (tick % CHECK_EVERY == 0) {
            StackMerge.check(mc, inventory);
            // Putting stacks together only while standing (clicks on the move are what anticheats look for)
            if (still && (StackMerge.ontoHotbar(mc, player, inventory, layerNeeds) || StackMerge.inInventory(mc, player, inventory))) {
                return true;
            }
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
            // The smallest stack first: small stacks get used up instead of piling up
            int from = -1;
            for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(item) && (from == -1 || stack.getCount() < inventory.getItem(from).getCount())) {
                    from = slot;
                }
            }
            int to = freeHotbarSlot(inventory, wantedSet, AutoBuild.INSTANCE.layers.tillingLeft() > 0);
            if (from == -1 || to == -1) {
                continue;
            }
            // Swap: inventory slot <-> hotbar slot (inventory menu slots 9-35 are the same numbers)
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, to, ContainerInput.SWAP, player);
            AutoBuild.INSTANCE.inventory.moved(inventory, from, to);
            PrinterAddon.trace("Hotbar: moved {} {} to slot {} (the layer needs {})", inventory.getItem(to).getCount(), item, to + 1,
                    layerNeeds.get(item));
            return true;
        }
        // Room left on the hotbar: a second stack of what the layer needs most, so a used-up stack goes straight on
        // with the next one (a few items placed fast, like string and sand in a cactus farm, ran out every 4 s)
        if (tick % CHECK_EVERY == 0) {
            for (Item item : wanted) {
                if (layerNeeds.getOrDefault(item, 0) <= item.getDefaultMaxStackSize() || hotbarStacks(inventory, item) != 1) {
                    continue;
                }
                int from = fullestInInventory(inventory, item);
                int to = emptyHotbarSlotAfter(inventory, hotbarSlotOf(inventory, item));
                if (from == -1 || to == -1) {
                    continue;
                }
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, to, ContainerInput.SWAP, player);
                AutoBuild.INSTANCE.inventory.moved(inventory, from, to);
                PrinterAddon.trace("Hotbar: a second stack of {} in slot {} ({} on the hotbar now, the layer needs {})", item, to + 1,
                        inventory.getItem(to).getCount() + inventory.getItem(hotbarSlotOf(inventory, item)).getCount(), layerNeeds.get(item));
                return true;
            }
        }
        return false;
    }

    private static int hotbarStacks(Inventory inventory, Item item) {
        int stacks = 0;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                stacks++;
            }
        }
        return stacks;
    }

    /** A full stack in the inventory, else the fullest (no small stacks onto the hotbar, those are put together). */
    private static int fullestInInventory(Inventory inventory, Item item) {
        int best = -1;
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && (best == -1 || stack.getCount() > inventory.getItem(best).getCount())) {
                best = slot;
            }
        }
        return best != -1 && inventory.getItem(best).getCount() >= inventory.getItem(best).getMaxStackSize() / 2 ? best : -1;
    }

    /**
     * An empty building slot (1-7), after this one if there is (the printer takes the first slot that has the item, so
     * that one is used up first and the new stack isn't clicked with before the server has it); never one that isn't empty.
     */
    private static int emptyHotbarSlotAfter(Inventory inventory, int after) {
        int before = -1;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (inventory.getItem(slot).isEmpty() && slot != inventory.getSelectedSlot()) {
                if (slot > after) {
                    return slot;
                }
                if (before == -1) {
                    before = slot;
                }
            }
        }
        return before;
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
     * A hotbar slot (1-7) that's empty or holds a block this layer doesn't need much; else one with a tool the layer
     * doesn't use all the time. Three netherite tools on slots 1-3 left 4 slots for a farmland layer's 5 items: dirt,
     * mud and hoppers were swapped in from the inventory 46 times a minute, each followed by a pause (11% of the
     * printer's time). The hoe stays while there's tilling (swapping it away and back is a slot change right where the
     * printer clicks); a pickaxe, axe or shovel is only for breaking, and the breaker takes it from anywhere.
     */
    private static int freeHotbarSlot(Inventory inventory, Set<Item> wanted, boolean hoeInUse) {
        int fallback = -1;
        int toolFallback = -1;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                return slot;
            }
            if (wanted.contains(stack.getItem())) {
                continue;
            }
            if (!stack.isDamageableItem()) {
                if (fallback == -1) {
                    fallback = slot;
                }
            } else if (toolFallback == -1 && !(hoeInUse && Tools.isHoe(stack))) {
                toolFallback = slot;
            }
        }
        return fallback != -1 ? fallback : toolFallback;
    }
}
