package no.sindrens.printeraddon.build;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the inventory is shared between the items a layer needs: every item gets a part of the building slots in
 * proportion to how many it still needs (a layer of as many pistons as observers: half each), so they run out at
 * about the same time and one trip tops them all up. Items that need only a slot or two get them all (not worth a
 * trip of their own later). A few slots always stay empty (for crafting results, picked up items, a tool from a
 * chest). Tools, food and everything else keep their slots.
 */
public final class InventoryBudget {
    /** Slots that are never filled by buying or fetching. */
    public static final int KEEP_FREE = 3;
    /** An item that needs this many slots or fewer for the rest of the layer gets them all. */
    private static final int SMALL = 2;

    private InventoryBudget() {
    }

    /**
     * targets: how many of each layer item to hold; wants: how many to get now to reach the target; excess: items
     * holding more than their share (or not needed by this layer at all), with how many stacks of each to keep, for
     * storage when the wants don't fit; shortSlots: how many slots the wants are short of right now; pool: the
     * building slots that were shared out.
     */
    public record Plan(Map<Item, Integer> targets, Map<Item, Integer> wants, Map<Item, Integer> excess, int shortSlots, int pool) {
        /** For the build log: "observer 640/768, piston 700/768" (held/target). */
        public String describe(Inventory inventory) {
            StringBuilder sb = new StringBuilder();
            targets.forEach((item, target) -> sb.append(name(item)).append(' ').append(inventory.countItem(item)
                            + (item == net.minecraft.world.item.Items.WATER_BUCKET ? inventory.countItem(net.minecraft.world.item.Items.BUCKET) : 0))
                    .append('/').append(target).append(", "));
            return sb.isEmpty() ? "-" : sb.substring(0, sb.length() - 2);
        }
    }

    /**
     * The plan for the layer's needs (item -> blocks still to place). {@code wholeNeeds} is what the whole schematic
     * still needs (a layer's last part stack is filled up when later layers need more of it); {@code storable} are
     * building materials of this schematic the layer doesn't need, whose slots may be emptied into storage.
     */
    public static Plan plan(Inventory inventory, Map<Item, Integer> layerNeeds, Map<Item, Integer> wholeNeeds, Set<Item> storable) {
        Map<Item, Integer> slotsHeld = new HashMap<>();
        Map<Item, Integer> counts = new HashMap<>();
        int free = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                free++;
            } else {
                slotsHeld.merge(stack.getItem(), 1, Integer::sum);
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        // An empty bucket is a water bucket a moment later (an infinite one refills itself, another is filled from
        // water nearby): it counts as one
        if (layerNeeds.containsKey(net.minecraft.world.item.Items.WATER_BUCKET)) {
            counts.merge(net.minecraft.world.item.Items.WATER_BUCKET, counts.getOrDefault(net.minecraft.world.item.Items.BUCKET, 0), Integer::sum);
        }
        // "A hoe" (pickaxe, shovel) is only asked for while there's no usable one (a worn one doesn't count)
        for (Item marker : new Item[]{Tools.HOE, Tools.PICKAXE, Tools.SHOVEL}) {
            if (layerNeeds.containsKey(marker)) {
                counts.put(marker, 0);
            }
        }
        Map<Item, Integer> targets = new LinkedHashMap<>();
        Map<Item, Integer> wants = new LinkedHashMap<>();
        Map<Item, Integer> excess = new LinkedHashMap<>();
        // Tools and buckets (one per slot): what the layer needs of them, first
        int singles = 0;
        List<Item> stackable = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : layerNeeds.entrySet()) {
            Item item = entry.getKey();
            if (entry.getValue() <= 0) {
                continue;
            }
            if (item.getDefaultMaxStackSize() == 1) {
                targets.put(item, entry.getValue());
                int want = entry.getValue() - counts.getOrDefault(item, 0);
                if (want > 0) {
                    wants.put(item, want);
                    singles += want;
                }
            } else {
                stackable.add(item);
            }
        }
        int pool = free - KEEP_FREE - singles;
        for (Item item : stackable) {
            pool += slotsHeld.getOrDefault(item, 0);
        }
        for (Item item : storable) {
            if (!layerNeeds.containsKey(item)) {
                pool += slotsHeld.getOrDefault(item, 0);
            }
        }
        pool = Math.max(0, pool);

        Map<Item, Integer> caps = new LinkedHashMap<>();
        for (Item item : stackable) {
            int stack = item.getDefaultMaxStackSize();
            caps.put(item, Math.max(1, (cover(item, layerNeeds.get(item), wholeNeeds) + stack - 1) / stack));
        }
        Map<Item, Integer> shares = share(pool, caps, layerNeeds);

