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
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.PUMPKIN_SEEDS || Tools.isMarker(item)
                || item == Items.REDSTONE;
    }

    /** What else an item can be made from (to look for in storage too): pumpkins for seeds, blocks for dust. */
    @javax.annotation.Nullable
    public static Item madeFrom(Item item) {
        if (item == Items.PUMPKIN_SEEDS) {
            return Items.PUMPKIN;
        }
        if (item == Items.REDSTONE) {
            return Items.REDSTONE_BLOCK;
        }
        return null;
    }

    /** How many of {@link #madeFrom} make this many (rounded up). */
    public static int madeFromCount(Item item, int count) {
        return item == Items.PUMPKIN_SEEDS ? ceil(count, 4) : item == Items.REDSTONE ? ceil(count, 9) : 0;
    }

    /**
     * A plan for these items (item -> how many are missing). {@code sold} says what the shop sells; seeds are made
     * from pumpkins (own, or bought), and only bought as seeds when the shop has no pumpkins.
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
            if (item == Items.RAIL || item == Items.POWERED_RAIL || Tools.kindOf(new ItemStack(item)) != null) {
                return i;
            }
        }
        return craft.size();
    }

    private void add(Item item, int count) {
        // Always the shop first: crafted only when the shop doesn't sell it (seeds: own pumpkins first, see below)
        if ((item == Items.RAIL || item == Items.POWERED_RAIL) && sold.test(item)) {
            buy.merge(item, count, Integer::sum);
        } else if (Tools.isMarker(item)) {
            diamondTool(item, count);
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
        } else if (item == Items.REDSTONE) {
            // Dust: from the player's own redstone blocks first (9 each), then bought; as blocks when the shop
            // doesn't sell dust or couldn't deliver it, or blocks cost less per dust, or crafting is preferred
            int own = take(Items.REDSTONE_BLOCK, ceil(count, 9));
            if (own > 0) {
                craft.add(new Step(Items.REDSTONE, own * 9));
            }
            int missing = count - own * 9;
            if (missing > 0) {
                if (sold.test(Items.REDSTONE_BLOCK) && (!sold.test(Items.REDSTONE) || blocksBetter(Items.REDSTONE, Items.REDSTONE_BLOCK, 9))) {
                    buy.merge(Items.REDSTONE_BLOCK, ceil(missing, 9), Integer::sum);
                    craft.add(new Step(Items.REDSTONE, ceil(missing, 9) * 9));
                } else {
                    buy.merge(Items.REDSTONE, missing, Integer::sum);
                }
            }
        } else if (item == Items.PUMPKIN_SEEDS) {
            // Seeds are crafted from pumpkins (4 each): the player's own pumpkins (or from storage) first, then bought
            // pumpkins. Seeds are only bought when the shop has no pumpkins
            int crafts = ceil(count, 4);
            int pumpkins = take(Items.PUMPKIN, crafts);
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

    /**
     * Tools are always diamond (the player's rule): diamonds from the inventory (storage was looked in before), else
     * from blocks of diamond at hand, else blocks of diamond bought (9 diamonds each: a pickaxe, an axe, a hoe and a
     * shovel). The sticks from planks or logs.
     */
    private void diamondTool(Item tool, int count) {
        int diamonds = Tools.diamondsFor(tool) * count;
        int missing = diamonds - take(Items.DIAMOND, diamonds);
        if (missing > 0) {
            int blocks = ceil(missing, 9);
            int ownBlocks = take(Items.DIAMOND_BLOCK, blocks);
            if (ownBlocks < blocks && sold.test(Items.DIAMOND_BLOCK)) {
                buy.merge(Items.DIAMOND_BLOCK, blocks - ownBlocks, Integer::sum);
                ownBlocks = blocks;
            }
            if (ownBlocks > 0) {
                craft.add(0, new Step(Items.DIAMOND, ownBlocks * 9));
            }
        }
        sticks(2 * count);
        needsTable = true;
        craft.add(new Step(tool, count));
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

    /**
     * Whether to buy the block form ({@code per} items each) rather than the item itself: when that's set (fewer
     * purchases), or the blocks cost less per item in the shop scan.
     */
    private boolean blocksBetter(Item item, Item block, int per) {
        if (no.sindrens.printeraddon.config.Configs.Shop.PREFER_BLOCKS.getBooleanValue()) {
            return true;
        }
        Double itemPrice = no.sindrens.printeraddon.shop.ShopBuyer.price(Minecraft.getInstance(), item);
        Double blockPrice = no.sindrens.printeraddon.shop.ShopBuyer.price(Minecraft.getInstance(), block);
        return itemPrice != null && blockPrice != null && blockPrice / per < itemPrice;
    }

    /** Sticks: from the inventory, else crafted from planks (2 planks make 4). */
    private void sticks(int count) {
        int missing = count - take(Items.STICK, count);
        missing -= spare(Items.STICK, missing);
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
        spares.merge(Items.STICK, crafts * 4 - missing, Integer::sum);
    }

    /** Planks of any wood: from the inventory, else from logs (4 each), else bought. */
    private void planks(int count) {
        int missing = count - takeTag(ItemTags.PLANKS, count);
        missing -= spare(Items.OAK_PLANKS, missing);
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
        spares.merge(Items.OAK_PLANKS, logs * 4 - missing, Integer::sum);
    }

    /** Made beyond what a step needed (a log makes 4 planks, 2 were needed): later steps take that first. */
    private final Map<Item, Integer> spares = new java.util.HashMap<>();

    private int spare(Item item, int count) {
        int spare = spares.getOrDefault(item, 0);
        int used = Math.max(0, Math.min(spare, count));
        spares.put(item, spare - used);
        return used;
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
