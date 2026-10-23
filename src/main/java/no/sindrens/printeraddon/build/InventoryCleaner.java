package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.PrinterAddon;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes room in the inventory when it's (almost) full: drops leftover building materials of this schematic that
 * no block still needs. Never drops anything else (tools, food, valuables, items the build still needs).
 */
public class InventoryCleaner {
    /** Below this many free slots, leftovers are dropped. */
    private static final int MIN_FREE_SLOTS = 3;
    private static final int COOLDOWN = 10;
    /** Rails kept beyond what the schematic needs, for temporary rails. */
    private static final int SPARE_RAILS = 16;

    /** Every item this schematic has needed during the build. */
    private final Set<Item> materials = new HashSet<>();
    private int cooldown;

    public void reset() {
        materials.clear();
        cooldown = 0;
    }

    /** Called with what the whole schematic still needs (item -> count). */
    public void remember(Map<Item, Integer> neededWhole) {
        materials.addAll(neededWhole.keySet());
    }

    /** Returns true if it dropped something this tick. */
    public boolean tick(Minecraft mc, Map<Item, Integer> neededWhole) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || player.getAbilities().instabuild) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        Inventory inventory = player.getInventory();
        if (freeSlots(inventory) >= MIN_FREE_SLOTS) {
            return false;
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !materials.contains(stack.getItem())) {
                continue;
            }
            Item item = stack.getItem();
            // Spare rails only while the schematic still has rails to place (for temporary rails)
            boolean railsLeft = neededWhole.keySet().stream().anyMatch(i -> new ItemStack(i).is(net.minecraft.tags.ItemTags.RAILS));
            int keep = neededWhole.getOrDefault(item, 0) + (item == Items.RAIL && railsLeft ? SPARE_RAILS : 0);
            if (inventory.countItem(item) - stack.getCount() < keep) {
                continue;
            }
            // Throw the whole stack, through the player's own inventory menu (no screen needed)
            int menuSlot = slot < 9 ? 36 + slot : slot;
            PrinterAddon.debug("Dropping {} {} to make room (the build doesn't need them)", stack.getCount(),
                    stack.getHoverName().getString());
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, 1, ContainerInput.THROW, player);
            cooldown = COOLDOWN;
            return true;
        }
        return false;
    }

    public static int freeSlots(Inventory inventory) {
        int free = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }
}
