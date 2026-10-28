package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Temporary blocks (dirt by default) to reach blocks of the schematic that float, with nothing beside or under them to
 * place them against, when air place is off (or the server refuses it): as a player would, a few blocks are put from
 * the nearest solid block towards the floating one, the printer places the schematic's block against them, and once
 * nothing around needs them any more they are broken again. Only where the schematic has air, never in the player.
 */
public final class TempBlocks {
    public static final TempBlocks INSTANCE = new TempBlocks();

    private static final int SEARCH_RADIUS = 24;
    private static final int PLACE_EVERY_TICKS = 4;

    /** Temp blocks in the world (put there by this build). */
    private final Set<BlockPos> placed = new LinkedHashSet<>();
    /** Done with: to be broken (see BlockBreaker). */
    private final Set<BlockPos> remove = new HashSet<>();
    /** Floating blocks with no solid block close enough to start from: tried again later. */
    private final Map<BlockPos, Long> noAnchorUntil = new HashMap<>();
    private long lastPlaceTick = -100;
    private long lastReleaseTick;
    private int placedCount;
    /** A floating block of the layer still waits for a temp block (nothing is released meanwhile). */
    private boolean jobActive;
    @Nullable
    private BlockPos errand;

    private TempBlocks() {
    }

    public boolean enabled() {
        return Configs.Layers.TEMP_BLOCKS.getBooleanValue() && !AutoBuild.INSTANCE.airPlace.allowed();
    }

    /** The blocks the setting lists (in its order; dirt when none of them is a block). */
    public java.util.List<Item> items() {
        java.util.List<Item> out = new java.util.ArrayList<>();
        for (String name : Configs.Layers.TEMP_BLOCK_ITEM.getStringValue().split("[,;\\s]+")) {
            Identifier id = Identifier.tryParse(name.trim().toLowerCase(java.util.Locale.ROOT));
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item instanceof BlockItem block && !(block.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) {
                out.add(item);
            }
        }
        if (out.isEmpty()) {
            out.add(Items.DIRT);
        }
        return out;
    }

