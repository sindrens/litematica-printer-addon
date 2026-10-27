package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.shop.ShopBuyer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Makes room in the inventory when it's (almost) full: puts leftover building materials of this schematic that
 * no block still needs into storage (vaults, linked chests); when that's full (or there is none), sells in the shop
 * what it buys back, keeps the rest, and drops only when the build is stuck for room. Junk from breaking (cactus
 * flowers) is thrown out, and blocks the build doesn't use go into a vault when a supply trip is short of room.
 * Never touches anything else (tools, food, valuables, renamed or enchanted items, items the build still needs).
 */
public class InventoryCleaner {
    /** Below this many free slots, leftovers are dropped. */
    private static final int MIN_FREE_SLOTS = 3;
    private static final int COOLDOWN = 10;
    /** Rails kept beyond what the schematic needs, for temporary rails. */
    private static final int SPARE_RAILS = 16;

    /** Every item this schematic has needed during the build. */
    private final Set<Item> materials = new HashSet<>();
    private int cooldown;
    /** Ticks since leftovers were last put in chests: if the inventory is still full soon after, they're full. */
    private int sinceStored = Integer.MAX_VALUE;
    /** Ticks since leftovers were last sold (at most once in 5 minutes). */
    private int sinceSold = Integer.MAX_VALUE;

    /** Leftovers of crafting (what hoes, rails and seeds are made of) are put away too, unless needed now. */
    private static final Set<Item> CRAFTING_LEFTOVERS = Set.of(Items.DIAMOND, Items.DIAMOND_BLOCK, Items.STICK,
            Items.IRON_INGOT, Items.IRON_BLOCK, Items.GOLD_INGOT, Items.GOLD_BLOCK, Items.REDSTONE_BLOCK, Items.OAK_LOG,
            Items.OAK_PLANKS, Items.PUMPKIN, Items.COBBLESTONE);

    /**
     * Building materials and crafting leftovers in the inventory that the current layer doesn't need (also not to
     * craft something it needs): what may go into storage when the layer's items need the room. Never tools, food or
     * anything else.
     */
    public Set<Item> storable(Inventory inventory) {
        return offLayer(inventory);
    }

