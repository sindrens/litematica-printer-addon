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
 * Makes room in the inventory when it's (almost) full: puts leftover building materials of this schematic that
 * no block still needs into the linked chests, or drops them when there are none (or they're full). Never touches
 * anything else (tools, food, valuables, items the build still needs).
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
    /** Ticks since leftovers were last put in chests: if the inventory is still full soon after, they're full. */
    private int sinceStored = Integer.MAX_VALUE;

    public void reset() {
        materials.clear();
        cooldown = 0;
        sinceStored = Integer.MAX_VALUE;
    }

    /** Called with what the whole schematic still needs (item -> count). */
    public void remember(Map<Item, Integer> neededWhole) {
        materials.addAll(neededWhole.keySet());
    }

    /** Returns true if it dropped something this tick. */
    public boolean tick(Minecraft mc, Map<Item, Integer> neededWhole, ChestFetcher fetcher) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || player.getAbilities().instabuild) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (sinceStored < Integer.MAX_VALUE) {
            sinceStored++;
        }
        Inventory inventory = player.getInventory();
        if (freeSlots(inventory) >= MIN_FREE_SLOTS) {
            return false;
        }

        Set<Item> leftovers = new HashSet<>();
        boolean store = sinceStored > 20 * 60;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !materials.contains(stack.getItem())) {
                continue;
            }
            Item item = stack.getItem();
            // Never anything the layers being built still need
            if (AutoBuild.INSTANCE.layers.layerNeeds().containsKey(item)) {
                continue;
            }
            // Spare rails only while the schematic still has rails to place (for temporary rails)
            boolean railsLeft = neededWhole.keySet().stream().anyMatch(i -> new ItemStack(i).is(net.minecraft.tags.ItemTags.RAILS));
            int keep = neededWhole.getOrDefault(item, 0) + (item == Items.RAIL && railsLeft ? SPARE_RAILS : 0);
            if (inventory.countItem(item) - stack.getCount() < keep) {
                continue;
            }
            if (store) {
                leftovers.add(item);
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
        if (store && leftovers.isEmpty() && makeRoomForMissing(mc, inventory, fetcher)) {
            return true;
        }
        if (store && !leftovers.isEmpty()) {
            sinceStored = 0;
            if (fetcher.startStore(mc, leftovers)) {
                PrinterAddon.debug("Putting leftovers in the linked chests: {}", leftovers);
                return true;
            }
            // No linked chest: they're dropped on the next ticks
        }
        return false;
    }

    /**
     * Full of things the layer needs while it also needs something that isn't here at all (and can't be bought for
     * lack of room): the item with the most stacks gives up two, to the linked chests or else on the ground.
     */
    private boolean makeRoomForMissing(Minecraft mc, Inventory inventory, ChestFetcher fetcher) {
        if (freeSlots(inventory) > 1) {
            return false;
        }
        Map<Item, Integer> layerNeeds = AutoBuild.INSTANCE.layers.layerNeeds();
        // Only when the layer is really stuck on it, and it can be got (the shop sells it or a chest is linked)
        if (!AutoBuild.INSTANCE.layers.stalledFor(20 * 30)) {
            return false;
        }
        boolean chests = ChestFetcher.hasStorage(mc);
        Item missing = layerNeeds.keySet().stream().filter(item -> inventory.countItem(item) == 0)
                .filter(item -> chests || (no.sindrens.printeraddon.config.Configs.Shop.AUTO_BUY.getBooleanValue()
                        && no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, item)))
                .findFirst().orElse(null);
        if (missing == null) {
            return false;
        }
        Map<Item, Integer> stacks = new java.util.HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !stack.isDamageableItem() && layerNeeds.containsKey(stack.getItem())) {
                stacks.merge(stack.getItem(), 1, Integer::sum);
            }
        }
        Item most = stacks.entrySet().stream().filter(e -> e.getValue() >= 3).max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (most == null) {
            return false;
        }
        String what = new ItemStack(most).getHoverName().getString();
        String forWhat = new ItemStack(missing).getHoverName().getString();
        sinceStored = 0;
        if (fetcher.startStore(mc, Map.of(most, stacks.get(most) - 2))) {
            no.sindrens.printeraddon.util.Chat.info("Inventory full: putting 2 stacks of " + what + " in your chest to make room for " + forWhat + ".");
            return true;
        }
        // No linked chest: drop one stack (smallest first), the next round the other
        int smallest = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(most) && (smallest == -1 || stack.getCount() < inventory.getItem(smallest).getCount())) {
                smallest = slot;
            }
        }
        no.sindrens.printeraddon.util.Chat.warn("Inventory full: dropping a stack of " + what + " to make room for " + forWhat
                + ". Link a chest (J+B) to keep extras instead.");
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, smallest < 9 ? 36 + smallest : smallest, 1,
                ContainerInput.THROW, mc.player);
        sinceStored = 20 * 60 + 1 - 20 * 5;
        cooldown = COOLDOWN;
        return true;
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
