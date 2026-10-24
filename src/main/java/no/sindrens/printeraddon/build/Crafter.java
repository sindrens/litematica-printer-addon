package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Crafts items like a player clicking a recipe in the recipe book: the server puts the ingredients in the grid, the
 * result is shift-clicked out. Small recipes (ingots from blocks, sticks, planks, seeds from pumpkins) use the
 * inventory's 2x2 grid; rails and powered rails need a crafting table: one within reach, or one from the inventory
 * that's placed next to the player and broken again afterwards. A job crafts until it has enough or runs out of
 * ingredients.
 */
public class Crafter {
    private static final int WAIT = 6;
    private static final int TIMEOUT = 60;

    private enum Phase { FIND, COMMAND, PLACE_TABLE, OPEN_TABLE, WAIT_OPEN, FILL, TAKE, CLEAR, CLOSE }

    /** The craft command didn't open anything (no permission): not used again this session. */
    private static boolean craftCommandBroken;
    private boolean usedCommand;

    /** A crafting table can be opened anywhere with the server's craft command (switched on, and it works). */
    public static boolean craftCommand() {
        return no.sindrens.printeraddon.config.Configs.Shop.USE_CRAFT_COMMAND.getBooleanValue() && !craftCommandBroken;
    }

    /** Where a recipe that needs a crafting table is done: one in reach, else the craft command, else one placed. */
    private Phase tablePhase(Minecraft mc) {
        table = findTable(mc);
        placedTable = false;
        usedCommand = false;
        return table != null ? Phase.OPEN_TABLE : craftCommand() ? Phase.COMMAND : Phase.PLACE_TABLE;
    }

    /** Recipe clicks per fill (one set each); more sets get crafted in the next round. */
    private static final int MAX_SETS_PER_FILL = 16;

    private record Job(Item item, int count) {
    }

    private final Deque<Job> jobs = new ArrayDeque<>();
    @Nullable
    private Job job;
    private Phase phase;
    @Nullable
    private RecipeDisplayEntry recipe;
    private boolean needsTable;
    @Nullable
    private ManualRecipe manual;
    private int findTries;
    @Nullable
    private BlockPos table;
    private boolean placedTable;
    private int wait;
    private int waited;
    private int startCount;
    private int crafted;
    /** The count when the result was last shift-clicked out, and how often in a row that changed nothing. */
    private int lastTakeCount = -1;
    private int stuckTakes;
    /** The last job ended because the inventory had no room for what was crafted (see needsRoom). */
    private boolean noRoom;
    /** The crafting table menu this job opened on the server (-1: none), closed there by its own number. */
    private int openedMenu = -1;

    /**
     * True once after a job ended for lack of room in the inventory: room is made (stacks into storage) before the
     * next try, and it doesn't count as "can't be crafted".
     */
    public boolean needsRoom() {
        boolean was = noRoom;
        noRoom = false;
        return was;
    }

    public boolean isBusy() {
        return job != null || !jobs.isEmpty();
    }

    /** What's being crafted, for the status line. */
    public String describe() {
        Job now = job != null ? job : jobs.peekFirst();
        return now == null ? "crafting" : "crafting " + now.count() + " " + new ItemStack(now.item()).getHoverName().getString();
    }

    public void reset() {
        jobs.clear();
        job = null;
    }

    /** Queues crafting {@code count} of {@code item} (ingredients must be in the inventory by the time it runs). */
    public void craft(Item item, int count) {
        if (count > 0) {
            jobs.add(new Job(item, count));
        }
    }

