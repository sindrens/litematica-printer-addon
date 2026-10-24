package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the printer can't do with blocks, done with buckets: water and lava sources, filling cauldrons with lava,
 * water or powder snow, and waterlogging blocks. Water only goes in once the blocks around it are built, so it
 * can't wash away redstone or crops.
 */
public class Fluids {
    /** Ticks between bucket uses. */
    private static final int COOLDOWN = 8;
    private static final int SKIP_TICKS = 20 * 30;

    private int cooldown;
    private long tick;
    /** Spot being worked on: first the bucket is put in hand, the next tick it's used. */
    @Nullable
    private BlockPos pending;
    private final Map<BlockPos, Long> skipped = new HashMap<>();
    private final Map<BlockPos, Integer> tries = new HashMap<>();

    public void reset() {
        pending = null;
        skipped.clear();
        tries.clear();
    }

    public boolean isBusy() {
        return pending != null;
    }

    /** The bucket a spot needs, or null if it needs none. */
    @Nullable
    public static Item bucketFor(BlockState target) {
        if (target.getBlock() == Blocks.LAVA_CAULDRON || target.getBlock() == Blocks.LAVA) {
            return Items.LAVA_BUCKET;
        }
        if (target.getBlock() == Blocks.POWDER_SNOW_CAULDRON) {
            return Items.POWDER_SNOW_BUCKET;
        }
        if (target.getBlock() == Blocks.WATER_CAULDRON || target.getBlock() == Blocks.WATER) {
            return Items.WATER_BUCKET;
        }
        if (!target.getFluidState().isEmpty() && target.getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
            // Waterlogged block
            return Items.WATER_BUCKET;
        }
        return null;
    }

    /** True if the player has the bucket and it's safe to use it here now. */
    public static boolean canDo(Minecraft mc, WorldSchematic schematic, BlockPos pos, BlockState target) {
        Item bucket = bucketFor(target);
        return bucket != null && mc.player != null && mc.player.getInventory().contains(s -> s.is(bucket))
                && isSafe(mc.level, schematic, pos, target);
    }

    /**
     * A water or lava source may only go in once the blocks next to it and under it are built, so it doesn't flow
     * over spots where something still has to be placed.
     */
    private static boolean isSafe(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (!(target.getBlock() instanceof LiquidBlock)) {
            return true;
        }
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
            BlockPos next = pos.relative(direction);
            BlockState nextTarget = schematic.getBlockState(next);
            if (nextTarget.isAir() || nextTarget.getBlock() instanceof LiquidBlock) {
                continue;
            }
            BlockCheck.Result result = BlockCheck.check(level, schematic, next);
            if (result != BlockCheck.Result.OK && result != BlockCheck.Result.BUCKET) {
                return false;
            }
        }
        return true;
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
            use(mc, player, schematic, pos);
            cooldown = COOLDOWN;
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