    /**
     * Building materials and crafting leftovers in the inventory that the current layer doesn't need (also not to
     * craft something it needs). Never tools, food or anything else. Blocks of this schematic count as its materials
     * also when nothing needs them any more (left over from a finished layer, after a restart).
     */
    private Set<Item> offLayer(Inventory inventory) {
        Map<Item, Integer> layerNeeds = AutoBuild.INSTANCE.layers.layerNeeds();
        Set<Item> keep = new HashSet<>(layerNeeds.keySet());
        if (layerNeeds.containsKey(Items.PUMPKIN_SEEDS)) {
            keep.add(Items.PUMPKIN);
        }
        // What tools are made of stays while one is needed (this layer, or the build without a plain one of the kind)
        boolean hoeNeeded = false;
        for (Item kind : new Item[]{Tools.HOE, Tools.PICKAXE, Tools.SHOVEL, Tools.AXE}) {
            hoeNeeded |= layerNeeds.containsKey(kind) || AutoBuild.INSTANCE.wholeNeeds(kind);
        }
        if (layerNeeds.containsKey(Items.RAIL) || layerNeeds.containsKey(Items.POWERED_RAIL)) {
            keep.addAll(Set.of(Items.IRON_INGOT, Items.IRON_BLOCK, Items.GOLD_INGOT, Items.GOLD_BLOCK, Items.STICK,
                    Items.REDSTONE, Items.REDSTONE_BLOCK, Items.OAK_PLANKS, Items.OAK_LOG));
        }
        Set<Item> off = new HashSet<>();
        Set<Item> special = new HashSet<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Item item = stack.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            // Never buckets (an infinite water bucket was put in a vault once, and the layer's water waited for it),
            // nor anything renamed, enchanted or otherwise special: such an item and every stack of its kind stay
            if (isBucket(item) || !plain(stack)) {
                special.add(item);
                continue;
            }
            if (hoeNeeded && Tools.hoeMaterial(item)) {
                continue;
            }
            if (!stack.isDamageableItem() && !keep.contains(item)
                    && (materials.contains(item) || CRAFTING_LEFTOVERS.contains(item) || isSchematicBlock(item))) {
                off.add(item);
            }
        }
        off.removeAll(special);
        return off;
    }

    /**
     * Junk: what breaking the "always break" blocks gives when the schematic has none of them (cactus flowers on the
     * farm). 311 of them filled 5 slots in one run, so every purchase was 5 stacks smaller, and the shop doesn't buy
     * them. Thrown out while a vault is open on a supply trip (see ChestFetcher), or sold when the shop buys them back.
     */
    public static boolean isJunk(ItemStack stack) {
        return !stack.isEmpty() && plain(stack) && stack.getItem() instanceof BlockItem block
                && BlockBreaker.alwaysBreak(block.getBlock().defaultBlockState())
                && !AutoBuild.INSTANCE.schematicBlocks.contains(block.getBlock());
    }

    /** Junk the shop buys back, with leftovers to be sold: kept for selling, not thrown out. */
    private static boolean sellsJunk(Minecraft mc, Item item) {
        return Configs.Shop.SELL_LEFTOVERS.getBooleanValue() && ShopBuyer.sellPrice(mc, item) != null;
    }

    /**
     * Throws the junk out of the inventory part of an open vault or chest, a stack a click, as a player would (ctrl+Q
     * over it). Returns how many items went.
     */
    public static int throwJunk(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, String where) {
        if (mc.gameMode == null || !menu.getCarried().isEmpty()) {
            return 0;
        }
        Map<Item, Integer> thrown = new LinkedHashMap<>();
        int items = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (slot.container instanceof Inventory && isJunk(stack) && !sellsJunk(mc, stack.getItem())) {
                thrown.merge(stack.getItem(), stack.getCount(), Integer::sum);
                items += stack.getCount();
                mc.gameMode.handleContainerInput(menu.containerId, slot.index, 1, ContainerInput.THROW, player);
            }
        }
        if (items > 0) {
            BuildLog.line("JUNK", "threw out " + describe(thrown) + " while " + where + " was open (from breaking; the shop doesn't buy it back)");
            RunLog.junkThrown(items);
        }
        return items;
    }

    /**
     * A block the build doesn't use at all (your own iron bars, concrete and signs: 3 slots in a run where each slot
     * was a stack less in every purchase). Only ordinary stackable blocks: never junk, food, buckets, tools, rare,
     * renamed or enchanted items, nor anything the build or the add-on's crafting uses.
     */
    public boolean isStray(ItemStack stack) {
        Item item = stack.getItem();
        return !stack.isEmpty() && plain(stack) && item instanceof BlockItem block && stack.getMaxStackSize() > 1
                && stack.getRarity() == Rarity.COMMON && !stack.has(DataComponents.FOOD) && !isJunk(stack) && !isBucket(item)
                && !materials.contains(item) && !CRAFTING_LEFTOVERS.contains(item) && !Tools.hoeMaterial(item)
                && !AutoBuild.INSTANCE.schematicBlocks.contains(block.getBlock())
                && !AutoBuild.INSTANCE.layers.layerNeeds().containsKey(item);
    }

    /** How many slots of the inventory hold strays (see isStray): room once they're put into a vault. */
    public int strayStacks(Inventory inventory) {
        int stacks = 0;
        for (int i = 0; i < 36; i++) {
            if (isStray(inventory.getItem(i))) {
                stacks++;
            }
        }
        return stacks;
    }

    /**
     * Puts the strays (see isStray) into the open vault or chest while it keeps its free part (see StockUp#keepFree).
     * Returns what was clicked in (checked against the server's answer by the caller).
     */
    public Map<Item, Integer> storeStrays(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, String where) {
        if (mc.gameMode == null || !menu.getCarried().isEmpty()) {
            return Map.of();
        }
        int free = 0;
        int total = 0;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                total++;
                if (slot.getItem().isEmpty()) {
                    free++;
                }
            }
        }
        int room = free - StockUp.keepFree(total);
        Map<Item, Integer> stored = new LinkedHashMap<>();
        int items = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (room <= 0) {
                break;
            }
            if (slot.container instanceof Inventory && isStray(stack)) {
                stored.merge(stack.getItem(), stack.getCount(), Integer::sum);
                items += stack.getCount();
                room--;
                mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
        if (items > 0) {
            BuildLog.line("STORAGE", where + ": putting in " + describe(stored) + " (blocks this build doesn't use: room for the trip)");
            no.sindrens.printeraddon.util.Chat.quiet("Put " + describe(stored) + " in " + where + ": this build doesn't use them,"
                    + " and the supply trip needs the room.");
            RunLog.straysStored(items);
        }
        return stored;
    }

    private static String describe(Map<Item, Integer> items) {
        StringBuilder sb = new StringBuilder();
        items.forEach((item, count) -> sb.append(sb.length() == 0 ? "" : ", ").append(count).append(' ')
                .append(new ItemStack(item).getHoverName().getString()));
        return sb.toString();
    }

    /** A bucket of any kind (water, lava, empty...): kept, and never a reason to go shopping. */
    public static boolean isBucket(Item item) {
        return item == Items.BUCKET || item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET || item == Items.POWDER_SNOW_BUCKET
                || item == Items.MILK_BUCKET;
    }

    /** An ordinary item, as it comes from the shop: not renamed, enchanted or otherwise changed. */
    public static boolean plain(ItemStack stack) {
        return stack.getComponents().equals(new ItemStack(stack.getItem()).getComponents());
    }

    /** As plain, but a tool may be worn (a hoe the add-on made and used): only renamed, enchanted or otherwise changed isn't. */
    public static boolean ordinary(ItemStack stack) {
        for (var entry : stack.getComponentsPatch().entrySet()) {
            if (entry.getKey() != net.minecraft.core.component.DataComponents.DAMAGE) {
                return false;
            }
        }
        return true;
    }

    /**
     * When auto build stops (by the player, or the build is done): what goes into storage. The building materials of
     * this schematic, what the add-on crafts with, and ordinary hoes; never buckets, food, armor, weapons, nor anything
     * renamed or enchanted (those stacks stay, see ChestFetcher).
     */
    public Set<Item> putAway(Inventory inventory) {
        Set<Item> items = new HashSet<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Item item = stack.getItem();
            if (stack.isEmpty() || isBucket(item) || !ordinary(stack)) {
                continue;
            }
            if (Tools.addonTool(stack) || !stack.isDamageableItem() && (materials.contains(item)
                    || CRAFTING_LEFTOVERS.contains(item) || isSchematicBlock(item) || Tools.hoeMaterial(item))) {
                items.add(item);
            }
        }
        return items;
    }

    private static boolean isSchematicBlock(Item item) {
        return item instanceof net.minecraft.world.item.BlockItem block
                && AutoBuild.INSTANCE.schematicBlocks.contains(block.getBlock());
    }

    public void reset() {
        materials.clear();
        cooldown = 0;
        sinceStored = Integer.MAX_VALUE;
        sinceSold = Integer.MAX_VALUE;
    }

    /** Called with what the whole schematic still needs (item -> count). */
    public void remember(Map<Item, Integer> neededWhole) {
        materials.addAll(neededWhole.keySet());
    }

    /** Returns true if it dropped something this tick. */
    public boolean tick(Minecraft mc, Map<Item, Integer> neededWhole, ChestFetcher fetcher) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || player.getAbilities().instabuild) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (sinceStored < Integer.MAX_VALUE) {
            sinceStored++;
        }
        if (sinceSold < Integer.MAX_VALUE) {
            sinceSold++;
        }
        Inventory inventory = player.getInventory();
        if (protectWornTools(mc, inventory, fetcher)) {
            return true;
        }
        if (junk(mc, player, inventory)) {
            return true;
        }
        // A new layer doesn't empty the inventory by itself: what the layer doesn't need stays (the next layer may
        // need it) until the layer's own items need the room, then it goes into storage (see InventoryBudget)
        if (freeSlots(inventory) >= MIN_FREE_SLOTS) {
            return false;
        }
        // Full: with storage, everything this layer doesn't need goes there first
        if (sinceStored > 20 * 60 && ChestFetcher.hasStorage(mc)) {
            Set<Item> offLayer = offLayer(inventory);
            if (!offLayer.isEmpty()) {
                sinceStored = 0;
                if (fetcher.startStore(mc, offLayer)) {
                    PrinterAddon.debug("Inventory full: putting what this layer doesn't need in storage: {}", offLayer);
                    return true;
                }
            }
        }

        Set<Item> leftovers = new HashSet<>();
        Set<Item> sellable = new HashSet<>();
        Map<Item, Boolean> shopBuysIt = new java.util.HashMap<>();
        int dropSlot = -1;
        boolean store = sinceStored > 20 * 60;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            // Only plain building materials of this schematic: never tools, nor renamed or enchanted items
            if (stack.isEmpty() || !materials.contains(stack.getItem()) || stack.isDamageableItem() || !plain(stack)
                    || isBucket(stack.getItem())) {
                continue;
            }
            Item item = stack.getItem();
            // Never anything the layers being built still need
            if (AutoBuild.INSTANCE.layers.layerNeeds().containsKey(item)) {
                continue;
            }
            // Until the whole schematic has been read once, what later layers need isn't known: nothing is dropped
            if (!store && !AutoBuild.INSTANCE.wholeKnown()) {
                continue;
            }
            // Spare rails only while the schematic still has rails to place (for temporary rails)
            boolean railsLeft = neededWhole.keySet().stream().anyMatch(i -> new ItemStack(i).is(net.minecraft.tags.ItemTags.RAILS));
            int keep = neededWhole.getOrDefault(item, 0) + (item == Items.RAIL && railsLeft ? SPARE_RAILS : 0);
            if (inventory.countItem(item) - stack.getCount() < keep) {
                continue;
            }
            if (store) {
                leftovers.add(item);
                continue;
            }
            // Storage is full (or there is none): what the shop buys back is sold ("sell all": only when the build
            // needs none of it), the rest stays. Only blocks the schematic itself places (redstone dust, hoppers),
            // never what was only crafted with (your own diamonds, ingots)
            if (!isSchematicBlock(item)) {
                continue;
            }
            if (keep == 0 && shopBuysIt.computeIfAbsent(item, i -> no.sindrens.printeraddon.shop.ShopBuyer.sellPrice(mc, i) != null)) {
                sellable.add(item);
            }
            if (dropSlot < 0) {
                dropSlot = slot;
            }
        }
        if (!store && !sellable.isEmpty() && sinceSold > 20 * 60 * 5
                && no.sindrens.printeraddon.config.Configs.Shop.SELL_LEFTOVERS.getBooleanValue()) {
            sinceSold = 0;
            if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.startSelling(mc, sellable)) {
                PrinterAddon.debug("Storage full: selling what the build doesn't need: {}", sellable);
                return true;
            }
        }
        if (!store && dropSlot >= 0 && AutoBuild.INSTANCE.layers.stalledFor(20 * 30)) {
            // Stuck for room with nothing to store or sell: dropped, the last way out (through the player's own
            // inventory menu, no screen needed)
            ItemStack stack = inventory.getItem(dropSlot);
            BuildLog.line("DROP", stack.getCount() + " " + stack.getHoverName().getString()
                    + ": the build is stuck for room, storage is full and the shop doesn't buy them back");
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, dropSlot < 9 ? 36 + dropSlot : dropSlot, 1,
                    ContainerInput.THROW, player);
            cooldown = COOLDOWN;
            return true;
        }
        if (store && leftovers.isEmpty() && makeRoomForMissing(mc, inventory, fetcher)) {
            return true;
        }
        if (store && !leftovers.isEmpty()) {
            sinceStored = 0;
            if (fetcher.startStore(mc, leftovers)) {
                PrinterAddon.debug("Putting leftovers in the linked chests: {}", leftovers);
                return true;
            }
            // No linked chest: they're dropped on the next ticks
        }
        return false;
    }

    /**
     * Junk (see isJunk) once it fills 2 slots, or room runs short: sold when the shop buys it back; else thrown out while
     * the player stands still, when there's no storage to throw it out at (see ChestFetcher) or no trip came by in time
     * (3 slots of it).
     */
    private boolean junk(Minecraft mc, LocalPlayer player, Inventory inventory) {
        int slots = 0;
        int first = -1;
        Set<Item> sell = new HashSet<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isJunk(stack)) {
                slots++;
                if (sellsJunk(mc, stack.getItem())) {
                    sell.add(stack.getItem());
                } else if (first < 0) {
                    first = slot;
                }
            }
        }
        if (slots < 2 && (slots == 0 || freeSlots(inventory) >= MIN_FREE_SLOTS)) {
            return false;
        }
        if (!sell.isEmpty() && sinceSold > 20 * 60 * 5) {
            sinceSold = 0;
            if (ShopBuyer.INSTANCE.startSelling(mc, sell)) {
                BuildLog.line("JUNK", "selling " + sell.stream().map(i -> new ItemStack(i).getHoverName().getString()).toList()
                        + " (from breaking; the shop buys it back)");
                return true;
            }
        }
        boolean storage = ChestFetcher.hasStorage(mc);
        if (first >= 0 && (!storage || slots >= 3) && player.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4) {
            ItemStack stack = inventory.getItem(first);
            BuildLog.line("JUNK", "threw out " + stack.getCount() + " " + stack.getHoverName().getString() + " (from breaking; "
                    + (storage ? slots + " slots of it before a trip came by" : "no vault or chest to do it at")
                    + ", the shop doesn't buy it back)");
            RunLog.junkThrown(stack.getCount());
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, first < 9 ? 36 + first : first, 1,
                    ContainerInput.THROW, player);
            cooldown = COOLDOWN;
            return true;
        }
        return false;
    }

    /** When a worn special tool was last put away, and when its wear was last said. */
    private long wornStoredAt = -100000;
    private long wornWarnedAt = -100000;

    /**
     * A special hoe of the player's (enchanted, renamed) that's nearly worn out: put into storage before the printer
     * uses it up (an ordinary one is used until it breaks; the add-on makes a new one, see Tools). Without storage, said.
     */
    private boolean protectWornTools(Minecraft mc, Inventory inventory, ChestFetcher fetcher) {
        ItemStack worn = ItemStack.EMPTY;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && worn.isEmpty(); slot++) {
            if (Tools.wornSpecial(inventory.getItem(slot))) {
                worn = inventory.getItem(slot);
            }
        }
        if (worn.isEmpty()) {
            return false;
        }
        String name = worn.getHoverName().getString();
        if (ChestFetcher.hasStorage(mc) && AutoBuild.clientTicks - wornStoredAt > 20 * 60) {
            wornStoredAt = AutoBuild.clientTicks;
            if (fetcher.startStoreWorn(mc)) {
                no.sindrens.printeraddon.util.Chat.info("Putting your " + name + " in storage before it breaks (" + Tools.left(worn)
                        + " uses left); the add-on uses a hoe of its own.");
                BuildLog.line("TOOLS", name + " has " + Tools.left(worn) + " uses left: put in storage before it breaks");
                return true;
            }
        }
        if (AutoBuild.clientTicks - wornWarnedAt > 20 * 60 * 10) {
            wornWarnedAt = AutoBuild.clientTicks;
            no.sindrens.printeraddon.util.Chat.warn("Your " + name + " has " + Tools.left(worn) + " uses left and the printer may use it"
                    + " up: take it out of your inventory (the add-on gets a hoe of its own).");
        }
        return false;
    }

    /**
     * Full of things the layer needs while it also needs something that isn't here at all (and can't be bought for
     * lack of room): the item with the most stacks gives up two, to the linked chests or else on the ground.
     */
    private boolean makeRoomForMissing(Minecraft mc, Inventory inventory, ChestFetcher fetcher) {
        if (freeSlots(inventory) > 1) {
            return false;
        }
        Map<Item, Integer> layerNeeds = AutoBuild.INSTANCE.layers.layerNeeds();
        // Only when the layer is really stuck on it, and it can be got (the shop sells it or a chest is linked)
        if (!AutoBuild.INSTANCE.layers.stalledFor(20 * 30)) {
            return false;
        }
        boolean chests = ChestFetcher.hasStorage(mc);
        Item missing = layerNeeds.keySet().stream().filter(item -> inventory.countItem(item) == 0)
                .filter(item -> chests || (no.sindrens.printeraddon.config.Configs.Shop.AUTO_BUY.getBooleanValue()
                        && no.sindrens.printeraddon.shop.ShopBuyer.sells(mc, item)))
                .findFirst().orElse(null);
        if (missing == null) {
            return false;
        }
        Map<Item, Integer> stacks = new java.util.HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !stack.isDamageableItem() && layerNeeds.containsKey(stack.getItem())) {
                stacks.merge(stack.getItem(), 1, Integer::sum);
            }
        }
        Item most = stacks.entrySet().stream().filter(e -> e.getValue() >= 3).max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (most == null) {
            return false;
        }
        String what = new ItemStack(most).getHoverName().getString();
        String forWhat = new ItemStack(missing).getHoverName().getString();
        sinceStored = 0;
        if (fetcher.startStore(mc, Map.of(most, stacks.get(most) - 2))) {
            no.sindrens.printeraddon.util.Chat.info("Inventory full: putting 2 stacks of " + what + " in your chest to make room for " + forWhat + ".");
            return true;
        }
        // No linked chest: drop one stack (smallest first), the next round the other
        int smallest = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(most) && (smallest == -1 || stack.getCount() < inventory.getItem(smallest).getCount())) {
                smallest = slot;
            }
        }
        no.sindrens.printeraddon.util.Chat.warn("Inventory full: dropping a stack of " + what + " to make room for " + forWhat
                + ". Link a chest (J+B) to keep extras instead.");
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, smallest < 9 ? 36 + smallest : smallest, 1,
                ContainerInput.THROW, mc.player);
        sinceStored = 20 * 60 + 1 - 20 * 5;
        cooldown = COOLDOWN;
        return true;
    }

    public static int freeSlots(Inventory inventory) {
        int free = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }
}
