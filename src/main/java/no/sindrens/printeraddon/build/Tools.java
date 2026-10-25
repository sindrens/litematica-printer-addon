package no.sindrens.printeraddon.build;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.config.Configs;

/**
 * The tools a build needs, got by the add-on itself: nothing has to be in the inventory at the start. The player's
 * rules: a plain tool from the inventory, the vaults or linked chests first; if there's none, a diamond one is
 * crafted at once (diamonds from storage, else a block of diamond bought); the player's own tools (enchanted,
 * renamed, or netherite) only when there's nothing else, and put away before they break. Plain tools are used until
 * they break.
 */
public final class Tools {
    /**
     * Stand for "a hoe", "a pickaxe", "a shovel" and "an axe" in the lists of what's needed: storage gives any plain one
     * of the kind, crafting makes a diamond one.
     */
    public static final Item HOE = Items.DIAMOND_HOE;
    public static final Item PICKAXE = Items.DIAMOND_PICKAXE;
    public static final Item SHOVEL = Items.DIAMOND_SHOVEL;
    public static final Item AXE = Items.DIAMOND_AXE;

    /** True for the items that stand for a kind of tool in the lists of what's needed. */
    public static boolean isMarker(Item item) {
        return item == HOE || item == PICKAXE || item == SHOVEL || item == AXE;
    }

