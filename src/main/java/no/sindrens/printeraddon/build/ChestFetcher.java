package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.shop.ShopBuyer;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes missing items from storage before anything is bought, and puts leftovers the build no longer needs back:
 * the player's vaults (/pv 1, /pv 2... opened with the command, no flying) and the chests, barrels and shulker boxes
 * they linked (flown to). Remembers what's in each one so it doesn't open them again for nothing.
 */
public class ChestFetcher {
    private static final int TIMEOUT = 20 * 20;
    private static final int OPEN_TIMEOUT = 60;
    /** Ticks between closing a vault and opening the next (servers don't like menus opened back to back). */
    private static final int VAULT_GAP = 10;
    /** What's known about a storage spot is trusted this long (the player may fill it meanwhile). */
    /**
     * What a vault or chest has is trusted this long (only the add-on changes it during a build: any other menu is
     * closed): one that's known not to have what's wanted isn't opened (it was, three times in a log, for nothing).
     */
    private static final long KNOWN_FOR = 20 * 60 * 10;

    private enum Phase { GO, OPEN, TAKE, CLOSE }

    /** A linked container (pos) or a vault (number, opened with the vault command). */
    private record Spot(@Nullable BlockPos pos, int vault) {
        String name() {
            return pos != null ? "chest at " + pos.toShortString() : "/" + Configs.Shop.VAULT_COMMAND.getStringValue() + " " + vault;
        }
    }

    private record Known(Map<Item, Integer> contents, long at) {
    }

    private final Map<Spot, Known> known = new HashMap<>();
    /** Vaults that didn't open, until when they're left alone. */
    private static final Map<Integer, Long> vaultDown = new HashMap<>();
    private final Map<Item, Integer> wanted = new LinkedHashMap<>();
    private final List<Spot> toVisit = new ArrayList<>();
    /** Items being put away (instead of taken), with how many stacks of each stay in the inventory. */
    private final Map<Item, Integer> storing = new HashMap<>();
    @Nullable
    private Spot current;
    private Phase phase;
    private int waited;
    private int taken;
    private int gap;
    /** The current spot's menu was open at some point (closed again by the player: opened once more). */
    private boolean seenOpen;
    /** Opened again after it was closed, or after it didn't open: once per spot. */
    private boolean retried;
    /** Small stacks were put together in this spot's menu already; ticks left to wait for the server's answer. */
    private boolean mergedHere;
    private int mergeWait;

    /** A job (fetching or storing) runs, until its end is written down (see tick). */
    private boolean active;

    public boolean isBusy() {
        return active || current != null || !toVisit.isEmpty();
    }

    public void reset() {
        known.clear();
        wanted.clear();
        storing.clear();
        toVisit.clear();
        current = null;
        active = false;
        nearOnly = false;
        storeWorn = false;
    }

    /** True if storage had a hoe when last looked in (within the last few minutes). */
    public boolean knownHoe() {
        for (Known k : known.values()) {
            if (AutoBuild.clientTicks - k.at() <= KNOWN_FOR
                    && k.contents().keySet().stream().anyMatch(item -> Tools.isHoe(new ItemStack(item)))) {
                return true;
            }
        }
        return false;
    }

    /** How many of this item storage had when last looked in (within the last few minutes). */
    public int knownCount(Item item) {
        int count = 0;
        for (Known k : known.values()) {
            if (AutoBuild.clientTicks - k.at() <= KNOWN_FOR) {
                count += k.contents().getOrDefault(item, 0);
            }
        }
        return count;
    }

    /** What's being done, for the status line. */
    public String describe() {
        String where = current != null ? current.name() : "storage";
        return storing.isEmpty() ? "taking " + describe(wanted) + " from " + where : "putting items away in " + where;
    }

    /** The last time leftovers were put away, some didn't fit anywhere. */
    private boolean storageFull;
    /** Storing without flying anywhere (vaults, and linked chests within reach): when auto build has stopped. */
    private boolean nearOnly;
    /** Special tools about to break (an enchanted hoe) are put away too. */
    private boolean storeWorn;

