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
    /** Ticks to wait after a change at least (a round trip to the server and back on a fast connection). */
    private static final int MIN_SETTLE_TICKS = 4;
    private static int settleTicks = MIN_SETTLE_TICKS;

    private final Item[] last = new Item[Inventory.INVENTORY_SIZE];
    /** When the item in hand last changed (nothing is placed for a moment then). */
    private long changedAt = -1000;
    /**
     * When each slot last got another kind of item: blocks placed with what's in such a slot wait for a moment, the
     * others go on (a hotbar refill used to stop the printer for 4 ticks every time; with string and sand used up
     * every 4 seconds in a cactus farm that was 7% of its turns).
     */
    private final long[] slotChangedAt = new long[Inventory.INVENTORY_SIZE];
    private long updatedAt = -1;
    private String lastChange = "";

    public InventoryWatch() {
        java.util.Arrays.fill(slotChangedAt, -1000);
    }

    /** Compares the slots with last tick (cheap, and only once per tick however often it's called). */
    public void update(Minecraft mc) {
        if (mc.player == null || updatedAt == AutoBuild.clientTicks) {
            return;
        }
        updatedAt = AutoBuild.clientTicks;
        settleTicks = settleTicksFor(mc);
        Inventory inventory = mc.player.getInventory();
        StringBuilder changes = null;
        int changed = 0;
        boolean hand = false;
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
                // A water bucket that's full again (or empty) changes nothing the printer clicks with; nor does the
                // stack in hand being used up (nothing on its way to the server: the next click takes another slot)
                boolean bucket = isBucket(last[slot]) && isBucket(now);
                boolean usedUp = now == null && slot == inventory.getSelectedSlot();
                if (!bucket && !usedUp) {
                    changed++;
                    hand |= slot == inventory.getSelectedSlot();
                    slotChangedAt[slot] = AutoBuild.clientTicks;
                }
                last[slot] = now;
            }
        }
        // Only what can make the server's hand differ waits for all blocks: the item in hand changed. Items swapped
        // between other slots only hold up the blocks placed with those slots (see settlingFor)
        if (changes != null && changed > 0) {
            lastChange = changes.toString();
            if (hand) {
                changedAt = AutoBuild.clientTicks;
            }
            if (AutoBuild.INSTANCE.isRunning()) {
                PrinterAddon.trace("Inventory slots changed ({}), waiting {} ticks before placing {}", lastChange, settleTicks,
                        hand ? "anything" : "with those slots");
            }
        }
    }

    /** The add-on itself just moved items between these slots: blocks placed with them wait (all, if one is in hand). */
    public void moved(Inventory inventory, int... slots) {
        for (int slot : slots) {
            if (slot == inventory.getSelectedSlot()) {
                moved();
                return;
            }
            if (slot >= 0 && slot < slotChangedAt.length) {
                slotChangedAt[slot] = AutoBuild.clientTicks;
            }
        }
    }

    /**
     * Blocks placed with this item wait: the slot the printer takes it from (the first one that has it, as the printer
     * looks) changed too recently to trust what the server has there.
     */
    public boolean settlingFor(Inventory inventory, Item item) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return AutoBuild.clientTicks - slotChangedAt[slot] < settleTicks;
            }
        }
        return false;
    }

    private static boolean isBucket(@javax.annotation.Nullable Item item) {
        return item == net.minecraft.world.item.Items.BUCKET || item == net.minecraft.world.item.Items.WATER_BUCKET;
    }

    /** The add-on itself just moved an item: same wait, without waiting a tick to notice. */
    public void moved() {
        changedAt = AutoBuild.clientTicks;
    }

    /** True while the slots changed too recently to trust what the server has in the hand. */
    public boolean settling() {
        return AutoBuild.clientTicks - changedAt < settleTicks;
    }

    /** For the wrong-block report: how long ago the slots last changed, and how. */
    public String describe(Minecraft mc) {
        String hand = mc.player == null ? "?" : name(mc.player.getMainHandItem().isEmpty() ? null : mc.player.getMainHandItem().getItem());
        return "hand " + hand + ", slots last changed " + (AutoBuild.clientTicks - changedAt) + " ticks ago (" + lastChange + ")";
    }

    /** The round trip in ticks from the player's ping (as the server measures it), plus a tick to spare. */
    private static int settleTicksFor(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            return MIN_SETTLE_TICKS;
        }
        net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info == null) {
            return MIN_SETTLE_TICKS;
        }
        return Math.max(MIN_SETTLE_TICKS, Math.min(20, info.getLatency() / 50 + 2));
    }

    private static String name(Item item) {
        return item == null ? "empty" : item.toString().replace("minecraft:", "");
    }
}
