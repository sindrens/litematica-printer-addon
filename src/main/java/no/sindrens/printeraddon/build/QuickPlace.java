package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Places a block with one click, one block per printer interval. Before clicking it works out, with the game's own
 * placement rules, which way to look and which side of the empty spot to click so the block comes out exactly as
 * in the schematic (direction, half, axis...). The printer needs 2-4 actions per block (look, sneak, click, one per
 * interval); this needs one. Blocks it can't predict (farmland, comparators to switch, wall torches, rails...) stay
 * with the printer. Most northern row first, so a layer fills row by row.
 */
public class QuickPlace {
    /** A spot just clicked isn't tried again for this long (the server's answer takes a moment). */
    private static final int SKIP_TICKS = 20 * 3;
    private static final Direction[] FACES = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    /** Looking straight down, south, west, north, east, straight up. */
    private static final float[][] ROTATIONS = {{0, 90}, {0, 0}, {90, 0}, {180, 0}, {270, 0}, {0, -90}};
    /** Properties that follow from the neighbours and fix themselves once those are there. */
    private static final Set<String> CONNECTIONS = Set.of("north", "south", "east", "west", "up", "down");

    /** How to place a block: look this way, click this face of its spot. */
    public record Way(float yaw, float pitch, Direction face) {
    }

    private int cooldown;
    private long tick;
    private final Map<BlockPos, Long> skipped = new HashMap<>();
    private static final Map<BlockPos, Optional<Way>> CACHE = new HashMap<>();
    private static long cacheTick = Long.MIN_VALUE;

    public void reset() {
        cooldown = 0;
        skipped.clear();
        CACHE.clear();
    }

    /** True for a missing block this can place right now with one click. */
    public static boolean isCandidate(Minecraft mc, BlockPos pos, BlockState target) {
        return way(mc, pos, target) != null;
    }