        // Put the bucket in hand now, use it next tick (switching items takes a moment to reach the server)
        Item bucket = bucketFor(schematic.getBlockState(next));
        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(new ItemStack(bucket));
        if (slot == -1) {
            return false;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        } else {
            InventoryUtils.setPickedItemToHand(slot, new ItemStack(bucket), mc);
        }
        pending = next;
        return true;
    }

    @Nullable
    private BlockPos findNext(Minecraft mc, LocalPlayer player, WorldSchematic schematic) {
        double reach = player.blockInteractionRange() - 0.3;
        int r = (int) Math.ceil(reach);
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance > reach * reach || distance >= bestDistance || skipped.containsKey(pos)) {
                continue;
            }
            boolean deferred = AutoBuild.INSTANCE.deferred.contains(pos);
            if (!deferred && !DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                continue;
            }
            if (!SchematicHelper.isInAnyArea(areas, pos)) {
                continue;
            }
            if (BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.BUCKET) {
                continue;
            }
            BlockState target = schematic.getBlockState(pos);
            if (!canDo(mc, schematic, pos, target)) {
                continue;
            }
            best = pos.immutable();
            bestDistance = distance;
        }
        return best;
    }

    private void use(Minecraft mc, LocalPlayer player, WorldSchematic schematic, BlockPos pos) {
        PrinterBridge.releaseLook();
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        useBucket(mc, player, schematic, pos);
        // Turn back, so the printer's next block doesn't use the bucket's direction
        Clicks.restoreView(player, oldYaw, oldPitch);
    }

    private void useBucket(Minecraft mc, LocalPlayer player, WorldSchematic schematic, BlockPos pos) {
        BlockState target = schematic.getBlockState(pos);
        BlockState current = mc.level.getBlockState(pos);
        Item bucket = bucketFor(target);
        if (bucket == null || !player.getMainHandItem().is(bucket)) {
            return;
        }

        boolean done;
        if (current.getBlock() == Blocks.CAULDRON) {
            // Fill the cauldron: click its top
            Vec3 hit = Vec3.atCenterOf(pos).add(0, 0.5, 0);
            lookAt(player, hit);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
            done = true;
            PrinterAddon.debug("Filling the cauldron at {} with {}", pos.toShortString(), bucket);
        } else if (target.getBlock() instanceof LiquidBlock) {
            done = placeSource(mc, player, pos);
        } else {
            // Waterlog: aim at the block itself
            done = aimAt(mc, player, pos, null);
            if (done) {
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                PrinterAddon.debug("Waterlogging {} at {}", target.getBlock().getName().getString(), pos.toShortString());
            }
        }

        if (!done || tries.merge(pos, 1, Integer::sum) > 3) {
            skipped.put(pos, tick + SKIP_TICKS);
            PrinterAddon.debug("Couldn't use a bucket at {} from here, trying later", pos.toShortString());
        }
    }

    /** A bucket places its fluid next to the block face it hits: aim at a neighbour's face that points at the spot. */
    private boolean placeSource(Minecraft mc, LocalPlayer player, BlockPos pos) {
        for (Direction toSpot : Direction.values()) {
            BlockPos neighbour = pos.relative(toSpot.getOpposite());
            BlockState state = mc.level.getBlockState(neighbour);
            if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (aimAt(mc, player, neighbour, toSpot)) {
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                PrinterAddon.debug("Placing {} at {}", mc.level.getBlockState(pos).getBlock().getName().getString(),
                        pos.toShortString());
                return true;
            }
        }
        return false;
    }

    /**
     * Turns the view to a block (a given face of it, or whichever face is seen) and checks the view really hits it
     * there, as the server will check the same.
     */
    private static boolean aimAt(Minecraft mc, LocalPlayer player, BlockPos pos, @Nullable Direction face) {
        Vec3 eye = player.getEyePosition();
        // Aim at the block's real shape: a rail or a slab is much thinner than a full block
        net.minecraft.world.phys.shapes.VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        net.minecraft.world.phys.AABB box = shape.isEmpty()
                ? new net.minecraft.world.phys.AABB(pos) : shape.bounds().move(pos);
        Direction[] faces = face != null ? new Direction[]{face} : Direction.values();
        double range = player.blockInteractionRange();
        double[] spots = {0.5, 0.25, 0.75};
        for (Direction f : faces) {
            // Several points on the face, in case part of it is hidden
            for (double u : spots) {
                for (double v : spots) {
                    Vec3 point = facePoint(box, f, u, v);
                    if (point.distanceToSqr(eye) > range * range) {
                        continue;
                    }
                    BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(point.subtract(eye).normalize().scale(range)),
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getDirection() == f) {
                        lookAt(player, point);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A point on one face of a box: u and v go across the face from 0 to 1. */
    private static Vec3 facePoint(net.minecraft.world.phys.AABB box, Direction f, double u, double v) {
        double x = f.getStepX() > 0 ? box.maxX - 0.01 : f.getStepX() < 0 ? box.minX + 0.01 : box.minX + (box.maxX - box.minX) * u;
        double y = f.getStepY() > 0 ? box.maxY - 0.01 : f.getStepY() < 0 ? box.minY + 0.01
                : box.minY + (box.maxY - box.minY) * (f.getAxis() == Direction.Axis.X ? u : v);
        double z = f.getStepZ() > 0 ? box.maxZ - 0.01 : f.getStepZ() < 0 ? box.minZ + 0.01 : box.minZ + (box.maxZ - box.minZ) * v;
        return new Vec3(x, y, z);
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