    /** The temp block to use: the first of the list there is in the inventory, else the first of the list. */
    public Item item() {
        java.util.List<Item> items = items();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            for (Item item : items) {
                if (player.getInventory().countItem(item) > 0) {
                    return item;
                }
            }
        }
        return items.getFirst();
    }

    /** A temp block still in use: not to be broken by anything else. */
    public boolean isTemp(BlockPos pos) {
        return placed.contains(pos) && !remove.contains(pos);
    }

    /** A temp block done with, to break when in reach. */
    public boolean toRemove(BlockPos pos) {
        return remove.contains(pos);
    }

    public boolean hasRemovals() {
        return !remove.isEmpty();
    }

    /** Broken (by the breaker): it's gone from the lists. */
    public void removed(BlockPos pos) {
        if (remove.remove(pos)) {
            placed.remove(pos);
        }
    }

    public int left() {
        return placed.size();
    }

    public void reset() {
        placed.clear();
        remove.clear();
        noAnchorUntil.clear();
        jobActive = false;
        placedCount = 0;
        releaseErrand();
    }

    /** At the end of a build: the temp blocks still standing, said once. */
    public void stopped() {
        releaseErrand();
        if (!placed.isEmpty()) {
            BlockPos first = placed.iterator().next();
            Chat.warn(placed.size() + " temporary block" + (placed.size() == 1 ? "" : "s") + " (" + itemName() + ") left in the build, near "
                    + first.toShortString() + ". 'Clear extra blocks' (J+X) next to them breaks them.");
            BuildLog.line("TEMP", placed.size() + " left at the stop: " + placed);
        }
        placed.clear();
        remove.clear();
        jobActive = false;
    }

    private String itemName() {
        return item().getName(item().getDefaultInstance()).getString();
    }

    /** Every tick while auto build runs. */
    public void tick(Minecraft mc, boolean breakerBusy) {
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            return;
        }
        long now = AutoBuild.clientTicks;
        if (now - lastReleaseTick >= 20) {
            lastReleaseTick = now;
            release(mc.level, schematic);
        }
        if (!enabled()) {
            jobActive = false;
            releaseErrand();
            return;
        }
        if (breakerBusy || now - lastPlaceTick < PLACE_EVERY_TICKS
                || AutoBuild.INSTANCE.fetcher.isBusy()) {
            return;
        }
        Plan plan = plan(mc, player, schematic);
        jobActive = plan != null;
        if (plan == null) {
            releaseErrand();
            return;
        }
        double reach = player.blockInteractionRange() - 0.3;
        if (player.getEyePosition().distanceToSqr(plan.hit) > reach * reach) {
            // Out of reach: go there first (the build waits on this block anyway)
            if (!plan.cell.equals(errand)) {
                errand = plan.cell;
                BuildLog.line("TEMP", "going to " + plan.cell.toShortString() + " to put a temp block for " + plan.target.toShortString());
            }
            AutoBuild.INSTANCE.mover.goTo(plan.cell, null, Configs.Movement.mode().canWalk());
            return;
        }
        place(mc, player, plan);
    }

    private void releaseErrand() {
        if (errand != null) {
            errand = null;
            AutoBuild.INSTANCE.mover.goTo(null);
        }
    }

    private record Plan(BlockPos target, BlockPos cell, BlockPos against, Direction face, Vec3 hit) {
    }

    /** The next temp block: for the nearest floating block, the cell next to something solid on the way to it. */
    @Nullable
    private Plan plan(Minecraft mc, LocalPlayer player, WorldSchematic schematic) {
        Level level = mc.level;
        long now = AutoBuild.clientTicks;
        noAnchorUntil.values().removeIf(t -> t < now);
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 eye = player.getEyePosition();
        int r = SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (d >= bestDistance || !DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                continue;
            }
            BlockState target = schematic.getBlockState(pos);
            if (target.isAir() || !target.getFluidState().isEmpty() || noAnchorUntil.containsKey(pos)) {
                continue;
            }
            if (BlockCheck.check(level, schematic, pos) != BlockCheck.Result.MISSING || AirPlace.hasSupport(level, pos)) {
                continue;
            }
            best = pos.immutable();
            bestDistance = d;
        }
        if (best == null) {
            return null;
        }
        Plan plan = path(level, schematic, player, best);
        if (plan == null) {
            noAnchorUntil.put(best, now + 20 * 20);
            BuildLog.line("TEMP", best.toShortString() + " floats with nothing solid within "
                    + Configs.Layers.TEMP_MAX_CHAIN.getIntegerValue() + " blocks to build out from: tried again in 20 s");
        }
        return plan;
    }

    /** Breadth-first from the floating block through free cells (air in the schematic) to one beside something solid. */
    @Nullable
    private Plan path(Level level, WorldSchematic schematic, LocalPlayer player, BlockPos target) {
        int max = Configs.Layers.TEMP_MAX_CHAIN.getIntegerValue();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        // Below first: a column under it is what a player would do, and it stays out of the way of the layer
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos c = target.relative(d);
            if (free(level, schematic, player, c)) {
                queue.add(c);
                depth.put(c, 1);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            Direction toSolid = solidSide(level, cell);
            if (toSolid != null) {
                BlockPos against = cell.relative(toSolid);
                Direction face = toSolid.getOpposite();
                Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                return new Plan(target, cell, against, face, hit);
            }
            int dd = depth.get(cell);
            if (dd >= max) {
                continue;
            }
            for (Direction d : order) {
                BlockPos next = cell.relative(d);
                if (!depth.containsKey(next) && free(level, schematic, player, next)) {
                    depth.put(next, dd + 1);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** A cell a temp block may go in: empty, air in the schematic, not where the player is. */
    private static boolean free(Level level, WorldSchematic schematic, LocalPlayer player, BlockPos cell) {
        if (!level.isLoaded(cell) || !level.getWorldBorder().isWithinBounds(cell)) {
            return false;
        }
        BlockState state = level.getBlockState(cell);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (SchematicHelper.isInAnyArea(SchematicHelper.areas(), cell) && !schematic.getBlockState(cell).isAir()) {
            return false;
        }
        return !player.getBoundingBox().inflate(0.05).intersects(new net.minecraft.world.phys.AABB(cell));
    }

    /** A side of the cell with a solid block to click against (not a chest or anything that opens), or null. */
    @Nullable
    private static Direction solidSide(Level level, BlockPos cell) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockState neighbor = level.getBlockState(cell.relative(d));
            if (!neighbor.isAir() && !neighbor.canBeReplaced() && neighbor.getFluidState().isEmpty() && !Clicks.isInteractive(neighbor)) {
                return d;
            }
        }
        return null;
    }

    private long switchedAt = -100;

    private void place(Minecraft mc, LocalPlayer player, Plan plan) {
        Item item = item();
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        int slot = -1;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            int from = -1;
            for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
                if (inventory.getItem(i).is(item)) {
                    from = i;
                    break;
                }
            }
            if (from < 0) {
                releaseErrand();
                Chat.warnEvery("temp-blocks", 60_000, "No " + itemName() + " for temporary blocks (to reach floating blocks): "
                        + "put some in your inventory, or change 'Temp block' in the Layers settings.");
                return;
            }
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, selected,
                    net.minecraft.world.inventory.ContainerInput.SWAP, player);
            AutoBuild.INSTANCE.inventory.moved();
            switchedAt = AutoBuild.clientTicks;
            return;
        }
        if (slot != selected) {
            inventory.setSelectedSlot(slot);
            switchedAt = AutoBuild.clientTicks;
            return;
        }
        // Not in the same tick as the switch (servers refuse those clicks)
        if (AutoBuild.clientTicks - switchedAt < 2 || !PrinterBridge.releaseLook()) {
            return;
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        Vec3 d = plan.hit.subtract(player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(),
                player.horizontalCollision));
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(plan.hit, plan.face, plan.against, false));
        player.swing(InteractionHand.MAIN_HAND);
        Clicks.restoreView(player, oldYaw, oldPitch);
        lastPlaceTick = AutoBuild.clientTicks;
        placed.add(plan.cell);
        placedCount++;
        releaseErrand();
        BuildLog.line("TEMP", "placed " + itemName() + " at " + plan.cell.toShortString() + " (against " + plan.against.toShortString()
                + ") for " + plan.target.toShortString() + " | " + placed.size() + " standing, " + placedCount + " this build");
    }

    /** Temp blocks nothing needs any more go to the breaker; ones gone already are forgotten. */
    private void release(Level level, WorldSchematic schematic) {
        java.util.List<Item> items = items();
        placed.removeIf(p -> level.isLoaded(p) && !items.contains(level.getBlockState(p).getBlock().asItem()));
        remove.retainAll(placed);
        if (jobActive || !Configs.Layers.TEMP_BLOCKS_REMOVE.getBooleanValue()) {
            return;
        }
        for (BlockPos p : placed) {
            if (!remove.contains(p) && !neededNear(level, schematic, p)) {
                remove.add(p);
            }
        }
    }

    /** A block of the layers being built, still missing, within 2 blocks of the temp block. */
    private static boolean neededNear(Level level, WorldSchematic schematic, BlockPos p) {
        for (BlockPos pos : BlockPos.betweenClosed(p.offset(-2, -2, -2), p.offset(2, 2, 2))) {
            if (!schematic.getBlockState(pos).isAir() && BlockCheck.check(level, schematic, pos) == BlockCheck.Result.MISSING) {
                return true;
            }
        }
        return false;
    }
}