        int wantSlots = 0;
        for (Item item : stackable) {
            int stack = item.getDefaultMaxStackSize();
            int target = Math.min(shares.getOrDefault(item, 0) * stack, cover(item, layerNeeds.get(item), wholeNeeds));
            targets.put(item, target);
            int have = counts.getOrDefault(item, 0);
            if (target > have) {
                int want = target - have;
                wants.put(item, want);
                wantSlots += Math.max(0, (want - topUpRoom(inventory, item) + stack - 1) / stack);
            } else if (have - target >= stack) {
                excess.put(item, (target + stack - 1) / stack);
            }
        }
        for (Item item : storable) {
            if (!layerNeeds.containsKey(item) && slotsHeld.containsKey(item)) {
                excess.put(item, 0);
            }
        }
        int shortSlots = Math.max(0, wantSlots + singles - Math.max(0, free - KEEP_FREE));
        return new Plan(targets, wants, excess, shortSlots, pool);
    }

    /**
     * The wants cut down to what fits in the free slots now (when nothing could be put away to make room): the free
     * slots are shared in proportion to what each item wants, at least a slot each while there are any.
     */
    public static Map<Item, Integer> fitNow(Inventory inventory, Map<Item, Integer> wants) {
        int free = Math.max(0, InventoryCleaner.freeSlots(inventory) - KEEP_FREE);
        Map<Item, Integer> fitted = new LinkedHashMap<>();
        Map<Item, Integer> slotsWanted = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : wants.entrySet()) {
            Item item = entry.getKey();
            int stack = item.getDefaultMaxStackSize();
            int topUp = Math.min(entry.getValue(), topUpRoom(inventory, item));
            int rest = entry.getValue() - topUp;
            if (topUp > 0) {
                fitted.put(item, topUp);
            }
            if (rest > 0) {
                slotsWanted.put(item, (rest + stack - 1) / stack);
            }
        }
        Map<Item, Integer> shares = share(free, slotsWanted, wants);
        slotsWanted.forEach((item, slots) -> {
            int stack = item.getDefaultMaxStackSize();
            int rest = wants.get(item) - fitted.getOrDefault(item, 0);
            int got = Math.min(rest, shares.getOrDefault(item, 0) * stack);
            if (got > 0) {
                fitted.merge(item, got, Integer::sum);
            }
        });
        return fitted;
    }

    /**
     * Shares {@code pool} slots between the items (item -> slots it could use at most): small needs in full, the
     * rest in proportion to what each could use, at least one slot each while there are enough.
     */
    static Map<Item, Integer> share(int pool, Map<Item, Integer> caps, Map<Item, Integer> needs) {
        Map<Item, Integer> shares = new LinkedHashMap<>();
        List<Item> open = new ArrayList<>(caps.keySet());
        open.sort(Comparator.comparingInt((Item i) -> caps.get(i)).thenComparing(i -> -needs.getOrDefault(i, 0)));
        int left = pool;
        long total = 0;
        for (Item item : open) {
            total += caps.get(item);
        }
        if (total <= left) {
            shares.putAll(caps);
            return shares;
        }
        // Small needs first, in full, while the others still get a slot each
        for (java.util.Iterator<Item> it = open.iterator(); it.hasNext(); ) {
            Item item = it.next();
            int cap = caps.get(item);
            if (cap > SMALL || left - cap < open.size() - 1) {
                break;
            }
            shares.put(item, cap);
            left -= cap;
            it.remove();
        }
        if (open.isEmpty()) {
            return shares;
        }
        // Fewer slots than items: the ones needed most get one
        if (left < open.size()) {
            open.sort(Comparator.comparingInt((Item i) -> -needs.getOrDefault(i, 0)));
            for (Item item : open) {
                shares.put(item, left-- > 0 ? 1 : 0);
            }
            return shares;
        }
        // One slot each, the rest in proportion to what each could still use
        int rest = left - open.size();
        long restTotal = 0;
        for (Item item : open) {
            restTotal += caps.get(item) - 1;
        }
        Map<Item, Double> fraction = new HashMap<>();
        int given = 0;
        for (Item item : open) {
            double exact = restTotal == 0 ? 0 : rest * (double) (caps.get(item) - 1) / restTotal;
            int whole = (int) Math.floor(exact);
            shares.put(item, 1 + whole);
            fraction.put(item, exact - whole);
            given += whole;
        }
        List<Item> byFraction = new ArrayList<>(open);
        byFraction.sort(Comparator.comparingDouble((Item i) -> -fraction.get(i)));
        for (int i = 0; i < rest - given && i < byFraction.size(); i++) {
            Item item = byFraction.get(i);
            if (shares.get(item) < caps.get(item)) {
                shares.merge(item, 1, Integer::sum);
            }
        }
        return shares;
    }

    /**
     * How many of an item to hold at most for the layer: what it still needs, filled up to whole stacks when later
     * layers need more of it anyway.
     */
    private static int cover(Item item, int need, Map<Item, Integer> wholeNeeds) {
        int stack = item.getDefaultMaxStackSize();
        int whole = wholeNeeds.getOrDefault(item, 0);
        int up = (need + stack - 1) / stack * stack;
        return whole > need ? Math.min(up, whole) : need;
    }

    static int topUpRoom(Inventory inventory, Item item) {
        int room = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && stack.getComponents().equals(new ItemStack(item).getComponents())) {
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }

    private static String name(Item item) {
        return item.toString().replace("minecraft:", "");
    }
}
