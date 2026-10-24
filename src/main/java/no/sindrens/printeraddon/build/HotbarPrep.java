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

    private long tick;

    /** Moves at most one item. Returns true if it did. {@code still}: the player isn't flying or walking anywhere. */
    public boolean tick(Minecraft mc, Map<Item, Integer> layerNeeds, boolean still) {
        LocalPlayer player = mc.player;
        // Also with the chat or the pause menu open (alt-tabbed): the printer goes on placing then, and an empty
        // hotbar stopped it. Not while a container or the inventory is open (the player may be moving items)
        if (player == null || mc.gameMode == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || layerNeeds.isEmpty() || ++tick % CHECK_EVERY != 0) {
            return false;
        }
        Inventory inventory = player.getInventory();
        StackMerge.check(mc, inventory);
        // Putting stacks together only while standing (clicks on the move are what anticheats look for)
        if (still && (StackMerge.ontoHotbar(mc, player, inventory, layerNeeds) || StackMerge.inInventory(mc, player, inventory))) {
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
            // The smallest stack first: small stacks get used up instead of piling up
            int from = -1;
            for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(item) && (from == -1 || stack.getCount() < inventory.getItem(from).getCount())) {
                    from = slot;
                }
            }
            int to = freeHotbarSlot(inventory, wantedSet);
            if (from == -1 || to == -1) {
                continue;
            }
            // Swap: inventory slot <-> hotbar slot (inventory menu slots 9-35 are the same numbers)
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, to, ContainerInput.SWAP, player);
            AutoBuild.INSTANCE.inventory.moved();
            PrinterAddon.trace("Hotbar: moved {} {} to slot {} (the layer needs {})", inventory.getItem(to).getCount(), item, to + 1,
                    layerNeeds.get(item));
            return true;
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
