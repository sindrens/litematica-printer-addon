package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Works out how to get items that are crafted rather than bought: what to buy first, then what to craft in which
 * order. Rails come from iron ingots (or iron blocks) and sticks, powered rails from gold, sticks and redstone,
 * pumpkin seeds from pumpkins (the player's own first). A crafting table is made from planks if there's none.
 */
public final class CraftPlan {
    /** One crafting step. */
    public record Step(Item item, int count) {
    }

    public final Map<Item, Integer> buy = new LinkedHashMap<>();
    public final List<Step> craft = new ArrayList<>();
    private final Map<Item, Integer> have = new LinkedHashMap<>();
    private final Map<net.minecraft.tags.TagKey<Item>, Integer> haveTag = new LinkedHashMap<>();
    private final Predicate<Item> sold;
    private final Inventory inventory;
    private boolean needsTable;

    private CraftPlan(Inventory inventory, Predicate<Item> sold) {
        this.inventory = inventory;
        this.sold = sold;
    }

    /** True for items this plans (everything else is simply bought). */
    public static boolean crafted(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.PUMPKIN_SEEDS;
    }

    /**
     * A plan for these items (item -> how many are missing). {@code sold} says what the shop sells; seeds are only
     * crafted from pumpkins when there are pumpkins or the shop sells them.
     */
    public static CraftPlan of(Minecraft mc, Map<Item, Integer> missing, Predicate<Item> sold, boolean tableNearby) {
        CraftPlan plan = new CraftPlan(mc.player.getInventory(), sold);
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            plan.add(entry.getKey(), entry.getValue());
        }
        if (plan.needsTable && !tableNearby && plan.take(Items.CRAFTING_TABLE, 1) < 1) {
            plan.planks(4);
            plan.craft.add(plan.tableIndex(), new Step(Items.CRAFTING_TABLE, 1));
        }
        return plan;
    }

    private int tableIndex() {
        // Before the first step that needs it (rails), after what it's made of
        for (int i = 0; i < craft.size(); i++) {
            Item item = craft.get(i).item();
            if (item == Items.RAIL || item == Items.POWERED_RAIL) {
                return i;
            }
        }
        return craft.size();
    }

    private void add(Item item, int count) {
        // Always the shop first: crafted only when the shop doesn't sell it (seeds: own pumpkins first, see below)
        if ((item == Items.RAIL || item == Items.POWERED_RAIL) && sold.test(item)) {
            buy.merge(item, count, Integer::sum);
        } else if (item == Items.RAIL) {
            int crafts = ceil(count, 16);
            ingot(Items.IRON_INGOT, Items.IRON_BLOCK, 6 * crafts);
            sticks(crafts);
            needsTable = true;
            craft.add(new Step(Items.RAIL, crafts * 16));
        } else if (item == Items.POWERED_RAIL) {
            int crafts = ceil(count, 6);
            ingot(Items.GOLD_INGOT, Items.GOLD_BLOCK, 6 * crafts);
            sticks(crafts);
            redstone(crafts);
            needsTable = true;
            craft.add(new Step(Items.POWERED_RAIL, crafts * 6));
        } else if (item == Items.PUMPKIN_SEEDS) {
            int crafts = ceil(count, 4);
            // Pumpkins the player brought (or that came from storage) are crafted first
            int pumpkins = take(Items.PUMPKIN, crafts);
            if (pumpkins < crafts && sold.test(Items.PUMPKIN_SEEDS)) {
                buy.merge(Items.PUMPKIN_SEEDS, (crafts - pumpkins) * 4, Integer::sum);
                crafts = pumpkins;
            }
            if (pumpkins < crafts && sold.test(Items.PUMPKIN)) {
                buy.merge(Items.PUMPKIN, crafts - pumpkins, Integer::sum);
                pumpkins = crafts;
            }
            if (pumpkins > 0) {
                craft.add(new Step(Items.PUMPKIN_SEEDS, pumpkins * 4));
            }
            if (pumpkins < crafts) {
                buy.merge(Items.PUMPKIN_SEEDS, (crafts - pumpkins) * 4, Integer::sum);
            }
        } else {
            buy.merge(item, count, Integer::sum);
        }
    }

    /** Ingots: from the inventory, else from blocks (9 each) in the inventory, else bought (blocks if sold). */
    private void ingot(Item ingot, Item block, int count) {
        int missing = count - take(ingot, count);
        if (missing <= 0) {
            return;
        }
        int blocks = ceil(missing, 9);
        int ownBlocks = take(block, blocks);
        if (ownBlocks < blocks) {
            if (sold.test(block) || !sold.test(ingot)) {
                buy.merge(block, blocks - ownBlocks, Integer::sum);
            } else {
                buy.merge(ingot, missing - ownBlocks * 9, Integer::sum);
                blocks = ownBlocks;
            }
        }
        if (blocks > 0) {
            craft.add(0, new Step(ingot, blocks * 9));
        }
    }

    private void redstone(int count) {
        int missing = count - take(Items.REDSTONE, count);
        if (missing <= 0) {
            return;
        }
        if (sold.test(Items.REDSTONE) || !sold.test(Items.REDSTONE_BLOCK)) {
            buy.merge(Items.REDSTONE, missing, Integer::sum);
        } else {
            buy.merge(Items.REDSTONE_BLOCK, ceil(missing, 9), Integer::sum);
            craft.add(0, new Step(Items.REDSTONE, ceil(missing, 9) * 9));
        }
    }

    /** Sticks: from the inventory, else crafted from planks (2 planks make 4). */
    private void sticks(int count) {
        int missing = count - take(Items.STICK, count);
        if (missing <= 0) {
            return;
        }
        if (sold.test(Items.STICK)) {
            buy.merge(Items.STICK, missing, Integer::sum);
            return;
        }
        int crafts = ceil(missing, 4);
        planks(crafts * 2);
        craft.add(new Step(Items.STICK, crafts * 4));
    }

    /** Planks of any wood: from the inventory, else from logs (4 each), else bought. */
    private void planks(int count) {
        int missing = count - takeTag(ItemTags.PLANKS, count);
        if (missing <= 0) {
            return;
        }
        if (sold.test(Items.OAK_PLANKS) && !sold.test(Items.OAK_LOG)) {
            buy.merge(Items.OAK_PLANKS, missing, Integer::sum);
            return;
        }
        int logs = ceil(missing, 4);
        int ownLogs = takeTag(ItemTags.LOGS, logs);
        if (ownLogs < logs) {
            buy.merge(Items.OAK_LOG, logs - ownLogs, Integer::sum);
        }
        craft.add(0, new Step(Items.OAK_PLANKS, logs * 4));
    }

    /** Takes up to {@code count} from what the inventory has (not counting what other steps already took). */
    private int take(Item item, int count) {
        int available = inventory.countItem(item) - have.getOrDefault(item, 0);
        int taken = Math.max(0, Math.min(count, available));
        have.merge(item, taken, Integer::sum);
        return taken;
    }

    private int takeTag(net.minecraft.tags.TagKey<Item> tag, int count) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(tag)) {
                total += stack.getCount();
            }
        }
        int available = total - haveTag.getOrDefault(tag, 0);
        int taken = Math.max(0, Math.min(count, available));
        haveTag.merge(tag, taken, Integer::sum);
        return taken;
    }

    private static int ceil(int a, int b) {
        return (a + b - 1) / b;
    }

    public boolean isEmpty() {
        return buy.isEmpty() && craft.isEmpty();
    }
}
