package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Places the minecarts (hopper, chest, plain...) the schematic has on its rails, once the rails are there. */
public class EntityPlacer {
    private static final int INTERVAL = 20;

    private int ticks;
    @Nullable
    private BlockPos pendingRail;
    @Nullable
    private Item pendingItem;

    public void reset() {
        pendingRail = null;
        ticks = 0;
    }

    public boolean isBusy() {
        return pendingRail != null;
    }

    /** Minecarts in the schematic, with the rail each one stands on. */
    private static List<Map.Entry<BlockPos, Item>> carts(WorldSchematic schematic) {
        List<Map.Entry<BlockPos, Item>> carts = new ArrayList<>();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            AABB box = new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1);
            for (Entity entity : schematic.getEntities((Entity) null, box, e -> e instanceof AbstractMinecart)) {
                ItemStack item = ((AbstractMinecart) entity).getPickResult();
                if (!item.isEmpty()) {
                    carts.add(Map.entry(entity.blockPosition(), item.getItem()));
                }
            }
        }
        return carts;
    }

    private static boolean hasCart(Minecraft mc, BlockPos rail) {
        return !mc.level.getEntitiesOfClass(AbstractMinecart.class, new AABB(rail).inflate(0.2)).isEmpty();
    }

    /** Minecart items still needed, for the shopping list. */
    public static Map<Item, Integer> missing(Minecraft mc, WorldSchematic schematic) {
        Map<Item, Integer> needs = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Item> cart : carts(schematic)) {
            if (mc.level.isLoaded(cart.getKey()) && !hasCart(mc, cart.getKey())) {
                needs.merge(cart.getValue(), 1, Integer::sum);
            }
        }
        return needs;
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            return;
        }

        if (pendingRail != null) {
            BlockPos rail = pendingRail;
            pendingRail = null;
            if (player.getMainHandItem().is(pendingItem) && mc.level.getBlockState(rail).getBlock() instanceof BaseRailBlock) {
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(rail).add(0, -0.4, 0), Direction.UP, rail, false));
                PrinterAddon.debug("Placing a {} on the rail at {}", new ItemStack(pendingItem).getHoverName().getString(),
                        rail.toShortString());
            }
            return;
        }

        if (++ticks % INTERVAL != 0) {
            return;
        }

        double reach = player.blockInteractionRange() - 0.5;
        for (Map.Entry<BlockPos, Item> cart : carts(schematic)) {
            BlockPos rail = cart.getKey();
            if (!(mc.level.getBlockState(rail).getBlock() instanceof BaseRailBlock) || hasCart(mc, rail)) {
                continue;
            }
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(rail)) > reach * reach) {
                continue;
            }
            Inventory inventory = player.getInventory();
            int slot = inventory.findSlotMatchingItem(new ItemStack(cart.getValue()));
            if (slot == -1) {
                continue;
            }
            if (Inventory.isHotbarSlot(slot)) {
                inventory.setSelectedSlot(slot);
            } else {
                InventoryUtils.setPickedItemToHand(slot, new ItemStack(cart.getValue()), mc);
            }
            pendingRail = rail;
            pendingItem = cart.getValue();
            return;
        }
    }
}
