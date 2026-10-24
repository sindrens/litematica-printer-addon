package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Puts small stacks of the same item together, so the inventory doesn't fill up with half stacks (13 stacks of 1 to
 * 8 mud and dirt once, and then a purchase had no room). Every way of doing it is checked against what the server
 * shows afterwards: a server (or its anticheat) that doesn't take inventory clicks while no menu is open undoes them
 * (a log had the same merge clicked 8 times, 20 s apart, with nothing changing), and a way that doesn't work there is
 * left alone from then on. While a vault is open the clicks are an ordinary player's: stacks are put together there.
 */
public final class StackMerge {
    /** A small stack moved onto the hotbar stack it belongs to, two small stacks in the inventory put together, or that in an open vault. */
    public enum Way { ONTO_HOTBAR, IN_INVENTORY, IN_VAULT }

    /** A merge to check: the small stack's inventory slot, its item and count before. */
    private record Pending(Way way, int slot, Item item, int count, long at) {
    }

    @Nullable
    private static Pending pending;
    private static final Map<Way, Integer> failures = new EnumMap<>(Way.class);
    private static final Set<Way> broken = EnumSet.noneOf(Way.class);
    private static long lastMerge = -1000;

    private StackMerge() {
    }

    /** At the start of a build: what didn't work stays known (the server doesn't change), the timers start over. */
    public static void reset() {
        pending = null;
        lastMerge = -1000;
    }

    public static boolean works(Way way) {
        return !broken.contains(way);
    }

    /** Checks the last merge once the server's answer is surely there; a way that fails twice in a row isn't used again. */
    public static void check(Minecraft mc, Inventory inventory) {
        Pending last = pending;
        if (last == null || AutoBuild.clientTicks - last.at() < Math.max(10, 2L * ServerLag.pingTicks(mc) + 6)) {
            return;
        }
        checkNow(inventory);
    }

    /**
     * A vault or the shop is open: what it puts into the inventory would make a merge done before look undone, so
     * that one isn't judged (a merge in the vault itself is checked before anything is taken, see ChestFetcher).
     */
    public static void containerOpen() {
        if (pending != null && pending.way() != Way.IN_VAULT) {
            pending = null;
        }
    }

    /** Checks the last merge now (the caller waited for the server's answer). */
    public static void checkNow(Inventory inventory) {
        Pending last = pending;
        if (last == null) {
            return;
        }
        pending = null;
        ItemStack now = inventory.getItem(last.slot());
        boolean worked = now.isEmpty() || !now.is(last.item()) || now.getCount() < last.count();
        if (worked) {
            failures.put(last.way(), 0);
            return;
        }
        int failed = failures.merge(last.way(), 1, Integer::sum);
        BuildLog.line("STACKS", "putting stacks together (" + name(last.way()) + ") didn't work: the server still shows "
                + now.getCount() + " " + itemName(last.item()) + " in slot " + (last.slot() + 1)
                + (failed >= 2 ? "; this server doesn't take it, not tried that way again" : ""));
        if (failed >= 2) {
            broken.add(last.way());
        }
    }

