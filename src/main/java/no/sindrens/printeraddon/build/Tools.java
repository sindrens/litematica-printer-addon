package no.sindrens.printeraddon.build;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.config.Configs;

/**
 * The tools a build needs, got by the add-on itself: nothing has to be in the inventory at the start. A hoe for
 * farmland comes from the inventory or storage, else it's crafted: from diamonds or iron at hand (or in storage),
 * else from stone the shop sells for very little. Never a block of diamond or iron bought for one (that was $8,550
 * for a hoe once, with 7 diamonds in a vault). A special hoe (enchanted, renamed) is put away before it breaks; an
 * ordinary one is used up.
 */
public final class Tools {
    /** Stands for "a hoe" in the lists of what's needed: storage gives any usable hoe, crafting picks the material. */
    public static final Item HOE = Items.STONE_HOE;
    /** Stand for "a pickaxe" and "a shovel" (for breaking wrong blocks: hoppers and observers only drop with a pickaxe). */
    public static final Item PICKAXE = Items.STONE_PICKAXE;
    public static final Item SHOVEL = Items.STONE_SHOVEL;

    /** True for the items that stand for a kind of tool in the lists of what's needed. */
    public static boolean isMarker(Item item) {
        return item == HOE || item == PICKAXE || item == SHOVEL;
    }

    /** The marker for the kind of tool this is (HOE, PICKAXE, SHOVEL), or null for anything else. */
    @javax.annotation.Nullable
    public static Item kindOf(ItemStack stack) {
        if (isHoe(stack)) {
            return HOE;
        }
        if (stack.is(ItemTags.PICKAXES)) {
            return PICKAXE;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return SHOVEL;
        }
        return null;
    }

    /** A tool of this kind good for a while (a hoe: see usableHoe; a pickaxe or shovel: not about to break). */
    public static boolean usable(Item kind, ItemStack stack) {
        if (kind == HOE) {
            return usableHoe(stack);
        }
        return kindOf(stack) == kind && (!stack.isDamageableItem()
                || left(stack) > Math.max(Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue(), stack.getMaxDamage() / 10));
    }

    public static boolean has(Item kind, Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (usable(kind, inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pickaxe and shovel the build may need for breaking wrong blocks, that the inventory has no usable one of:
     * a pickaxe when the schematic has blocks that drop nothing without one (hoppers, observers, concrete), a shovel
     * when it has dirt, mud or the like.
     */
    public static java.util.List<Item> breakingToolsMissing(java.util.Set<net.minecraft.world.level.block.Block> blocks, Inventory inventory) {
        boolean pickaxe = false;
        boolean shovel = false;
        for (net.minecraft.world.level.block.Block block : blocks) {
            net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
            pickaxe |= state.requiresCorrectToolForDrops() && state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
            shovel |= state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL);
        }
        java.util.List<Item> missing = new java.util.ArrayList<>();
        if (pickaxe && !has(PICKAXE, inventory)) {
            missing.add(PICKAXE);
        }
        if (shovel && !has(SHOVEL, inventory)) {
            missing.add(SHOVEL);
        }
        return missing;
    }

    /** A stone or wooden pickaxe or shovel, as the add-on makes them (put away with the building items). */
    public static boolean cheapTool(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.STONE_PICKAXE || item == Items.STONE_SHOVEL || item == Items.WOODEN_PICKAXE || item == Items.WOODEN_SHOVEL;
    }
    /** Uses of a stone hoe: how many to make for the tilling left. */
    private static final int STONE_USES = 131;
    /** At most this many hoes are made in one go (each takes a slot). */
    private static final int MAX_AT_ONCE = 3;

    private Tools() {
    }

    /** Uses left. */
    public static int left(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    /** Any hoe (by its kind, so it doesn't depend on the server's item tags). */
    public static boolean isHoe(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.HoeItem || stack.is(ItemTags.HOES);
    }

    /** A hoe the printer may use: not about to break (a new one is got before), and not a special one that's worn. */
    public static boolean usableHoe(ItemStack stack) {
        return isHoe(stack) && !wornSpecial(stack)
                && (!stack.isDamageableItem() || left(stack) >= Math.min(50, Math.max(5, stack.getMaxDamage() * 15 / 100)));
    }

    public static boolean hasUsableHoe(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (usableHoe(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /** Uses left in all the hoes of the inventory (the worn special ones not counted). */
    public static int hoeUses(Inventory inventory) {
        long uses = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isHoe(stack) && !wornSpecial(stack)) {
                uses += Math.min(100000, left(stack));
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, uses);
    }

    /** How many hoes to get for this much tilling (stone ones last 131 uses each; at most 3 at a time). */
    public static int hoesFor(int tilling) {
        return Math.max(1, Math.min(MAX_AT_ONCE, (tilling + STONE_USES - 1) / STONE_USES));
    }

    /**
     * Same, but never more than there are free slots for (one is kept free): each hoe takes a slot of its own. With
     * a full inventory it crafted three and couldn't take them out of the crafting grid ("Your inventory is full!").
     */
    public static int hoesFor(int tilling, Inventory inventory) {
        return Math.max(1, Math.min(hoesFor(tilling), InventoryCleaner.freeSlots(inventory) - 1));
    }

    /**
     * A tool of the player's own that's special (enchanted, renamed...) and nearly worn out: the printer would use it
     * until it breaks, so it's put away (into a vault or linked chest).
     */
    public static boolean wornSpecial(ItemStack stack) {
        return isHoe(stack) && stack.isDamageableItem() && !InventoryCleaner.ordinary(stack)
                && left(stack) <= Math.max(Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue(), stack.getMaxDamage() / 20);
    }

    /** What hoes are made of (kept while a hoe is needed, put away otherwise). */
    public static boolean hoeMaterial(Item item) {
        ItemStack stack = new ItemStack(item);
        return item == Items.DIAMOND || item == Items.IRON_INGOT || item == Items.STICK || stack.is(ItemTags.STONE_TOOL_MATERIALS)
                || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS);
    }
}
