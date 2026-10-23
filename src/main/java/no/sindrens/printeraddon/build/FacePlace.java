package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Places blocks whose direction comes from the clicked face (hoppers, logs, chains...) when the block they must be
 * placed against isn't there, like the first row of a floor of hoppers that all face north. The printer can't
 * do those (it needs that block to click on); this clicks the right face of the empty spot itself, so the block
 * gets the right direction. One block per printer interval. Everything else stays with the printer.
 */
public class FacePlace {
    private static final int SKIP_TICKS = 20 * 30;

    @Nullable
    private BlockPos pending;
    private int cooldown;
    private long tick;
    private final Map<BlockPos, Long> skipped = new HashMap<>();

    public void reset() {
        pending = null;
        cooldown = 0;
        skipped.clear();
    }

    public boolean isBusy() {
        return pending != null;
    }

    /** The face to click for this block to get its direction, or null if it isn't such a block. */
    @Nullable
    static Direction faceFor(BlockState target) {
        if (target.getBlock() instanceof HopperBlock) {
            Direction facing = target.getValue(HopperBlock.FACING);
            return facing == Direction.DOWN ? Direction.UP : facing.getOpposite();
        }
        if (target.getBlock() instanceof RotatedPillarBlock) {
            return switch (target.getValue(RotatedPillarBlock.AXIS)) {
                case X -> Direction.EAST;
                case Y -> Direction.UP;
                case Z -> Direction.SOUTH;
            };
        }
        return null;
    }

    /**
     * True for a missing block that only this can place: its direction comes from the clicked face, and the
     * block it would normally be placed against (on that side) isn't there.
     */
    public static boolean isCandidate(Level level, BlockPos pos, BlockState target) {
        Direction face = faceFor(target);
        if (face == null) {
            return false;
        }
        BlockPos against = pos.relative(face.getOpposite());
        BlockState there = level.getBlockState(against);
        return there.isAir() || there.canBeReplaced();
    }

    /** Returns true if it did something this tick. */
    public boolean tick(Minecraft mc) {
        tick++;
        skipped.values().removeIf(until -> until < tick);
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            return false;
        }

        if (pending != null) {
            BlockPos pos = pending;
            pending = null;
            place(mc, player, schematic, pos);
            cooldown = Math.max(2, Configs.Speed.printerInterval());
            return true;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        BlockPos next = findNext(mc, player, schematic);
        if (next == null) {
            return false;
        }

        // Item in hand now, place next tick (switching items takes a moment to reach the server)
        ItemStack item = MaterialCache.getInstance().getRequiredBuildItemForState(schematic.getBlockState(next));
        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(item);
        if (slot == -1) {
            return false;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        } else {
            InventoryUtils.setPickedItemToHand(slot, item, mc);
        }
        pending = next;
        return true;
    }

    @Nullable
    private BlockPos findNext(Minecraft mc, LocalPlayer player, WorldSchematic schematic) {
        double reach = Math.min(me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue(),
                player.blockInteractionRange()) - 0.3;
        int r = (int) Math.ceil(reach);
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance > reach * reach || distance >= bestDistance || distance <= 1.5 || skipped.containsKey(pos)) {
                continue;
            }
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos) && !AutoBuild.INSTANCE.deferred.contains(pos)) {
                continue;
            }
            if (!SchematicHelper.isInAnyArea(areas, pos) || BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.MISSING) {
                continue;
            }
            BlockState target = schematic.getBlockState(pos);
            if (!isCandidate(mc.level, pos, target) || !PlacementOrder.isReady(mc.level, schematic, pos, target)) {
                continue;
            }
            // Not where the player stands
            if (player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) {
                continue;
            }
            ItemStack item = MaterialCache.getInstance().getRequiredBuildItemForState(target);
            if (item.isEmpty() || !player.getInventory().contains(s -> s.is(item.getItem()))) {
                continue;
            }
            best = pos.immutable();
            bestDistance = distance;
        }
        return best;
    }

    private void place(Minecraft mc, LocalPlayer player, WorldSchematic schematic, BlockPos pos) {
        BlockState target = schematic.getBlockState(pos);
        Direction face = faceFor(target);
        ItemStack item = MaterialCache.getInstance().getRequiredBuildItemForState(target);
        if (face == null || !player.getMainHandItem().is(item.getItem()) || !isCandidate(mc.level, pos, target)) {
            return;
        }
        // Click the face of the empty spot itself: the block goes there, with the direction from that face
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAt(player, hit);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        skipped.put(pos.immutable(), tick + SKIP_TICKS);
        PrinterAddon.debug("Placed {} at {} against its {} side", target.getBlock().getName().getString(),
                pos.toShortString(), face.getName());
        AutoBuild.INSTANCE.stats.placed();
    }

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
    }
}
