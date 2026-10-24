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
    private static final long KNOWN_FOR = 20 * 60 * 5;

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

    public boolean isBusy() {
        return current != null || !toVisit.isEmpty();
    }

    public void reset() {
        known.clear();
        wanted.clear();
        storing.clear();
        toVisit.clear();
        current = null;
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
     * Puts stacks of these items into storage (vaults first, then the nearest linked containers) until only the
     * given number of stacks of each is left in the inventory. Returns false if there is no storage.
     */
    public boolean startStore(Minecraft mc, Map<Item, Integer> items) {
        if (!Configs.Shop.FETCH_FROM_CHESTS.getBooleanValue() || mc.player == null || isBusy() || items.isEmpty()) {
            return false;
        }
        toVisit.clear();
        toVisit.addAll(spots(mc));
        sort(mc, toVisit);
        if (toVisit.isEmpty()) {
            return false;
        }
        wanted.clear();
        storing.clear();
        storing.putAll(items);
        taken = 0;
        return true;
    }

    private boolean anyLeftToStore(Inventory inventory) {
        Map<Item, Integer> stacks = stacks(inventory);
        return storing.entrySet().stream().anyMatch(e -> stacks.getOrDefault(e.getKey(), 0) > e.getValue());
    }

    private static Map<Item, Integer> stacks(Inventory inventory) {
        Map<Item, Integer> stacks = new HashMap<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
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
        Chat.info("Looking in " + toVisit.size() + " vault" + (toVisit.size() == 1 ? "" : "s") + "/chest"
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
                Chat.info("Put " + taken + " leftover items in storage.");
                storing.clear();
                toVisit.clear();
                mover.goTo(null);
                return false;
            }
            current = toVisit.removeFirst();
            phase = current.pos() == null ? Phase.OPEN : Phase.GO;
            waited = 0;
        } else if (current == null) {
            wanted.values().removeIf(count -> count <= 0);
            if (wanted.isEmpty() || toVisit.isEmpty()) {
                if (!toVisit.isEmpty() || taken > 0) {
                    Chat.info("Took " + taken + " items from storage.");
                }
                toVisit.clear();
                mover.goTo(null);
                return false;
            }
            current = toVisit.removeFirst();
            phase = current.pos() == null ? Phase.OPEN : Phase.GO;
            waited = 0;
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
                    if (waited > OPEN_TIMEOUT + 2 * ServerLag.pingTicks(mc)) {
                        PrinterAddon.debug("The {} didn't open", current.name());
                        current = null;
                    }
                    return true;
                }
                if (waited < 4 + ServerLag.pingTicks(mc)) {
                    // Let the contents arrive
                    return true;
                }
                AbstractContainerMenu menu = player.containerMenu;
                if (!storing.isEmpty()) {
                    Map<Item, Integer> stacks = stacks(player.getInventory());
                    for (Slot slot : menu.slots) {
                        ItemStack stack = slot.getItem();
                        Integer keep = stack.isEmpty() ? null : storing.get(stack.getItem());
                        if (slot.container instanceof Inventory && keep != null && stacks.getOrDefault(stack.getItem(), 0) > keep) {
                            taken += stack.getCount();
                            stacks.merge(stack.getItem(), -1, Integer::sum);
                            mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                        }
                    }
                    // Read again the next time it's opened
                    known.remove(current);
                    phase = Phase.CLOSE;
                    return true;
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
                    contents.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    Integer want = wanted.get(stack.getItem());
                    if (want != null && want > 0 && ShopBuyer.room(mc, stack.getItem()) > 0) {
                        mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                        wanted.put(stack.getItem(), want - stack.getCount());
                        contents.merge(stack.getItem(), -stack.getCount(), Integer::sum);
                        taken += stack.getCount();
                    }
                }
                known.put(current, new Known(contents, AutoBuild.clientTicks));
                PrinterAddon.debug("{}: took what was wanted, {} left wanted", current.name(), wanted);
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

    /** Vaults first (no flying), then the containers nearest first. */
    private static void sort(Minecraft mc, List<Spot> spots) {
        Vec3 from = mc.player.position();
        spots.sort(Comparator.comparingDouble(s -> s.pos() == null ? -1000 + s.vault() : from.distanceToSqr(Vec3.atCenterOf(s.pos()))));
    }

    /** The vaults from the settings and the linked containers (see {@link ChestLinks}) that are loaded. */
    private static List<Spot> spots(Minecraft mc) {
        List<Spot> found = new ArrayList<>();
        for (int vault : vaults()) {
            found.add(new Spot(null, vault));
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
        items.forEach((item, count) -> parts.add(count + " " + new ItemStack(item).getHoverName().getString()));
        return String.join(", ", parts);
    }
}
