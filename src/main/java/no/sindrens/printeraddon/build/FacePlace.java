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
 * Places blocks whose direction comes from the clicked face (hoppers, logs, chains...) with one click on the right
 * face of the empty spot, so the block always gets the right direction. The printer needs several actions for
 * these (look, sneak, click into the hopper in front, stop sneaking: 3-4 printer intervals each) and can't do them
 * at all when the block in front isn't there yet. One block per printer interval, nearest row first, so a floor
 * fills row by row without holes. Everything else stays with the printer.
 */
public class FacePlace {
    /** A spot just clicked isn't tried again for this long (the server's answer takes a moment). */
    private static final int SKIP_TICKS = 20 * 3;

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

    /** True for a block this places: its direction comes from the clicked face, and its spot is empty. */
    public static boolean isCandidate(Level level, BlockPos pos, BlockState target) {
        if (faceFor(target) == null) {
            return false;
        }
        BlockState here = level.getBlockState(pos);
        return here.isAir() || here.canBeReplaced();
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
        double bestScore = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            // Row by row: the most northern row in reach first, then from west to east
            double score = pos.getZ() * 1000 + pos.getX() + pos.getY() * 0.001;
            if (distance > reach * reach || score >= bestScore || distance <= 1.5 || skipped.containsKey(pos)) {
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
            bestScore = score;
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