    /** The marker for the kind of tool this is (HOE, PICKAXE, SHOVEL, AXE), or null for anything else. */
    @javax.annotation.Nullable
    public static Item kindOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (isHoe(stack)) {
            return HOE;
        }
        if (stack.is(ItemTags.PICKAXES)) {
            return PICKAXE;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return SHOVEL;
        }
        if (stack.is(ItemTags.AXES)) {
            return AXE;
        }
        return null;
    }

    /** Diamonds one tool of this kind takes. */
    public static int diamondsFor(Item kind) {
        return kind == PICKAXE || kind == AXE ? 3 : kind == HOE ? 2 : 1;
    }

    /** One of the player's own tools: enchanted, renamed (anything more than wear), or netherite. Used last. */
    public static boolean privateTool(ItemStack stack) {
        return kindOf(stack) != null && (!InventoryCleaner.ordinary(stack) || isNetherite(stack.getItem()));
    }

    private static boolean isNetherite(Item item) {
        return item == Items.NETHERITE_HOE || item == Items.NETHERITE_PICKAXE || item == Items.NETHERITE_SHOVEL
                || item == Items.NETHERITE_AXE || item == Items.NETHERITE_SWORD;
    }

    /** A plain tool of this kind: used until it breaks (the player's own tools don't count, see privateTool). */
    public static boolean usable(Item kind, ItemStack stack) {
        return kindOf(stack) == kind && !privateTool(stack) && (!stack.isDamageableItem() || left(stack) >= 1);
    }

    /** One of the player's own tools of this kind, not about to break (for when there's no plain one). */
    public static boolean usablePrivate(Item kind, ItemStack stack) {
        return kindOf(stack) == kind && privateTool(stack) && !wornSpecial(stack)
                && (!stack.isDamageableItem() || left(stack) > Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue());
    }

    public static boolean has(Item kind, Inventory inventory) {
        return bestSlot(kind, inventory) >= 0;
    }

    /**
     * There's a tool of this kind to work with: a plain one, or one of the player's own at least as good as diamond
     * that isn't about to break. A netherite pickaxe and shovel in the inventory still had a diamond pickaxe and shovel
     * asked for: diamonds and sticks were taken from the vaults, crafting was refused by the server, and it all went
     * back (5 s and two slots).
     */
    public static boolean covered(Item kind, Inventory inventory) {
        if (has(kind, inventory)) {
            return true;
        }
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (usablePrivate(kind, stack) && rank(stack.getItem()) >= rank(Items.DIAMOND_PICKAXE)) {
                return true;
            }
        }
        return false;
    }

    /** Uses left in the player's own hoes at least as good as diamond, down to where they're put away. */
    public static int privateHoeUses(Inventory inventory) {
        long uses = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (usablePrivate(HOE, stack) && rank(stack.getItem()) >= rank(Items.DIAMOND_HOE)) {
                int reserve = Math.max(Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue(), stack.getMaxDamage() / 20);
                uses += Math.max(0, Math.min(100000, left(stack)) - reserve);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, uses);
    }

    /** How many plain tools of this kind the inventory has (the player's own don't count). */
    public static int count(Item kind, Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (usable(kind, inventory.getItem(slot))) {
                count++;
            }
        }
        return count;
    }

    /**
     * The slot of the plain tool of this kind to use, or -1: the best material first (diamond before iron or stone),
     * of those the most worn one (each is used up before the next).
     */
    public static int bestSlot(Item kind, Inventory inventory) {
        int best = -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!usable(kind, stack)) {
                continue;
            }
            if (best < 0) {
                best = slot;
                continue;
            }
            ItemStack other = inventory.getItem(best);
            int rank = rank(stack.getItem());
            int otherRank = rank(other.getItem());
            if (rank > otherRank || rank == otherRank && left(stack) < left(other)) {
                best = slot;
            }
        }
        return best;
    }

    /** How good the material is: netherite 6, diamond 5, iron 4, stone 3, gold 2, wood 1. */
    public static int rank(Item item) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        if (id.startsWith("netherite_")) {
            return 6;
        }
        if (id.startsWith("diamond_")) {
            return 5;
        }
        if (id.startsWith("iron_")) {
            return 4;
        }
        if (id.startsWith("stone_")) {
            return 3;
        }
        if (id.startsWith("golden_")) {
            return 2;
        }
        return id.startsWith("wooden_") ? 1 : 0;
    }

    /**
     * The tools the build may need for breaking wrong blocks, that the inventory has no plain one of: a pickaxe when
     * the schematic has blocks that drop nothing without one (hoppers, observers, concrete), a shovel when it has dirt,
     * mud or the like, an axe for wood and pumpkins. Not one the server won't let it craft (breaking goes on without).
     */
    public static java.util.List<Item> breakingToolsMissing(java.util.Set<net.minecraft.world.level.block.Block> blocks, Inventory inventory) {
        boolean pickaxe = false;
        boolean shovel = false;
        boolean axe = false;
        for (net.minecraft.world.level.block.Block block : blocks) {
            net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
            pickaxe |= state.requiresCorrectToolForDrops() && state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
            shovel |= state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL);
            axe |= state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE);
        }
        java.util.List<Item> missing = new java.util.ArrayList<>();
        if (pickaxe && !covered(PICKAXE, inventory) && !Crafter.refused(PICKAXE)) {
            missing.add(PICKAXE);
        }
        if (shovel && !covered(SHOVEL, inventory) && !Crafter.refused(SHOVEL)) {
            missing.add(SHOVEL);
        }
        if (axe && !covered(AXE, inventory) && !Crafter.refused(AXE)) {
            missing.add(AXE);
        }
        return missing;
    }

    /** A plain tool (as the add-on makes them): put away with the building items, for next time. */
    public static boolean addonTool(ItemStack stack) {
        return kindOf(stack) != null && !privateTool(stack);
    }

    /** Uses of a diamond hoe (what the add-on makes): how many to make for the tilling left. */
    private static final int DIAMOND_USES = 1561;
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

    /** A hoe the printer may use: a plain one, until it breaks (see usable). */
    public static boolean usableHoe(ItemStack stack) {
        return usable(HOE, stack);
    }

    public static boolean hasUsableHoe(Inventory inventory) {
        return has(HOE, inventory);
    }

    /** Uses left in all the plain hoes of the inventory. */
    public static int hoeUses(Inventory inventory) {
        long uses = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (usableHoe(stack)) {
                uses += Math.min(100000, left(stack));
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, uses);
    }

    /** How many hoes to get for this much tilling (diamond ones last 1561 uses each; at most 3 at a time). */
    public static int hoesFor(int tilling) {
        return Math.max(1, Math.min(MAX_AT_ONCE, (tilling + DIAMOND_USES - 1) / DIAMOND_USES));
    }

    /**
     * Same, but never more than there are free slots for (one is kept free): each hoe takes a slot of its own. With
     * a full inventory it crafted three and couldn't take them out of the crafting grid ("Your inventory is full!").
     */
    public static int hoesFor(int tilling, Inventory inventory) {
        return Math.max(1, Math.min(hoesFor(tilling), InventoryCleaner.freeSlots(inventory) - 1));
    }

    /**
     * A tool of the player's own (enchanted, renamed, netherite) that's nearly worn out: it's put away (into a vault
     * or linked chest) before it breaks, and not used any more.
     */
    public static boolean wornSpecial(ItemStack stack) {
        return privateTool(stack) && stack.isDamageableItem()
                && left(stack) <= Math.max(Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue(), stack.getMaxDamage() / 20);
    }

    /** What tools are made of (kept while a tool is needed, put away otherwise). */
    public static boolean hoeMaterial(Item item) {
        ItemStack stack = new ItemStack(item);
        return item == Items.DIAMOND || item == Items.DIAMOND_BLOCK || item == Items.STICK || stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.LOGS);
    }
}