    /** How full each storage spot was when last open (slots used, slots in all). */
    private record Fill(int used, int total) {
    }

    private static final Map<Spot, Fill> fill = new HashMap<>();
    private static long fullWarnedAt = -100000;
    private static int fullWarnedPercent;

    public boolean storageFull() {
        return storageFull;
    }

    /** True if there's anywhere to take from or store to (linked chests or vaults). */
    public static boolean hasStorage(Minecraft mc) {
        return Configs.Shop.FETCH_FROM_CHESTS.getBooleanValue() && !spots(mc).isEmpty();
    }

    /** Puts every stack of these items into storage. Returns false if there is none. */
    public boolean startStore(Minecraft mc, java.util.Set<Item> items) {
        Map<Item, Integer> keep = new HashMap<>();
        items.forEach(item -> keep.put(item, 0));
        return startStore(mc, keep);
    }

    /**
     * When auto build stops: every stack of these items into the vaults (and linked chests within reach, no flying).
     * Returns false if there's nowhere to put them.
     */
    public boolean startPutAway(Minecraft mc, java.util.Set<Item> items) {
        Map<Item, Integer> keep = new HashMap<>();
        items.forEach(item -> keep.put(item, 0));
        nearOnly = true;
        if (!startStore(mc, keep)) {
            nearOnly = false;
            return false;
        }
        return true;
    }

    /** Special tools about to break (an enchanted hoe the printer would use up) into storage. */
    public boolean startStoreWorn(Minecraft mc) {
        storeWorn = true;
        if (!startStore(mc, Map.of(Items.AIR, 0))) {
            storeWorn = false;
            return false;
        }
        return true;
    }

    /**
     * Puts stacks of these items into storage (vaults first, then the nearest linked containers) until only the
     * given number of stacks of each is left in the inventory. Returns false if there is no storage.
     */
    public boolean startStore(Minecraft mc, Map<Item, Integer> items) {
        if (!Configs.Shop.FETCH_FROM_CHESTS.getBooleanValue() || mc.player == null || isBusy() || items.isEmpty()) {
            return false;
        }
        toVisit.clear();
        toVisit.addAll(nearOnly ? nearSpots(mc) : spots(mc));
        sort(mc, toVisit);
        if (toVisit.isEmpty()) {
            return false;
        }
        wanted.clear();
        storing.clear();
        storing.putAll(items);
        taken = 0;
        active = true;
        return true;
    }

