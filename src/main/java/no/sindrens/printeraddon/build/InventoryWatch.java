package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;

/**
 * Notices when the kind of item in a slot changes (a swap into the hotbar, a stack used up, bought or picked up
 * items landing in an empty slot). Until the server has had a few ticks to agree on what's in each slot, nothing is
 * placed: a click sent while a swap is still on its way uses whatever the server has in that slot, which is how
 * observers ended up where pistons should be.
 */
public class InventoryWatch {
    /** Ticks to wait after a change (a round trip to the server and back, with some ping). */
    private static final int SETTLE_TICKS = 4;

    private final Item[] last = new Item[Inventory.INVENTORY_SIZE];
    /** When each slot last changed (what's in it, not how many). */
    private final long[] slotChangedAt = new long[Inventory.INVENTORY_SIZE];
    private long changedAt = -1000;
    private long updatedAt = -1;
    private String lastChange = "";

    /** Compares the slots with last tick (cheap, and only once per tick however often it's called). */
    public void update(Minecraft mc) {
        if (mc.player == null || updatedAt == AutoBuild.clientTicks) {
            return;
        }
        updatedAt = AutoBuild.clientTicks;
        Inventory inventory = mc.player.getInventory();
        StringBuilder changes = null;
        for (int slot = 0; slot < last.length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Item now = stack.isEmpty() ? null : stack.getItem();
            if (now != last[slot]) {
                if (changes == null) {
                    changes = new StringBuilder();
                } else {
                    changes.append(", ");
                }
                changes.append(slot).append(": ").append(name(last[slot])).append(" -> ").append(name(now));
                last[slot] = now;
                slotChangedAt[slot] = AutoBuild.clientTicks;
            }
        }
        if (changes != null) {
            changedAt = AutoBuild.clientTicks;
            lastChange = changes.toString();
            if (AutoBuild.INSTANCE.isRunning()) {
                PrinterAddon.trace("Inventory slots changed ({}), waiting {} ticks before placing", lastChange, SETTLE_TICKS);
            }
        }
    }

    /** The add-on itself just moved an item: same wait, without waiting a tick to notice. */
    public void moved() {
        changedAt = AutoBuild.clientTicks;
        movedAt = AutoBuild.clientTicks;
    }

    /** The add-on's last own item move (a swap click changes two slots at once; wait for both). */
    private long movedAt = -1000;

    /** True while any slot changed too recently (for the add-on's own item moves). */
    public boolean settling() {
        return AutoBuild.clientTicks - changedAt < SETTLE_TICKS;
    }

    /**
     * True while this slot changed too recently to trust what the server has in it. Only the slot a click uses
     * matters: other slots changing (a stack used up elsewhere, items arriving in the inventory) can't make a
     * click place the wrong thing, so they don't hold anything up.
     */
    public boolean settling(int slot) {
        if (slot < 0 || slot >= slotChangedAt.length) {
            return false;
        }
        return AutoBuild.clientTicks - slotChangedAt[slot] < SETTLE_TICKS || AutoBuild.clientTicks - movedAt < SETTLE_TICKS;
    }

    /** The selected hotbar slot changed too recently (what the printer's next click uses). */
    public boolean handSettling(Minecraft mc) {
        return mc.player != null && settling(mc.player.getInventory().getSelectedSlot());
    }

    /** For the wrong-block report: how long ago the slots last changed, and how. */
    public String describe(Minecraft mc) {
        String hand = mc.player == null ? "?" : name(mc.player.getMainHandItem().isEmpty() ? null : mc.player.getMainHandItem().getItem());
        return "hand " + hand + ", slots last changed " + (AutoBuild.clientTicks - changedAt) + " ticks ago (" + lastChange + ")";
    }

    private static String name(Item item) {
        return item == null ? "empty" : item.toString().replace("minecraft:", "");
    }
}
