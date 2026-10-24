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
        if (player == null || mc.gameMode == null || mc.gui.screen() != null || layerNeeds.isEmpty() || ++tick % CHECK_EVERY != 0) {
            return false;
        }
        Inventory inventory = player.getInventory();

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
            PrinterAddon.debug("Hotbar: moved {} to slot {} (the layer needs {})", item, to + 1, layerNeeds.get(item));
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

    /** A hotbar slot (1-7) that's empty or holds something this layer doesn't need much. */
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