    /**
     * A stack of the layer's items on the hotbar with room: a smaller stack of it from the inventory goes onto it in
     * one click (shift-click, which fills the hotbar stack first). The inventory's small stacks get used up that way
     * instead of piling up, and the hand doesn't run empty while there's more.
     */
    public static boolean ontoHotbar(Minecraft mc, LocalPlayer player, Inventory inventory, Map<Item, Integer> layerNeeds) {
        if (!works(Way.ONTO_HOTBAR) || pending != null || AutoBuild.clientTicks - lastMerge < 20) {
            return false;
        }
        for (int hotbar = 0; hotbar < Inventory.getSelectionSize(); hotbar++) {
            ItemStack onHotbar = inventory.getItem(hotbar);
            if (onHotbar.isEmpty() || onHotbar.getMaxStackSize() <= 1 || !layerNeeds.containsKey(onHotbar.getItem())) {
                continue;
            }
            int room = onHotbar.getMaxStackSize() - onHotbar.getCount();
            int small = -1;
            for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inventory.getItem(slot);
                // Only one that goes on whole: nothing spills into another hotbar slot
                if (!stack.isEmpty() && stack.getCount() < stack.getMaxStackSize() && stack.getCount() <= room
                        && ItemStack.isSameItemSameComponents(stack, onHotbar)
                        && (small < 0 || stack.getCount() < inventory.getItem(small).getCount())) {
                    small = slot;
                }
            }
            if (small < 0) {
                continue;
            }
            ItemStack stack = inventory.getItem(small);
            pending = new Pending(Way.ONTO_HOTBAR, small, stack.getItem(), stack.getCount(), AutoBuild.clientTicks);
            lastMerge = AutoBuild.clientTicks;
            PrinterAddon.trace("Stacks: {} {} from slot {} onto the {} in hotbar slot {}", stack.getCount(), stack.getItem(),
                    small + 1, onHotbar.getCount(), hotbar + 1);
            // Inventory menu slots 9-35 are the same numbers as the inventory's
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, small, 0, ContainerInput.QUICK_MOVE, player);
            AutoBuild.INSTANCE.inventory.moved();
            return true;
        }
        return false;
    }

    /** Two small stacks of the same item in the inventory (not the hotbar): the smaller one onto the other, every 2 s at most. */
    public static boolean inInventory(Minecraft mc, LocalPlayer player, Inventory inventory) {
        if (!works(Way.IN_INVENTORY) || pending != null || AutoBuild.clientTicks - lastMerge < 40) {
            return false;
        }
        int[] pair = smallPair(inventory, Inventory.getSelectionSize(), Inventory.INVENTORY_SIZE);
        if (pair == null) {
            return false;
        }
        ItemStack small = inventory.getItem(pair[0]);
        pending = new Pending(Way.IN_INVENTORY, pair[0], small.getItem(), small.getCount(), AutoBuild.clientTicks);
        lastMerge = AutoBuild.clientTicks;
        PrinterAddon.trace("Stacks: {} {} from slot {} onto slot {}", small.getCount(), small.getItem(), pair[0] + 1, pair[1] + 1);
        click(mc, player, player.inventoryMenu, pair[0], pair[1]);
        AutoBuild.INSTANCE.inventory.moved();
        return true;
    }

    /**
     * With a vault (or chest) open: up to {@code max} pairs of small stacks in the player's inventory put together.
     * Returns how many.
     */
    public static int inMenu(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int max) {
        if (!works(Way.IN_VAULT)) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        // Inventory slot -> the menu's slot for it
        int[] menuSlot = new int[Inventory.INVENTORY_SIZE];
        java.util.Arrays.fill(menuSlot, -1);
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE) {
                menuSlot[slot.getContainerSlot()] = slot.index;
            }
        }
        int done = 0;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        while (done < max) {
            int[] pair = smallPair(inventory, 0, Inventory.INVENTORY_SIZE, used);
            if (pair == null || menuSlot[pair[0]] < 0 || menuSlot[pair[1]] < 0) {
                break;
            }
            ItemStack small = inventory.getItem(pair[0]);
            if (done == 0 && pending == null) {
                pending = new Pending(Way.IN_VAULT, pair[0], small.getItem(), small.getCount(), AutoBuild.clientTicks);
            }
            PrinterAddon.trace("Stacks (vault open): {} {} from slot {} onto slot {}", small.getCount(), small.getItem(), pair[0] + 1, pair[1] + 1);
            click(mc, player, menu, menuSlot[pair[0]], menuSlot[pair[1]]);
            used.add(pair[0]);
            used.add(pair[1]);
            done++;
        }
        if (done > 0) {
            lastMerge = AutoBuild.clientTicks;
            BuildLog.lineOrKeep("STACKS", "put " + done + " pair" + (done == 1 ? "" : "s") + " of small stacks together while the vault was open");
        }
        return done;
    }

    /** Small stack onto the other: pick it up, put it on the other, and what doesn't fit back where it was. */
    private static void click(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int from, int to) {
        int id = menu.containerId;
        mc.gameMode.handleContainerInput(id, from, 0, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(id, to, 0, ContainerInput.PICKUP, player);
        if (!menu.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(id, from, 0, ContainerInput.PICKUP, player);
        }
    }

    @Nullable
    private static int[] smallPair(Inventory inventory, int from, int to) {
        return smallPair(inventory, from, to, java.util.Set.of());
    }

    /**
     * The smallest plain stack that has another stack of its item with room (the fullest of those), between these
     * inventory slots. Tools and single items never.
     */
    @Nullable
    private static int[] smallPair(Inventory inventory, int from, int to, java.util.Set<Integer> skip) {
        int bestSmall = -1;
        int bestOther = -1;
        for (int a = from; a < to; a++) {
            ItemStack small = inventory.getItem(a);
            if (skip.contains(a) || small.isEmpty() || small.getMaxStackSize() <= 1 || small.getCount() >= small.getMaxStackSize()
                    || (bestSmall >= 0 && small.getCount() >= inventory.getItem(bestSmall).getCount())) {
                continue;
            }
            int other = -1;
            for (int b = from; b < to; b++) {
                ItemStack stack = inventory.getItem(b);
                if (b == a || skip.contains(b) || stack.getCount() >= stack.getMaxStackSize() || stack.getCount() < small.getCount()
                        || !ItemStack.isSameItemSameComponents(small, stack)) {
                    continue;
                }
                if (other < 0 || stack.getCount() > inventory.getItem(other).getCount()) {
                    other = b;
                }
            }
            if (other >= 0) {
                bestSmall = a;
                bestOther = other;
            }
        }
        return bestSmall < 0 ? null : new int[]{bestSmall, bestOther};
    }

    private static String name(Way way) {
        return switch (way) {
            case ONTO_HOTBAR -> "onto the hotbar stack";
            case IN_INVENTORY -> "in the inventory";
            case IN_VAULT -> "with a vault open";
        };
    }

    private static String itemName(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
