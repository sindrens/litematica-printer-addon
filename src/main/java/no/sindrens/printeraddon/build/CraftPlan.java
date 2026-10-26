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
 * Works out how to get items that can be bought in more than one way: what to buy first, then what to craft in which
 * order. Always the player's own items first, then the cheapest way by the shop's prices: seeds or pumpkins, ingots
 * or blocks (or both: 10 ingots as 1 block and 1 ingot), dust or blocks, rails or what they're made of, sticks or
 * logs. A crafting table is made from planks if there's none.
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

    /** A bigger item that's taken apart into {@code per} of the item in the inventory's grid (a block of iron: 9 ingots). */
    public record Pack(Item big, int per) {
    }

    /**
     * What the shop may sell bigger, to be taken apart: bought as whichever costs less for the amount needed (18
     * ingots: 18 ingots or 2 blocks, whichever is cheaper; 10 ingots: also 1 block and 1 ingot).
     */
    public static final Map<Item, Pack> PACKS = Map.ofEntries(
            Map.entry(Items.IRON_INGOT, new Pack(Items.IRON_BLOCK, 9)),
            Map.entry(Items.GOLD_INGOT, new Pack(Items.GOLD_BLOCK, 9)),
            Map.entry(Items.NETHERITE_INGOT, new Pack(Items.NETHERITE_BLOCK, 9)),
            Map.entry(Items.DIAMOND, new Pack(Items.DIAMOND_BLOCK, 9)),
            Map.entry(Items.EMERALD, new Pack(Items.EMERALD_BLOCK, 9)),
            Map.entry(Items.LAPIS_LAZULI, new Pack(Items.LAPIS_BLOCK, 9)),
            Map.entry(Items.COAL, new Pack(Items.COAL_BLOCK, 9)),
            Map.entry(Items.REDSTONE, new Pack(Items.REDSTONE_BLOCK, 9)),
            Map.entry(Items.RAW_IRON, new Pack(Items.RAW_IRON_BLOCK, 9)),
            Map.entry(Items.RAW_GOLD, new Pack(Items.RAW_GOLD_BLOCK, 9)),
            Map.entry(Items.RAW_COPPER, new Pack(Items.RAW_COPPER_BLOCK, 9)),
            Map.entry(Items.WHEAT, new Pack(Items.HAY_BLOCK, 9)),
            Map.entry(Items.SLIME_BALL, new Pack(Items.SLIME_BLOCK, 9)),
            Map.entry(Items.BONE_MEAL, new Pack(Items.BONE_BLOCK, 9)),
            Map.entry(Items.DRIED_KELP, new Pack(Items.DRIED_KELP_BLOCK, 9)),
            Map.entry(Items.IRON_NUGGET, new Pack(Items.IRON_INGOT, 9)),
            Map.entry(Items.GOLD_NUGGET, new Pack(Items.GOLD_INGOT, 9)),
            Map.entry(Items.PUMPKIN_SEEDS, new Pack(Items.PUMPKIN, 4)));

    /** True for items this plans (everything else is simply bought). */
    public static boolean crafted(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || Tools.isMarker(item) || PACKS.containsKey(item);
    }

    /** What else an item can be made from (to look for in storage too): pumpkins for seeds, blocks for ingots or dust. */
    @javax.annotation.Nullable
    public static Item madeFrom(Item item) {
        Pack pack = PACKS.get(item);
        return pack == null ? null : pack.big();
    }

    /** How many of {@link #madeFrom} make this many (rounded up). */
    public static int madeFromCount(Item item, int count) {
        Pack pack = PACKS.get(item);
        return pack == null ? 0 : ceil(count, pack.per());
    }

    /** Price of one in the shop scan, or null (not sold, not scanned). */
    @javax.annotation.Nullable
    private Double price(Item item) {
        return sold.test(item) ? no.sindrens.printeraddon.shop.ShopBuyer.price(Minecraft.getInstance(), item) : null;
    }

    /**
     * The cheapest way to buy {@code count} of {@code small} when {@code big} makes {@code per} of them: {big, small}
     * to buy. All small, as many big as fit and the rest small, or all big (rounded up), whatever costs least. Without
     * prices for both: the one that's sold; with both sold and no prices, big when {@code bigByDefault} (or the
     * "craft from blocks" setting), else small. Equal cost: big (fewer to buy and carry).
     */
    private int[] cheapest(Item small, Item big, int per, int count, boolean bigByDefault) {
        boolean smallSold = sold.test(small);
        boolean bigSold = sold.test(big) && !Crafter.refused(small);
        int allBig = ceil(count, per);
        if (!bigSold) {
            return new int[]{0, count};
        }
        if (!smallSold) {
            return new int[]{allBig, 0};
        }
        Double ps = price(small);
        Double pb = price(big);
        if (ps == null || pb == null) {
            boolean big1 = bigByDefault || no.sindrens.printeraddon.config.Configs.Shop.PREFER_BLOCKS.getBooleanValue();
            return big1 ? new int[]{allBig, 0} : new int[]{0, count};
        }
        int floor = count / per;
        int[][] options = {{0, count}, {floor, count - floor * per}, {allBig, 0}};
        int[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (int[] o : options) {
            double cost = o[0] * pb + o[1] * ps;
            // Cheaper by more than a rounding error; equal: the one with more big ones (later in the list)
            if (best == null || cost < bestCost * 0.995 || cost <= bestCost * 1.005 && o[0] > best[0]) {
                best = o;
                bestCost = Math.min(bestCost, cost);
            }
        }
        double asSmall = count * ps;
        if (best[0] > 0 && Math.abs(asSmall - bestCost) > 0.01) {
            BuildLog.line("PRICE", String.format(java.util.Locale.ROOT, "%d %s: %s for $%.0f (all as %s: $%.0f)", count, name(small),
                    (best[0] > 0 ? best[0] + " " + name(big) : "") + (best[0] > 0 && best[1] > 0 ? " + " : "") + (best[1] > 0 ? best[1] + " " + name(small) : ""),
                    bestCost, name(small), asSmall));
        } else if (best[0] == 0 && allBig * pb > asSmall + 0.01) {
            BuildLog.line("PRICE", String.format(java.util.Locale.ROOT, "%d %s: bought as they are for $%.0f (as %d %s: $%.0f)", count, name(small),
                    asSmall, allBig, name(big), allBig * pb));
        }
        return best;
    }

    private static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    /**
     * Cost of {@code count} of an item the cheapest way (as {@link #cheapest} would buy it), or null when a price is
     * missing. For comparing buying something made with making it.
     */
    @javax.annotation.Nullable
    private Double cost(Item item, int count) {
        if (count <= 0) {
            return 0.0;
        }
        Pack pack = PACKS.get(item);
        Double ps = price(item);
        if (pack != null) {
            Double pb = price(pack.big());
            if (pb != null && !Crafter.refused(item)) {
                int floor = count / pack.per();
                double viaBig = Math.min(ceil(count, pack.per()) * pb, ps == null ? Double.MAX_VALUE : floor * pb + (count - floor * pack.per()) * ps);
                return ps == null ? viaBig : Math.min(viaBig, count * ps);
            }
        }
        if (item == Items.STICK) {
            Double logs = price(Items.OAK_LOG);
            Double viaLogs = logs == null ? null : ceil(count, 8) * logs;           // a log: 4 planks, 8 sticks
            return ps == null ? viaLogs : viaLogs == null ? count * ps : Math.min(count * ps, viaLogs);
        }
        return ps == null ? null : count * ps;
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
        // Rails: bought, or made when that's cheaper (or the shop doesn't sell them)
        if ((item == Items.RAIL || item == Items.POWERED_RAIL) && sold.test(item) && !railCraftCheaper(item, count)) {
            buy.merge(item, count, Integer::sum);
        } else if (Tools.isMarker(item)) {
            // Nothing bought for one the server won't craft (it goes on with the tools there are)
            if (!Crafter.refused(item)) {
                diamondTool(item, count);
            }
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
        } else if (PACKS.containsKey(item)) {
            pack(item, PACKS.get(item), count);
        } else {
            buy.merge(item, count, Integer::sum);
        }
    }

    /**
     * An item that also comes bigger (seeds in pumpkins, ingots in blocks): the player's own bigger ones first (taken
     * apart), then bought the cheapest way (see {@link #cheapest}). Seeds without prices: pumpkins, as before.
     */
    private void pack(Item item, Pack pack, int count) {
        int own = take(pack.big(), ceil(count, pack.per()));
        if (own > 0) {
            craft.add(0, new Step(item, own * pack.per()));
        }
        int missing = count - own * pack.per();
        if (missing <= 0) {
            return;
        }
        int[] split = cheapest(item, pack.big(), pack.per(), missing, item == Items.PUMPKIN_SEEDS);
        if (split[0] > 0) {
            // Nuggets from ingots: the ingots themselves may be cheapest as blocks (pack in a pack)
            if (PACKS.containsKey(pack.big()) && !sold.test(pack.big())) {
                pack(pack.big(), PACKS.get(pack.big()), split[0]);
            } else {
                buy.merge(pack.big(), split[0], Integer::sum);
            }
            craft.add(new Step(item, split[0] * pack.per()));
        }
        if (split[1] > 0) {
            buy.merge(item, split[1], Integer::sum);
        }
    }

    /** Making rails (ingots or blocks, sticks) costs less than buying them, by the shop's prices (none known: no). */
    private boolean railCraftCheaper(Item rail, int count) {
        Double bought = price(rail);
        if (bought == null) {
            return false;
        }
        boolean powered = rail == Items.POWERED_RAIL;
        int crafts = ceil(count, powered ? 6 : 16);
        Double metal = cost(powered ? Items.GOLD_INGOT : Items.IRON_INGOT, 6 * crafts);
        Double sticks = cost(Items.STICK, crafts);
        Double dust = powered ? cost(Items.REDSTONE, crafts) : Double.valueOf(0);
        if (metal == null || sticks == null || dust == null) {
            return false;
        }
        double made = metal + sticks + dust;
        boolean cheaper = made < bought * count * 0.98 && !Crafter.refused(rail);
        BuildLog.line("PRICE", String.format(java.util.Locale.ROOT, "%d %s: %s (bought $%.0f, made $%.0f)", count, name(rail),
                cheaper ? "made" : "bought", bought * count, made));
        return cheaper;
    }

    /**
     * Tools are always diamond (the player's rule): diamonds from the inventory (storage was looked in before), else
     * from blocks of diamond at hand, else blocks of diamond bought (9 diamonds each: a pickaxe, an axe, a hoe and a
     * shovel). The sticks from planks or logs.
     */
    private void diamondTool(Item tool, int count) {
        int diamonds = Tools.diamondsFor(tool) * count;
        int missing = diamonds - take(Items.DIAMOND, diamonds);
        // What's left of a block of diamond crafted for the tool before (a pickaxe and a shovel took a block each)
        missing -= spare(Items.DIAMOND, missing);
        if (missing > 0) {
            int ownBlocks = take(Items.DIAMOND_BLOCK, ceil(missing, 9));
            int blocks = ownBlocks;
            int left = Math.max(0, missing - ownBlocks * 9);
            if (left > 0) {
                // Diamonds or blocks of diamond, whichever costs less (blocks when the prices aren't known, as before)
                int[] split = cheapest(Items.DIAMOND, Items.DIAMOND_BLOCK, 9, left, true);
                if (split[0] > 0) {
                    buy.merge(Items.DIAMOND_BLOCK, split[0], Integer::sum);
                    blocks += split[0];
                }
                if (split[1] > 0) {
                    buy.merge(Items.DIAMOND, split[1], Integer::sum);
                }
            }
            if (blocks > 0) {
                craft.add(0, new Step(Items.DIAMOND, blocks * 9));
                spares.merge(Items.DIAMOND, Math.max(0, blocks * 9 - missing), Integer::sum);
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
        int ownBlocks = take(block, ceil(missing, 9));
        int blocks = ownBlocks;
        int left = missing - ownBlocks * 9;
        if (left > 0) {
            // Bought the cheapest way: ingots, blocks, or blocks and the rest as ingots
            int[] split = cheapest(ingot, block, 9, left, false);
            if (split[0] > 0) {
                buy.merge(block, split[0], Integer::sum);
                blocks += split[0];
            }
            if (split[1] > 0) {
                buy.merge(ingot, split[1], Integer::sum);
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
        pack(Items.REDSTONE, PACKS.get(Items.REDSTONE), missing);
    }

    /** Sticks: from the inventory, else crafted from planks (2 planks make 4). */
    private void sticks(int count) {
        int missing = count - take(Items.STICK, count);
        missing -= spare(Items.STICK, missing);
        if (missing <= 0) {
            return;
        }
        // Bought as sticks, unless logs (a log: 8 sticks) cost less, or sticks aren't sold
        Double stickPrice = price(Items.STICK);
        Double logPrice = price(Items.OAK_LOG);
        boolean viaLogs = !sold.test(Items.STICK) || stickPrice != null && logPrice != null && ceil(missing, 8) * logPrice < missing * stickPrice * 0.98;
        if (!viaLogs) {
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
        // Planks, or logs (4 planks each), whichever costs less; logs when the prices aren't known, as before
        Double plankPrice = price(Items.OAK_PLANKS);
        Double logPrice = price(Items.OAK_LOG);
        if (sold.test(Items.OAK_PLANKS) && (!sold.test(Items.OAK_LOG)
                || plankPrice != null && logPrice != null && missing * plankPrice < ceil(missing, 4) * logPrice * 0.98)) {
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
