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
    /** Ticks between bucket uses (an infinite water bucket from a plugin needs the server's answer first). */
    private static final int COOLDOWN = 20;
    /** The slot of the bucket last used and when, to check it came back right (see checkBucket). */
    private int usedSlot = -1;
    private long usedAt;
    private static final int SKIP_TICKS = 20 * 30;
    /**
     * A bucket that missed is tried again this soon (it was 30 s: a leaf the bucket missed once while the lane flew
     * past waited half a minute, then the stems on it waited for its water, and the layer's end took over a minute).
     */
    private static final int RETRY_TICKS = 20;
    /** Bucket uses at one spot before it waits the long while (SKIP_TICKS). */
    private static final int QUICK_TRIES = 6;
    /** A bucket here filled itself again after a use (an infinite water bucket from a plugin). */
    private static boolean infiniteSeen;
    /** wantsTurn: last asked, the answer, and since when it's been wanted without a use. */
    private long wantAskedAt = -1;
    private boolean wantAnswer;
    private long wantedSince = -1;
    private long wantRestUntil = -1;
    /** Ticks the printer is held for the bucket at most, then it gets its turns again for a while. */
    private static final int WANT_LIMIT = 40;
    private static final int WANT_REST = 60;

    /** No bucket use before this client tick (counted in real ticks: it only counted down while the printer was idle). */
    private long readyAt;
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

    /** True once a bucket here filled itself again after a use (an infinite water bucket from a plugin). */
    public static boolean infiniteBucketSeen() {
        return infiniteSeen;
    }

    /**
     * True when a bucket can be used now on a spot in reach: the printer then leaves its next free turn to the bucket
     * and the lane holds still a moment. The bucket only ever got a turn while the printer had nothing at all to
     * place, so on a busy lane the leaves flew by without their water (8 water to go in, 4 of them for 80 s).
     * Never for long: after WANT_LIMIT ticks without a use the printer gets its turns back for a while.
     */
    public boolean wantsTurn(Minecraft mc) {
        tick = AutoBuild.clientTicks;
        if (pending != null || tick < readyAt || tick < wantRestUntil || mc.player == null || mc.level == null) {
            wantedSince = -1;
            return false;
        }
        // Looked for every few ticks (a cube of blocks around the player), the answer kept in between
        if (tick - wantAskedAt >= 4 || tick < wantAskedAt) {
            wantAskedAt = tick;
            WorldSchematic schematic = SchematicHelper.world();
            wantAnswer = schematic != null && findNext(mc, mc.player, schematic) != null;
        }
        if (!wantAnswer) {
            wantedSince = -1;
            return false;
        }
        if (wantedSince < 0) {
            wantedSince = tick;
        } else if (tick - wantedSince > WANT_LIMIT) {
            wantedSince = -1;
            wantRestUntil = tick + WANT_REST;
            PrinterAddon.debug("The bucket waited {} ticks for a turn without getting one: the printer goes on for a while", WANT_LIMIT);
            return false;
        }
        return true;
    }

    public static boolean isBucket(Item item) {
        return item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET || item == Items.POWDER_SNOW_BUCKET;
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
            // Dirt still to be tilled is as good as built (no water gets onto it): farmland without a crop on it waits
            // for its water, and the water waited for that farmland
            if (result != BlockCheck.Result.OK && result != BlockCheck.Result.BUCKET && !Tiller.untilled(level, schematic, next)) {
                return false;
            }
        }
        return true;
    }

    /** Returns true if it did something this tick. */
    public boolean tick(Minecraft mc) {
        tick = AutoBuild.clientTicks;
        skipped.values().removeIf(until -> until < tick);
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            return false;
        }

        if (pending != null) {
            BlockPos pos = pending;
            pending = null;
            int slotUsed = player.getInventory().getSelectedSlot();
            wantAskedAt = -1000;
            if (use(mc, player, schematic, pos)) {
                // Only a bucket really used is checked afterwards (a missed aim left a full bucket in that slot,
                // which would look like one that filled itself again)
                usedSlot = slotUsed;
                usedAt = tick;
                resyncedAt = -1;
            }
            wantedSince = -1;
            // Another water bucket in another slot needs no answer about this one first: ordinary buckets go one
            // after the other (8 leaves a layer); one infinite bucket waits for the server to fill it again
            boolean another = !infiniteSeen && player.getInventory().countItem(Items.WATER_BUCKET) > 0;
            readyAt = tick + (another ? 4 + 2 * ServerLag.pingTicks(mc) : Math.max(COOLDOWN, 10 + 2 * ServerLag.pingTicks(mc)));
            AutoBuild.INSTANCE.inventory.moved();
            AutoBuild.INSTANCE.layers.checkSoon();
            return true;
        }
        checkBucket(mc, player);

        if (tick < readyAt) {
            return false;
        }

        BlockPos next = findNext(mc, player, schematic);
        if (next == null) {
            return refill(mc, player);
        }

        // Put the bucket in hand now, use it next tick (switching items takes a moment to reach the server)
        Item bucket = bucketFor(schematic.getBlockState(next));
        Inventory inventory = player.getInventory();
        int slot = slotOf(inventory, bucket);
        if (slot == -1) {
            return false;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        } else {
            InventoryUtils.setPickedItemToHand(slot, inventory.getItem(slot).copy(), mc);
        }
        pending = next;
        return true;
    }

    /**
     * The client shows the bucket empty after using it; an infinite water bucket is full again on the server, which
     * doesn't always say so (the slot then stays an empty "ghost" bucket, and the layer's water waited for minutes).
     * Soon after a use, a slot still showing an empty bucket is swapped with another slot and back: the server answers
     * every click with what's really in those slots. (Picking it up and putting it back did the same, but this server
     * ignores those clicks without a menu open; swapping works.)
     */
    private void checkBucket(Minecraft mc, LocalPlayer player) {
        int wait = 4 + 2 * ServerLag.pingTicks(mc);
        if (usedSlot < 0 || tick - usedAt < wait || mc.gui.screen() != null) {
            return;
        }
        int slot = usedSlot;
        Inventory inventory = player.getInventory();
        if (!inventory.getItem(slot).is(Items.BUCKET)) {
            if (inventory.getItem(slot).is(Items.WATER_BUCKET) && !infiniteSeen) {
                infiniteSeen = true;
                BuildLog.line("INFO", "the water bucket filled itself again after a use: an infinite bucket, empty buckets are waited for");
            }
            usedSlot = -1;
            return;
        }
        if (resyncedAt >= 0) {
            // Asked once: still empty after the answer, it's a real empty bucket (filled from water nearby, see refill)
            if (tick - resyncedAt >= wait) {
                usedSlot = -1;
            }
            return;
        }
        resyncedAt = tick;
        // Another inventory slot to swap with (an empty one if there is one): there and back in the same tick
        int other = -1;
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            if (i != slot && (other < 0 || inventory.getItem(i).isEmpty() && !inventory.getItem(other).isEmpty())) {
                other = i;
            }
        }
        int id = player.inventoryMenu.containerId;
        if (Inventory.isHotbarSlot(slot) && other >= 0) {
            mc.gameMode.handleContainerInput(id, other, slot, net.minecraft.world.inventory.ContainerInput.SWAP, player);
            mc.gameMode.handleContainerInput(id, other, slot, net.minecraft.world.inventory.ContainerInput.SWAP, player);
        } else if (!Inventory.isHotbarSlot(slot)) {
            int hotbar = inventory.getSelectedSlot();
            mc.gameMode.handleContainerInput(id, slot, hotbar, net.minecraft.world.inventory.ContainerInput.SWAP, player);
            mc.gameMode.handleContainerInput(id, slot, hotbar, net.minecraft.world.inventory.ContainerInput.SWAP, player);
        }
        AutoBuild.INSTANCE.inventory.moved();
        PrinterAddon.debug("Bucket in slot {} still empty after use: asked the server for the real item", slot + 1);
    }

    /** When the empty-looking bucket was swapped there and back to get the real item (-1: not yet). */
    private long resyncedAt = -1;
    private long lastGhostCheck = -100000;

    /**
     * Out of water buckets but holding empty ones: fill one from water in reach that isn't part of the build (a
     * pool or an infinite water source next to it).
     */
    private boolean refill(Minecraft mc, LocalPlayer player) {
        Inventory inventory = player.getInventory();
        if (inventory.contains(s -> s.is(Items.WATER_BUCKET)) || !inventory.contains(s -> s.is(Items.BUCKET))) {
            return false;
        }
        // Only empty buckets: one of them may be an infinite water bucket the client shows empty (see checkBucket),
        // asked about every 30 s
        if (usedSlot < 0 && tick - lastGhostCheck > 20 * 30) {
            lastGhostCheck = tick;
            usedSlot = slotOf(inventory, Items.BUCKET);
            usedAt = tick - 100;
            resyncedAt = -1;
            return false;
        }
        WorldSchematic schematic = SchematicHelper.world();
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        double reach = player.blockInteractionRange() - 0.3;
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int r = (int) Math.ceil(reach);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach || !mc.level.getFluidState(pos).isSourceOfType(
                    net.minecraft.world.level.material.Fluids.WATER) || !(mc.level.getBlockState(pos).getBlock() instanceof LiquidBlock)) {
                continue;
            }
            if (schematic != null && SchematicHelper.isInAnyArea(areas, pos)) {
                continue;
            }
            Vec3 point = Vec3.atCenterOf(pos);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(point.subtract(eye).normalize().scale(reach + 1)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
                continue;
            }
            int slot = slotOf(inventory, Items.BUCKET);
            if (Inventory.isHotbarSlot(slot)) {
                inventory.setSelectedSlot(slot);
            } else {
                InventoryUtils.setPickedItemToHand(slot, inventory.getItem(slot).copy(), mc);
            }
            PrinterBridge.releaseLook();
            float oldYaw = player.getYRot();
            float oldPitch = player.getXRot();
            lookAt(player, point);
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            Clicks.restoreView(player, oldYaw, oldPitch);
            readyAt = tick + COOLDOWN;
            PrinterAddon.debug("Filled a bucket at {}", pos.toShortString());
            return true;
        }
        return false;
    }

    /** The slot of a bucket by item only (an infinite water bucket from a plugin has its own name and lore). */
    private static int slotOf(Inventory inventory, Item item) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
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
            if (distance > reach * reach || distance >= bestDistance || skipped.getOrDefault(pos, Long.MIN_VALUE) >= tick) {
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

    /** Returns true if the bucket was used. */
    private boolean use(Minecraft mc, LocalPlayer player, WorldSchematic schematic, BlockPos pos) {
        PrinterBridge.releaseLook();
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        boolean used = useBucket(mc, player, schematic, pos);
        // Turn back, so the printer's next block doesn't use the bucket's direction
        Clicks.restoreView(player, oldYaw, oldPitch);
        return used;
    }

    private boolean useBucket(Minecraft mc, LocalPlayer player, WorldSchematic schematic, BlockPos pos) {
        BlockState target = schematic.getBlockState(pos);
        BlockState current = mc.level.getBlockState(pos);
        Item bucket = bucketFor(target);
        if (bucket == null || !player.getMainHandItem().is(bucket)) {
            return false;
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

        int tried = tries.merge(pos, 1, Integer::sum);
        if (!done || tried > 3) {
            // Soon again (the player has moved on a bit, or the server just wasn't ready), the long wait only after
            // many tries
            skipped.put(pos, tick + (tried <= QUICK_TRIES ? RETRY_TICKS : SKIP_TICKS));
            PrinterAddon.debug("Couldn't use a bucket at {} from here (try {}), trying again in {} ticks", pos.toShortString(), tried,
                    tried <= QUICK_TRIES ? RETRY_TICKS : SKIP_TICKS);
        }
        return done;
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
