package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The recipes the add-on crafts, for crafting by hand when they aren't in the player's recipe book (servers don't
 * always unlock them): the pattern is put into the grid with clicks like a player would, one set at a time.
 */
public record ManualRecipe(Item result, int count, int width, int height, String[] rows, Map<Character, Predicate<ItemStack>> keys) {
    @Nullable
    public static ManualRecipe of(Item item) {
        Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
        Predicate<ItemStack> planks = s -> s.is(ItemTags.PLANKS);
        Predicate<ItemStack> stick = s -> s.is(Items.STICK);
        if (new ItemStack(item).is(ItemTags.PLANKS)) {
            return new ManualRecipe(item, 4, 1, 1, new String[]{"L"}, Map.of('L', logs));
        }
        if (item == Items.STICK) {
            return new ManualRecipe(item, 4, 1, 2, new String[]{"P", "P"}, Map.of('P', planks));
        }
        if (item == Items.CRAFTING_TABLE) {
            return new ManualRecipe(item, 1, 2, 2, new String[]{"PP", "PP"}, Map.of('P', planks));
        }
        if (item == Items.DIAMOND) {
            return new ManualRecipe(item, 9, 1, 1, new String[]{"B"}, Map.of('B', s -> s.is(Items.DIAMOND_BLOCK)));
        }
        if (item == Items.IRON_INGOT) {
            return new ManualRecipe(item, 9, 1, 1, new String[]{"B"}, Map.of('B', s -> s.is(Items.IRON_BLOCK)));
        }
        if (item == Items.GOLD_INGOT) {
            return new ManualRecipe(item, 9, 1, 1, new String[]{"B"}, Map.of('B', s -> s.is(Items.GOLD_BLOCK)));
        }
        if (item == Items.REDSTONE) {
            return new ManualRecipe(item, 9, 1, 1, new String[]{"B"}, Map.of('B', s -> s.is(Items.REDSTONE_BLOCK)));
        }
        if (item == Items.PUMPKIN_SEEDS) {
            return new ManualRecipe(item, 4, 1, 1, new String[]{"P"}, Map.of('P', s -> s.is(Items.PUMPKIN)));
        }
        if (item == Items.DIAMOND_HOE) {
            return new ManualRecipe(item, 1, 2, 3, new String[]{"DD", " S", " S"},
                    Map.of('D', s -> s.is(Items.DIAMOND), 'S', stick));
        }
        if (item == Items.IRON_HOE) {
            return new ManualRecipe(item, 1, 2, 3, new String[]{"II", " S", " S"},
                    Map.of('I', s -> s.is(Items.IRON_INGOT), 'S', stick));
        }
        if (item == Items.STONE_HOE) {
            return new ManualRecipe(item, 1, 2, 3, new String[]{"CC", " S", " S"},
                    Map.of('C', s -> s.is(ItemTags.STONE_TOOL_MATERIALS), 'S', stick));
        }
        if (item == Items.WOODEN_HOE) {
            return new ManualRecipe(item, 1, 2, 3, new String[]{"PP", " S", " S"},
                    Map.of('P', planks, 'S', stick));
        }
        if (item == Items.RAIL) {
            return new ManualRecipe(item, 16, 3, 3, new String[]{"I I", "ISI", "I I"},
                    Map.of('I', s -> s.is(Items.IRON_INGOT), 'S', stick));
        }
        if (item == Items.POWERED_RAIL) {
            return new ManualRecipe(item, 6, 3, 3, new String[]{"G G", "GSG", "GRG"},
                    Map.of('G', s -> s.is(Items.GOLD_INGOT), 'S', stick, 'R', s -> s.is(Items.REDSTONE)));
        }
        return null;
    }

    /** How many of each ingredient one set takes. */
    private Map<Character, Integer> perSet() {
        Map<Character, Integer> counts = new HashMap<>();
        for (String row : rows) {
            for (char c : row.toCharArray()) {
                if (c != ' ') {
                    counts.merge(c, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** True if the inventory has what one set takes. */
    public boolean canCraft(Inventory inventory) {
        for (Map.Entry<Character, Integer> entry : perSet().entrySet()) {
            int have = 0;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && keys.get(entry.getKey()).test(stack)) {
                    have += stack.getCount();
                }
            }
            if (have < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts sets into the grid (the inventory's 2x2 one, or the crafting table's 3x3 one): per ingredient, a stack is
     * picked up, one item is right-clicked into each of its grid slots, the rest goes back. A recipe of one item (seeds
     * from pumpkins, planks from logs, ingots from blocks) takes up to {@code sets} at once: the whole stack, or that
     * many right-clicks; then one shift-click on the result crafts them all. Returns false if an ingredient ran out.
     */
    public boolean fill(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, boolean table, int sets) {
        int gridWidth = table ? 3 : 2;
        Inventory inventory = player.getInventory();
        if (width == 1 && height == 1 && sets > 1) {
            Predicate<ItemStack> ingredient = keys.values().iterator().next();
            int source = -1;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE && source == -1; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    source = slot;
                }
            }
            if (source == -1) {
                return false;
            }
            int sourceSlot = menuSlot(source, table);
            int have = inventory.getItem(source).getCount();
            mc.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.PICKUP, player);
            if (sets >= have) {
                // All of it into the grid
                mc.gameMode.handleContainerInput(menu.containerId, 1, 0, ContainerInput.PICKUP, player);
            } else {
                for (int i = 0; i < Math.min(sets, 64); i++) {
                    mc.gameMode.handleContainerInput(menu.containerId, 1, 1, ContainerInput.PICKUP, player);
                }
            }
            if (!menu.getCarried().isEmpty()) {
                mc.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.PICKUP, player);
            }
            AutoBuild.INSTANCE.inventory.moved();
            return true;
        }
        for (Map.Entry<Character, Predicate<ItemStack>> key : keys.entrySet()) {
            int needed = perSet().getOrDefault(key.getKey(), 0);
            if (needed == 0) {
                continue;
            }
            int source = -1;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE && source == -1; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && key.getValue().test(stack) && stack.getCount() >= needed) {
                    source = slot;
                }
            }
            if (source == -1) {
                return false;
            }
            int sourceSlot = menuSlot(source, table);
            mc.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.PICKUP, player);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    if (rows[row].length() > col && rows[row].charAt(col) == key.getKey()) {
                        int gridSlot = 1 + row * gridWidth + col;
                        // Right click: one item from the stack on the cursor
                        mc.gameMode.handleContainerInput(menu.containerId, gridSlot, 1, ContainerInput.PICKUP, player);
                    }
                }
            }
            // The rest back where it came from
            if (!menu.getCarried().isEmpty()) {
                mc.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.PICKUP, player);
            }
        }
        AutoBuild.INSTANCE.inventory.moved();
        return true;
    }

    /** The menu slot of an inventory slot: the player's own menu, or the crafting table's. */
    private static int menuSlot(int inventorySlot, boolean table) {
        if (table) {
            return inventorySlot < 9 ? 37 + inventorySlot : inventorySlot + 1;
        }
        return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
    }
}