    /** Returns true while it's doing something (nothing else should touch the inventory then). */
    public boolean tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null) {
            reset();
            return false;
        }
        if (job == null) {
            job = jobs.pollFirst();
            if (job == null) {
                return false;
            }
            phase = Phase.FIND;
            findTries = 0;
            crafted = 0;
            startCount = count(player, job.item());
            lastTakeCount = -1;
            stuckTakes = 0;
            // A tool takes a slot of its own: none free, nothing is crafted (it was crafted over and over with the
            // server saying "Your inventory is full!", the grid never emptied)
            if (job.item().getDefaultMaxStackSize() == 1 && no.sindrens.printeraddon.build.InventoryCleaner.freeSlots(player.getInventory()) == 0) {
                noRoom = true;
                fail(mc, "no free slot for it, the inventory is full (making room first)");
                return true;
            }
        }
        if (wait > 0) {
            wait--;
            return true;
        }
        waited++;

        switch (phase) {
            case FIND -> {
                recipe = findRecipe(mc, job.item());
                manual = null;
                if (recipe == null) {
                    // Not in the recipe book (or not craftable from what's here yet): by hand, from the known recipe
                    manual = ManualRecipe.of(job.item());
                    if (manual != null && manual.canCraft(player.getInventory())) {
                        PrinterAddon.debug("No recipe book entry for {}: crafting it by hand", job.item());
                        needsTable = manual.width() > 2 || manual.height() > 2;
                        if (!needsTable) {
                            phase = Phase.FILL;
                            return true;
                        }
                        phase = tablePhase(mc);
                        return true;
                    }
                    // The recipe book or the inventory may still be updating after a purchase: a few more tries
                    if (findTries++ < 3) {
                        wait = 20 + 2 * ServerLag.pingTicks(mc);
                        return true;
                    }
                    fail(mc, manual == null ? "no recipe for it in your recipe book" : "the ingredients aren't in the inventory");
                    return true;
                }
                needsTable = !fitsInInventoryGrid(recipe.display());
                if (!needsTable) {
                    phase = Phase.FILL;
                    return true;
                }
                phase = tablePhase(mc);
            }
            case COMMAND -> {
                player.connection.sendCommand(no.sindrens.printeraddon.config.Configs.Shop.CRAFT_COMMAND.getStringValue());
                usedCommand = true;
                waited = 0;
                phase = Phase.WAIT_OPEN;
            }
            case PLACE_TABLE -> {
                BlockPos spot = tableSpot(mc);
                int slot = player.getInventory().findSlotMatchingItem(new ItemStack(Items.CRAFTING_TABLE));
                if (spot == null || slot == -1) {
                    fail(mc, slot == -1 ? "it needs a crafting table (in the inventory or within reach)" : "no free spot next to you for a crafting table");
                    return true;
                }
                select(mc, slot, Items.CRAFTING_TABLE);
                BlockPos below = spot.below();
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
                table = spot;
                placedTable = true;
                wait = WAIT;
                phase = Phase.OPEN_TABLE;
            }
            case OPEN_TABLE -> {
                if (mc.level.getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
                    if (waited > TIMEOUT) {
                        fail(mc, "the crafting table didn't appear");
                    }
                    return true;
                }
                // Not sneaking, so the click opens it
                player.setShiftKeyDown(false);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(table).add(0, 0.5, 0), Direction.UP, table, false));
                waited = 0;
                phase = Phase.WAIT_OPEN;
            }
            case WAIT_OPEN -> {
                if (player.containerMenu instanceof CraftingMenu) {
                    openedMenu = player.containerMenu.containerId;
                    PrinterAddon.debug("Crafting table open for {} (menu {})", job.item(), openedMenu);
                    phase = Phase.FILL;
                } else if (waited > TIMEOUT + 2 * ServerLag.pingTicks(mc)) {
                    if (usedCommand) {
                        // No permission (or no such command): a crafting table from now on
                        craftCommandBroken = true;
                        Chat.warn("/" + no.sindrens.printeraddon.config.Configs.Shop.CRAFT_COMMAND.getStringValue()
                                + " didn't open a crafting table: using a crafting table instead (switch 'Craft with /craft' off to skip this).");
                        BuildLog.line("CRAFT", "the craft command didn't open a crafting table: not used again this session");
                        if (mc.gui.screen() != null) {
                            player.closeContainer();
                        }
                        phase = tablePhase(mc);
                        waited = 0;
                        return true;
                    }
                    fail(mc, "the crafting table didn't open");
                }
            }
            case FILL -> {
                AbstractContainerMenu menu = needsTable ? player.containerMenu : player.inventoryMenu;
                if (needsTable && !(menu instanceof CraftingMenu)) {
                    fail(mc, "the crafting table closed");
                    return true;
                }
                if (manual != null) {
                    // By hand: sets into the grid (a recipe of one item takes up to a stack at once), the result is
                    // taken, again until there are enough
                    int left = job.count() - (count(player, job.item()) - startCount);
                    int sets = Math.max(1, (left + manual.count() - 1) / manual.count());
                    if (!manual.fill(mc, player, menu, needsTable, sets)) {
                        fail(mc, "the ingredients ran out");
                        return true;
                    }
                    wait = WAIT + ServerLag.pingTicks(mc);
                    waited = 0;
                    phase = Phase.TAKE;
                    return true;
                }
                // Only as many sets as still needed: each click on the recipe adds one more set to the grid
                // (filling it to the max would use up ingredients that later jobs need, e.g. planks for the table)
                int perCraft = Math.max(1, recipe.resultItems(SlotDisplayContext.fromLevel(mc.level)).stream()
                        .filter(stack -> stack.is(job.item())).mapToInt(ItemStack::getCount).findFirst().orElse(1));
                int left = job.count() - (count(player, job.item()) - startCount);
                int needed = (left + perCraft - 1) / perCraft;
                if (needed >= 64) {
                    // A stack's worth or more still to make (seeds for a whole farm): the grid filled up in one go,
                    // like a shift-click on the recipe, instead of a click per set
                    mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.id(), true);
                } else {
                    int sets = Math.min(MAX_SETS_PER_FILL, needed);
                    for (int i = 0; i < sets; i++) {
                        mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.id(), false);
                    }
                }
                wait = WAIT;
                waited = 0;
                phase = Phase.TAKE;
            }
            case TAKE -> {
                AbstractContainerMenu menu = needsTable ? player.containerMenu : player.inventoryMenu;
                if (menu.getSlot(0).getItem().isEmpty()) {
                    if (waited > TIMEOUT / 2) {
                        phase = Phase.CLEAR;
                    }
                    return true;
                }
                // The last shift-click took nothing (the result is still there): no room for it in the inventory
                int now = count(player, job.item());
                if (lastTakeCount >= 0 && now == lastTakeCount) {
                    if (++stuckTakes >= 2) {
                        noRoom = true;
                        fail(mc, "no room in the inventory for what was crafted (making room first)");
                        return true;
                    }
                } else {
                    stuckTakes = 0;
                }
                lastTakeCount = now;
                mc.gameMode.handleContainerInput(menu.containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
                wait = WAIT;
                crafted = count(player, job.item()) - startCount;
                phase = crafted >= job.count() ? Phase.CLEAR : Phase.FILL;
            }
            case CLEAR -> {
                // Ingredients left in the grid go back to the inventory
                AbstractContainerMenu menu = needsTable ? player.containerMenu : player.inventoryMenu;
                int gridSlots = needsTable ? 9 : 4;
                for (int slot = 1; slot <= gridSlots && slot < menu.slots.size(); slot++) {
                    if (!menu.getSlot(slot).getItem().isEmpty()) {
                        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
                    }
                }
                wait = WAIT;
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                // Whatever the screen shows now: a crafting table still open on the server sends the inventory's
                // changes for its own menu, which the client then ignores (in a test the inventory looked unchanged
                // after items were cleared and given)
                closeTable(mc, player);
                crafted = count(player, job.item()) - startCount;
                Chat.quiet("Crafted " + crafted + " " + new ItemStack(job.item()).getHoverName().getString() + ".");
                finishJob();
            }
        }
        return true;
    }

    private void finishJob() {
        if (placedTable && table != null) {
            // Take the table down again
            AutoBuild.INSTANCE.airPlace.misplaced().add(table);
        }
        job = null;
        table = null;
        placedTable = false;
    }

    private void fail(Minecraft mc, String why) {
        Chat.warn("Can't craft " + new ItemStack(job.item()).getHoverName().getString() + ": " + why + ".");
        if (mc.player != null) {
            closeTable(mc, mc.player);
        }
        finishJob();
    }

    /**
     * Closes the crafting table this job opened, on the server too, by its own number: in a test the client had
     * already gone back to the inventory while the server still had the table open, and from then on the server
     * sent every inventory change for the table's menu, which the client ignored (the inventory looked unchanged
     * after items were cleared and given, and the add-on went by a wrong inventory).
     */
    private void closeTable(Minecraft mc, LocalPlayer player) {
        PrinterAddon.debug("Closing the crafting table: client menu {}, opened {}", player.containerMenu.containerId, openedMenu);
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        } else if (openedMenu > 0) {
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(openedMenu));
            if (mc.gui.screen() instanceof CraftingScreen) {
                mc.gui.setScreen(null);
            }
        }
        openedMenu = -1;
    }

    /** How many the inventory has (planks of any wood count as planks). */
    private static int count(LocalPlayer player, Item item) {
        if (!new ItemStack(item).is(net.minecraft.tags.ItemTags.PLANKS)) {
            return player.getInventory().countItem(item);
        }
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(net.minecraft.tags.ItemTags.PLANKS)) {
                total += player.getInventory().getItem(slot).getCount();
            }
        }
        return total;
    }

    /** True if there's a crafting table within reach. */
    public static boolean tableNearby(Minecraft mc) {
        return mc.player != null && findTable(mc) != null;
    }

    private static void select(Minecraft mc, int slot, Item item) {
        Inventory inventory = mc.player.getInventory();
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        } else {
            fi.dy.masa.litematica.util.InventoryUtils.setPickedItemToHand(slot, new ItemStack(item), mc);
        }
    }

    /** A recipe from the player's recipe book that makes the item and can be crafted with what's in the inventory. */
    @Nullable
    static RecipeDisplayEntry findRecipe(Minecraft mc, Item item) {
        StackedItemContents contents = new StackedItemContents();
        mc.player.getInventory().fillStackedContents(contents);
        var context = SlotDisplayContext.fromLevel(mc.level);
        RecipeDisplayEntry fallback = null;
        for (var collection : mc.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();
                if (!(display instanceof ShapedCraftingRecipeDisplay) && !(display instanceof ShapelessCraftingRecipeDisplay)) {
                    continue;
                }
                List<ItemStack> results = entry.resultItems(context);
                // Planks of any wood will do
                boolean matches = !results.isEmpty() && (results.getFirst().is(item)
                        || (new ItemStack(item).is(net.minecraft.tags.ItemTags.PLANKS) && results.getFirst().is(net.minecraft.tags.ItemTags.PLANKS)));
                if (!matches) {
                    continue;
                }
                if (entry.canCraft(contents)) {
                    return entry;
                }
                fallback = entry;
            }
        }
        if (fallback != null) {
            PrinterAddon.debug("Recipe for {} found, but the ingredients aren't all in the inventory", item);
        }
        return null;
    }

    private static boolean fitsInInventoryGrid(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() <= 2 && shaped.height() <= 2;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size() <= 4;
        }
        return false;
    }

    @Nullable
    private static BlockPos findTable(Minecraft mc) {
        BlockPos center = mc.player.blockPosition();
        double reach = mc.player.blockInteractionRange() - 0.5;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -3, -4), center.offset(4, 4, 4))) {
            if (mc.level.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE
                    && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** An empty spot next to the player, on something solid, outside the schematic, to put a crafting table. */
    @Nullable
    private static BlockPos tableSpot(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -2, -2), feet.offset(2, 1, 2))) {
            if (SchematicHelper.isInAnyArea(areas, pos) || pos.equals(feet) || pos.equals(feet.above())) {
                continue;
            }
            if (mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos.below()).isFaceSturdy(mc.level, pos.below(), Direction.UP)) {
                return pos.immutable();
            }
        }
        return null;
    }
}