    private boolean anyLeftToStore(Inventory inventory) {
        Map<Item, Integer> stacks = stacks(inventory);
        if (storeWorn) {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (Tools.wornSpecial(inventory.getItem(slot))) {
                    return true;
                }
            }
        }
        return storing.entrySet().stream().anyMatch(e -> stacks.getOrDefault(e.getKey(), 0) > e.getValue());
    }

    /** Stacks per item that may be put away (ordinary ones, never buckets). */
    private static Map<Item, Integer> stacks(Inventory inventory) {
        Map<Item, Integer> stacks = new HashMap<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && InventoryCleaner.ordinary(stack) && !InventoryCleaner.isBucket(stack.getItem())) {
                stacks.merge(stack.getItem(), 1, Integer::sum);
            }
        }
        return stacks;
    }

    /**
     * Starts fetching (item -> how many). Returns false if no storage spot could have any of it.
     */
    public boolean start(Minecraft mc, Map<Item, Integer> list) {
        if (!Configs.Shop.FETCH_FROM_CHESTS.getBooleanValue() || mc.player == null || isBusy()) {
            return false;
        }
        // A finished store job leaves nothing behind that could be taken for this one
        storing.clear();
        wanted.clear();
        list.forEach((item, count) -> {
            int room = ShopBuyer.room(mc, item);
            if (room > 0) {
                wanted.put(item, Math.min(count, room));
            }
        });
        toVisit.clear();
        known.values().removeIf(k -> AutoBuild.clientTicks - k.at() > KNOWN_FOR);
        // Spots known to have something wanted first, then ones never opened (vaults first, then nearest chests)
        List<Spot> unknown = new ArrayList<>();
        for (Spot spot : spots(mc)) {
            Known k = known.get(spot);
            if (k == null) {
                unknown.add(spot);
            } else if (wanted.keySet().stream().anyMatch(item -> k.contents().getOrDefault(item, 0) > 0)) {
                toVisit.add(spot);
            }
        }
        sort(mc, toVisit);
        sort(mc, unknown);
        toVisit.addAll(unknown);
        if (wanted.isEmpty() || toVisit.isEmpty()) {
            toVisit.clear();
            return false;
        }
        taken = 0;
        active = true;
        Chat.quiet("Looking in " + toVisit.size() + " vault" + (toVisit.size() == 1 ? "" : "s") + "/chest"
                + (toVisit.size() == 1 ? "" : "s") + " for " + describe(wanted) + " before buying.");
        return true;
    }

    /** Returns true while it's doing something. */
    public boolean tick(Minecraft mc, Mover mover) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null) {
            reset();
            return false;
        }
        if (gap > 0) {
            gap--;
            return true;
        }
        if (current == null && !storing.isEmpty()) {
            if (!anyLeftToStore(player.getInventory()) || toVisit.isEmpty()) {
                // Everywhere visited and some still here: storage is full (they're sold or kept, see InventoryCleaner)
                storageFull = anyLeftToStore(player.getInventory());
                if (storageFull) {
                    BuildLog.lineOrKeep("STORAGE", "full: put " + taken + " items away, the rest didn't fit");
                    Chat.warn("Put " + taken + " items in storage; the rest didn't fit: " + storageNames(mc) + " "
                            + (vaults().size() + ChestLinks.list(mc).size() == 1 ? "is" : "are") + " full. Link a chest for the"
                            + " rest: look at a chest, press J+B and click 'Link the chest' (then 'Put building items away').");
                } else if (taken > 0) {
                    Chat.quiet("Put " + taken + " items in storage.");
                }
                warnIfFilling(mc);
                storing.clear();
                toVisit.clear();
                active = false;
                nearOnly = false;
                storeWorn = false;
                mover.goTo(null);
                return false;
            }
            current = toVisit.removeFirst();
            phase = current.pos() == null || nearOnly ? Phase.OPEN : Phase.GO;
            waited = 0;
            seenOpen = false;
            retried = false;
            mergedHere = false;
            mergeWait = 0;
        } else if (current == null) {
            wanted.values().removeIf(count -> count <= 0);
            if (wanted.isEmpty() || toVisit.isEmpty()) {
                if (!toVisit.isEmpty() || taken > 0) {
                    Chat.quiet("Took " + taken + " items from storage.");
                }
                toVisit.clear();
                active = false;
                mover.goTo(null);
                return false;
            }
            current = toVisit.removeFirst();
            phase = current.pos() == null ? Phase.OPEN : Phase.GO;
            waited = 0;
            seenOpen = false;
            retried = false;
            mergedHere = false;
            mergeWait = 0;
        }
        waited++;

        switch (phase) {
            case GO -> {
                BlockPos pos = current.pos();
                if (!(mc.level.getBlockEntity(pos) instanceof net.minecraft.world.Container)) {
                    known.remove(current);
                    current = null;
                    return true;
                }
                if (mover.goTo(pos)) {
                    phase = Phase.OPEN;
                    waited = 0;
                } else if (waited > TIMEOUT) {
                    PrinterAddon.debug("Couldn't get to the {}", current.name());
                    current = null;
                }
                mover.tick(mc);
            }
            case OPEN -> {
                player.setShiftKeyDown(false);
                BuildLog.line("STORAGE", "opening " + current.name() + (storing.isEmpty() ? " to take " + describe(wanted) : " to put away " + storing.keySet())
                        + (retried ? " (again)" : ""));
                if (current.pos() == null) {
                    PrinterAddon.debug("Opening {}", current.name());
                    player.connection.sendCommand(Configs.Shop.VAULT_COMMAND.getStringValue() + " " + current.vault());
                } else {
                    Vec3 hit = Vec3.atCenterOf(current.pos()).add(0, 0.5, 0);
                    mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, current.pos(), false));
                }
                phase = Phase.TAKE;
                waited = 0;
            }
            case TAKE -> {
                if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>) || player.containerMenu == player.inventoryMenu) {
                    if (seenOpen && !retried) {
                        // It was open and got closed before anything was taken (by the player, by accident): again
                        retried = true;
                        seenOpen = false;
                        BuildLog.line("STORAGE", current.name() + " was closed before it was done: opening it again");
                        phase = Phase.OPEN;
                        waited = 0;
                        gap = VAULT_GAP;
                        return true;
                    }
                    // A vault is loaded by the server the first time it's opened: that can take a few seconds
                    if (waited > OPEN_TIMEOUT + (current.pos() == null ? 60 : 0) + 2 * ServerLag.pingTicks(mc)) {
                        if (!retried) {
                            // The server may just have been slow, or the command got lost: once more
                            retried = true;
                            BuildLog.line("STORAGE", current.name() + " didn't open within " + waited + " ticks: trying once more");
                            phase = Phase.OPEN;
                            waited = 0;
                            gap = VAULT_GAP;
                            return true;
                        }
                        PrinterAddon.debug("The {} didn't open", current.name());
                        BuildLog.line("STORAGE", current.name() + " didn't open (twice)" + (current.pos() == null ? ": left alone for a minute" : ""));
                        if (current.pos() == null) {
                            // No such vault (or no /pv here, or the server was slow): not again for a minute
                            vaultDown.put(current.vault(), AutoBuild.clientTicks + 20L * 60);
                        }
                        current = null;
                    }
                    return true;
                }
                if (!seenOpen) {
                    seenOpen = true;
                    waited = 0;
                }
                if (waited < 4 + ServerLag.pingTicks(mc)) {
                    // Let the contents arrive
                    return true;
                }
                AbstractContainerMenu menu = player.containerMenu;
                // Small stacks put together while it's open (clicks with a menu open are an ordinary player's), and
                // checked against the server's answer before anything else changes the inventory
                if (!mergedHere) {
                    mergedHere = true;
                    if (StackMerge.inMenu(mc, player, menu, 8) > 0) {
                        mergeWait = 3 + 2 * ServerLag.pingTicks(mc);
                        return true;
                    }
                }
                if (mergeWait > 0) {
                    if (--mergeWait > 0) {
                        return true;
                    }
                    StackMerge.checkNow(player.getInventory());
                }
                if (!storing.isEmpty()) {
                    Map<Item, Integer> stacks = stacks(player.getInventory());
                    for (Slot slot : menu.slots) {
                        ItemStack stack = slot.getItem();
                        if (!(slot.container instanceof Inventory) || stack.isEmpty()) {
                            continue;
                        }
                        Integer keep = storing.get(stack.getItem());
                        // Never a bucket, nor anything renamed, enchanted or otherwise special (an infinite water bucket);
                        // a worn tool may be (an ordinary hoe the add-on made). Special tools about to break are put away
                        boolean store = keep != null && stacks.getOrDefault(stack.getItem(), 0) > keep
                                && InventoryCleaner.ordinary(stack) && !InventoryCleaner.isBucket(stack.getItem());
                        if (store || storeWorn && Tools.wornSpecial(stack)) {
                            taken += stack.getCount();
                            stacks.merge(stack.getItem(), -1, Integer::sum);
                            mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                        }
                    }
                    // What it has now (the clicks show at once): no need to open it again to find out
                    Map<Item, Integer> after = new HashMap<>();
                    for (Slot slot : menu.slots) {
                        if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) {
                            after.merge(slot.getItem().getItem(), slot.getItem().getCount(), Integer::sum);
                        }
                    }
                    known.put(current, new Known(after, AutoBuild.clientTicks));
                    noteFill(menu);
                    BuildLog.lineOrKeep("STORAGE", current.name() + ": put away, " + taken + " items so far" + fillText(current));
                    phase = Phase.CLOSE;
                    return true;
                }
                // A hoe is a hoe (a pickaxe a pickaxe...): of each kind wanted, the best plain one here (diamond
                // first, then the one with the most uses left; never the player's own enchanted or netherite ones)
                Map<Item, Integer> bestTool = new HashMap<>();
                for (Slot slot : menu.slots) {
                    ItemStack stack = slot.getItem();
                    Item kind = Tools.kindOf(stack);
                    if (slot.container instanceof Inventory || kind == null || wanted.getOrDefault(kind, 0) <= 0 || !Tools.usable(kind, stack)) {
                        continue;
                    }
                    Integer best = bestTool.get(kind);
                    ItemStack bestStack = best == null ? ItemStack.EMPTY : menu.slots.get(best).getItem();
                    if (best == null || Tools.rank(stack.getItem()) > Tools.rank(bestStack.getItem())
                            || Tools.rank(stack.getItem()) == Tools.rank(bestStack.getItem()) && Tools.left(stack) > Tools.left(bestStack)) {
                        bestTool.put(kind, slot.index);
                    }
                }
                Map<Item, Integer> contents = new HashMap<>();
                for (Slot slot : menu.slots) {
                    if (slot.container instanceof Inventory) {
                        continue;
                    }
                    ItemStack stack = slot.getItem();
                    if (stack.isEmpty()) {
                        continue;
                    }
                    // Read before the click: the moved stack is empty afterwards (and reads as air)
                    Item item = stack.getItem();
                    int count = stack.getCount();
                    contents.merge(item, count, Integer::sum);
                    // What it's wanted as: a tool as its kind (only the best one here), anything else as itself
                    Item kind = Tools.kindOf(stack);
                    boolean tool = kind != null && wanted.containsKey(kind);
                    Item key = tool ? kind : item;
                    Integer want = tool && !Integer.valueOf(slot.index).equals(bestTool.get(kind)) ? null : wanted.get(key);
                    // Never into the last few free slots (see InventoryBudget), unless it tops up a stack
                    boolean fits = InventoryCleaner.freeSlots(player.getInventory()) > InventoryBudget.KEEP_FREE
                            || InventoryBudget.topUpRoom(player.getInventory(), item) >= count;
                    if (want != null && want > 0 && fits && ShopBuyer.room(mc, item) > 0) {
                        mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                        wanted.put(key, want - count);
                        contents.merge(item, -count, Integer::sum);
                        taken += count;
                    }
                }
                known.put(current, new Known(contents, AutoBuild.clientTicks));
                noteFill(menu);
                PrinterAddon.debug("{}: took what was wanted, {} left wanted", current.name(), wanted);
                BuildLog.line("STORAGE", current.name() + ": " + taken + " taken so far | still wanted " + describe(wanted)
                        + " | left in it " + describe(contents) + fillText(current));
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                player.closeContainer();
                if (current.pos() == null) {
                    gap = VAULT_GAP;
                }
                current = null;
            }
        }
        return true;
    }

    /** How full the open vault or chest is now (after the clicks: the client shows them at once). */
    private void noteFill(AbstractContainerMenu menu) {
        int used = 0;
        int total = 0;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                total++;
                if (!slot.getItem().isEmpty()) {
                    used++;
                }
            }
        }
        if (total > 0 && current != null) {
            fill.put(current, new Fill(used, total));
        }
    }

    private static String fillText(@Nullable Spot spot) {
        Fill f = spot == null ? null : fill.get(spot);
        return f == null ? "" : " | " + f.used() + " of " + f.total() + " slots used";
    }

    /** Slots used and slots in all, over the storage looked in so far (null if none yet). */
    @Nullable
    public static int[] fillLevel() {
        int used = 0;
        int total = 0;
        for (Fill f : fill.values()) {
            used += f.used();
            total += f.total();
        }
        return total == 0 ? null : new int[]{used, total};
    }

    /** The player's storage getting full (80%, then 95%, then full): said, at most every 10 minutes for the same level. */
    private static void warnIfFilling(Minecraft mc) {
        int[] level = fillLevel();
        if (level == null) {
            return;
        }
        int percent = level[0] * 100 / level[1];
        int step = percent >= 100 ? 100 : percent >= 95 ? 95 : percent >= 80 ? 80 : 0;
        if (step == 0) {
            fullWarnedPercent = 0;
            return;
        }
        if (step > fullWarnedPercent || AutoBuild.clientTicks - fullWarnedAt > 20L * 60 * 10) {
            fullWarnedPercent = step;
            fullWarnedAt = AutoBuild.clientTicks;
            Chat.warn("Your storage is " + (step == 100 ? "full" : "getting full") + ": " + level[0] + " of " + level[1]
                    + " slots used (" + storageNames(mc) + "). Empty some, or link a chest (look at it, J+B, 'Link the chest').");
            BuildLog.lineOrKeep("STORAGE", "getting full: " + level[0] + " of " + level[1] + " slots used");
        }
    }

    /** "/pv 1, /pv 2 and 1 linked chest" for messages. */
    private static String storageNames(Minecraft mc) {
        List<String> names = new ArrayList<>();
        for (int vault : vaults()) {
            names.add("/" + Configs.Shop.VAULT_COMMAND.getStringValue() + " " + vault);
        }
        int chests = mc.player == null ? 0 : ChestLinks.list(mc).size();
        if (chests > 0) {
            names.add(chests + " linked chest" + (chests == 1 ? "" : "s"));
        }
        return names.isEmpty() ? "your storage" : String.join(", ", names);
    }

    /** Vaults, and linked containers within reach (nothing to fly to). */
    private static List<Spot> nearSpots(Minecraft mc) {
        List<Spot> near = new ArrayList<>();
        for (Spot spot : spots(mc)) {
            if (spot.pos() == null || mc.player != null
                    && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot.pos())) <= 4.5 * 4.5) {
                near.add(spot);
            }
        }
        return near;
    }

    /** Vaults first (no flying), then the containers nearest first. */
    private static void sort(Minecraft mc, List<Spot> spots) {
        Vec3 from = mc.player.position();
        spots.sort(Comparator.comparingDouble(s -> s.pos() == null ? -1000 + s.vault() : from.distanceToSqr(Vec3.atCenterOf(s.pos()))));
    }

    /** The vaults from the settings and the linked containers (see {@link ChestLinks}) that are loaded. */
    private static List<Spot> spots(Minecraft mc) {
        List<Spot> found = new ArrayList<>();
        for (int vault : vaults()) {
            Long down = vaultDown.get(vault);
            if (down == null || down < AutoBuild.clientTicks) {
                found.add(new Spot(null, vault));
            }
        }
        if (mc.level != null) {
            for (BlockPos pos : ChestLinks.list(mc)) {
                if (mc.level.isLoaded(pos) && mc.level.getBlockEntity(pos) instanceof net.minecraft.world.Container) {
                    found.add(new Spot(pos, 0));
                }
            }
        }
        return found;
    }

    /** The vault numbers from the setting: "1-6", "1,2,3", "1-3,5". */
    public static List<Integer> vaults() {
        List<Integer> vaults = new ArrayList<>();
        for (String part : Configs.Shop.VAULTS.getStringValue().split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            try {
                int dash = part.indexOf('-');
                if (dash > 0) {
                    int from = Integer.parseInt(part.substring(0, dash).trim());
                    int to = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int v = from; v <= Math.min(to, from + 99); v++) {
                        vaults.add(v);
                    }
                } else {
                    vaults.add(Integer.parseInt(part));
                }
            } catch (NumberFormatException e) {
                PrinterAddon.debug("Vault setting: '{}' is not a number", part);
            }
        }
        return vaults;
    }

    private static String describe(Map<Item, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.forEach((item, count) -> {
            if (count > 0) {
                parts.add(count + " " + new ItemStack(item).getHoverName().getString());
            }
        });
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }
}