    /** How to place the block now, or null if one click can't give exactly the schematic's block. Cached briefly. */
    @Nullable
    public static Way way(Minecraft mc, BlockPos pos, BlockState target) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        long now = mc.level.getGameTime();
        if (now - cacheTick > 5 || now < cacheTick) {
            CACHE.clear();
            cacheTick = now;
        }
        return CACHE.computeIfAbsent(pos.immutable(), p -> Optional.ofNullable(findWay(mc, p, target))).orElse(null);
    }

    @Nullable
    private static Way findWay(Minecraft mc, BlockPos pos, BlockState target) {
        no.sindrens.printeraddon.config.QuickPlaceMode mode = Configs.quickPlace();
        if (mode == no.sindrens.printeraddon.config.QuickPlaceMode.OFF || target.isAir() || target.getBlock() instanceof BaseRailBlock
                || !target.getFluidState().isEmpty() && target.getFluidState().isSource() && target.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return null;
        }
        ItemStack stack = StateRules.itemFor(target);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        Level level = mc.level;
        BlockState here = level.getBlockState(pos);
        if (!here.isAir() && !here.canBeReplaced()) {
            return null;
        }

        // Normally only what the printer can't place (e.g. a hopper with nothing in front of it to click)
        // ... or what the printer says it can do but didn't when it had the chance
        if (mode == no.sindrens.printeraddon.config.QuickPlaceMode.WHEN_PRINTER_CANT && printerCan(mc, pos)
                && !AutoBuild.INSTANCE.mover.wasSkipped(pos)) {
            return null;
        }

        LocalPlayer player = mc.player;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        try {
            for (float[] rotation : ROTATIONS) {
                player.setYRot(rotation[0]);
                player.setXRot(rotation[1]);
                for (Direction face : FACES) {
                    Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack.copy(),
                            new BlockHitResult(hit, face, pos, false));
                    if (!context.getClickedPos().equals(pos) || !context.canPlace()) {
                        continue;
                    }
                    BlockState placed = blockItem.getBlock().getStateForPlacement(context);
                    if (placed != null && placed.canSurvive(level, pos) && matches(placed, target)
                            && level.isUnobstructed(placed, pos, CollisionContext.of(player))) {
                        return new Way(rotation[0], rotation[1], face);
                    }
                }
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
        return null;
    }

    private static final me.aleksilassila.litematica.printer.guides.Guides PRINTER_GUIDES = new me.aleksilassila.litematica.printer.guides.Guides();

    /** True if one of the printer's own placement guides can place the block now. */
    private static boolean printerCan(Minecraft mc, BlockPos pos) {
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return false;
        }
        me.aleksilassila.litematica.printer.SchematicBlockState state =
                new me.aleksilassila.litematica.printer.SchematicBlockState(mc.level, schematic, pos.immutable());
        for (me.aleksilassila.litematica.printer.guides.Guide guide : PRINTER_GUIDES.getInteractionGuides(state)) {
            if (guide.canExecute(mc.player)) {
                return true;
            }
            if (guide.skipOtherGuides()) {
                break;
            }
        }
        return false;
    }

    /** The same block with the same properties, apart from ones that change by themselves or follow neighbours. */
    private static boolean matches(BlockState placed, BlockState target) {
        if (placed.getBlock() != target.getBlock()) {
            // A plain stem where the schematic has one bent towards its fruit: it bends by itself
            return StateRules.isGrowthVariant(target.getBlock(), placed.getBlock());
        }
        boolean connects = target.getBlock() instanceof RedStoneWireBlock || target.getBlock() instanceof FenceBlock
                || target.getBlock() instanceof IronBarsBlock || target.getBlock() instanceof WallBlock;
        for (Property<?> property : target.getProperties()) {
            String name = property.getName();
            if (StateRules.changesByItself(property) || name.equals("waterlogged") || name.equals("powered")
                    || (connects && CONNECTIONS.contains(name)) || (target.getBlock() instanceof StairBlock && name.equals("shape"))) {
                continue;
            }
            if (!placed.getValue(property).equals(target.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    /** Returns true if it placed something this tick. */
    public boolean tick(Minecraft mc) {
        tick++;
        skipped.values().removeIf(until -> until < tick);
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            return false;
        }

        BlockPos next = findNext(mc, player, schematic);
        if (next == null) {
            return false;
        }
        BlockState target = schematic.getBlockState(next);
        Way way = way(mc, next, target);
        ItemStack item = StateRules.itemFor(target);
        if (way == null || item.isEmpty()) {
            return false;
        }

        // Item in hand (the game sends the held item before the click, so this can be in the same tick)
        if (!player.getMainHandItem().is(item.getItem())) {
            Inventory inventory = player.getInventory();
            int slot = inventory.findSlotMatchingItem(item);
            if (slot == -1) {
                return false;
            }
            if (Inventory.isHotbarSlot(slot)) {
                inventory.setSelectedSlot(slot);
            } else {
                InventoryUtils.setPickedItemToHand(slot, item, mc);
                // Moving it from the inventory takes a click: place next turn
                cooldown = 1;
                return true;
            }
        }

        // Look the way that gives the right direction, then click the face of the empty spot
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        boolean turned = Math.abs(oldYaw - way.yaw()) > 0.5 || Math.abs(oldPitch - way.pitch()) > 0.5;
        if (turned) {
            player.setYRot(way.yaw());
            player.setXRot(way.pitch());
            player.yRotO = way.yaw();
            player.xRotO = way.pitch();
            player.connection.send(new ServerboundMovePlayerPacket.Rot(way.yaw(), way.pitch(), player.onGround(), player.horizontalCollision));
        }
        Vec3 hit = Vec3.atCenterOf(next).add(way.face().getStepX() * 0.5, way.face().getStepY() * 0.5, way.face().getStepZ() * 0.5);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, way.face(), next, false));
        if (turned) {
            // Turn back: the printer doesn't always set both directions itself, and would use this one
            restoreView(player, oldYaw, oldPitch);
        }
        skipped.put(next.immutable(), tick + SKIP_TICKS);
        CACHE.remove(next);
        AutoBuild.INSTANCE.stats.placed();
        PrinterAddon.debug("Placed {} at {} (click {}, look {}/{})", target.getBlock().getName().getString(),
                next.toShortString(), way.face().getName(), (int) way.yaw(), (int) way.pitch());
        cooldown = Math.max(1, Configs.Speed.printerInterval() - 1);
        return true;
    }

    /** Turns the view back (and tells the server), after a placement that needed another direction. */
    static void restoreView(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
    }

    /** The next block to place: in reach, ready, and the most northern row first (then west to east). */
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
            if (AutoBuild.INSTANCE.rails.isTemp(pos) || !PlacementOrder.isReady(mc.level, schematic, pos, target)) {
                continue;
            }
            ItemStack item = StateRules.itemFor(target);
            if (item.isEmpty() || !player.getInventory().contains(s -> s.is(item.getItem()))) {
                continue;
            }
            if (way(mc, pos, target) == null) {
                continue;
            }
            best = pos.immutable();
            bestScore = score;
        }
        return best;
    }
}
