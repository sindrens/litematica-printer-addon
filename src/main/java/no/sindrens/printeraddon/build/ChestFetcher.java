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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.shop.ShopBuyer;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes missing items from the chests, barrels and shulker boxes the player linked before anything is bought, and
 * puts leftovers the build no longer needs back into them. Flies to a container, opens it, takes or stores, and
 * remembers what's in each one so it doesn't open them again for nothing.
 */
public class ChestFetcher {
    private static final int TIMEOUT = 20 * 20;
    private static final int OPEN_TIMEOUT = 40;

    private enum Phase { GO, OPEN, TAKE, CLOSE }

    /** What was in each container when it was last opened (item -> count). */
    private final Map<BlockPos, Map<Item, Integer>> known = new HashMap<>();
    private final Map<Item, Integer> wanted = new LinkedHashMap<>();
    private final List<BlockPos> toVisit = new ArrayList<>();
    /** Items being put away (instead of taken). */
    private final java.util.Set<Item> storing = new java.util.HashSet<>();
    @Nullable
    private BlockPos current;
    private Phase phase;
    private int waited;
    private int taken;

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

    /** Puts every stack of these items into the nearest linked containers. Returns false if there are none. */
    public boolean startStore(Minecraft mc, java.util.Set<Item> items) {
        if (!Configs.Shop.FETCH_FROM_CHESTS.getBooleanValue() || mc.player == null || isBusy() || items.isEmpty()) {
            return false;
        }
        toVisit.clear();
        toVisit.addAll(containersNearby(mc));
        Vec3 from = mc.player.position();
        toVisit.sort(java.util.Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));
        if (toVisit.isEmpty()) {
            return false;
        }
        wanted.clear();
        storing.clear();
        storing.addAll(items);
        taken = 0;
        return true;
    }

    private boolean anyLeftToStore(Inventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (storing.contains(inventory.getItem(slot).getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts fetching (item -> how many). Returns false if no container nearby could have any of it.
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
        // Containers known to have something wanted first, then ones never opened, nearest first
        List<BlockPos> unknown = new ArrayList<>();
        for (BlockPos pos : containersNearby(mc)) {
            Map<Item, Integer> contents = known.get(pos);
            if (contents == null) {
                unknown.add(pos);
            } else if (wanted.keySet().stream().anyMatch(item -> contents.getOrDefault(item, 0) > 0)) {
                toVisit.add(pos);
            }
        }
        Vec3 from = mc.player.position();
        toVisit.sort(java.util.Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));
        unknown.sort(java.util.Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));
        toVisit.addAll(unknown);
        if (wanted.isEmpty() || toVisit.isEmpty()) {
            toVisit.clear();
            return false;
        }
        taken = 0;
        Chat.info("Looking in " + toVisit.size() + " chest" + (toVisit.size() == 1 ? "" : "s") + " nearby for "
                + describe(wanted) + " before buying.");
        return true;
    }

    /** Returns true while it's doing something. */
    public boolean tick(Minecraft mc, Mover mover) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null) {
            reset();
            return false;
        }
        if (current == null && !storing.isEmpty()) {
            if (!anyLeftToStore(player.getInventory()) || toVisit.isEmpty()) {
                Chat.info("Put " + taken + " leftover items in your chests.");
                storing.clear();
                toVisit.clear();
                mover.goTo(null);
                return false;
            }
            current = toVisit.removeFirst();
            phase = Phase.GO;
            waited = 0;
        } else if (current == null) {
            wanted.values().removeIf(count -> count <= 0);
            if (wanted.isEmpty() || toVisit.isEmpty()) {
                if (!toVisit.isEmpty() || taken > 0) {
                    Chat.info("Took " + taken + " items from chests.");
                }
                toVisit.clear();
                mover.goTo(null);
                return false;
            }
            current = toVisit.removeFirst();
            phase = Phase.GO;
            waited = 0;
        }
        waited++;

        switch (phase) {
            case GO -> {
                if (!(mc.level.getBlockEntity(current) instanceof net.minecraft.world.Container)) {
                    known.remove(current);
                    current = null;
                    return true;
                }
                if (mover.goTo(current)) {
                    phase = Phase.OPEN;
                    waited = 0;
                } else if (waited > TIMEOUT) {
                    PrinterAddon.debug("Couldn't get to the chest at {}", current.toShortString());
                    current = null;
                }
                mover.tick(mc);
            }
            case OPEN -> {
                player.setShiftKeyDown(false);
                Vec3 hit = Vec3.atCenterOf(current).add(0, 0.5, 0);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, current, false));
                phase = Phase.TAKE;
                waited = 0;
            }
            case TAKE -> {
                if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || player.containerMenu == player.inventoryMenu) {
                    if (waited > OPEN_TIMEOUT) {
                        PrinterAddon.debug("The chest at {} didn't open", current.toShortString());
                        current = null;
                    }
                    return true;
                }
                if (waited < 4) {
                    // Let the contents arrive
                    return true;
                }
                AbstractContainerMenu menu = player.containerMenu;
                if (!storing.isEmpty()) {
                    for (Slot slot : menu.slots) {
                        ItemStack stack = slot.getItem();
                        if (slot.container instanceof Inventory && !stack.isEmpty() && storing.contains(stack.getItem())) {
                            taken += stack.getCount();
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
                known.put(current, contents);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                player.closeContainer();
                current = null;
            }
        }
        return true;
    }

    /** The chests the player linked (see {@link ChestLinks}) that are loaded and still a container. */
    private static List<BlockPos> containersNearby(Minecraft mc) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : ChestLinks.list(mc)) {
            if (mc.level.isLoaded(pos) && mc.level.getBlockEntity(pos) instanceof net.minecraft.world.Container) {
                found.add(pos);
            }
        }
        return found;
    }

    private static String describe(Map<Item, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.forEach((item, count) -> parts.add(count + " " + new ItemStack(item).getHoverName().getString()));
        return String.join(", ", parts);
    }
}
