package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rail planner. A new rail connects to the rails next to it, so in a rail network (parallel tracks, curves,
 * slopes) the order and helper rails decide every rail's shape. Before the printer may place a rail:
 * <ul>
 *     <li>every placed rail next to it that it must not connect to is locked with temporary rails at its free
 *     ends (a rail with two connections can't be pulled sideways; tested in game),</li>
 *     <li>a curve waits until the two rails it joins are there (or temporary rails stand in for them).</li>
 * </ul>
 * Temporary rails are removed once the rails around them have their shape.
 */
public class RailHelper {
    private static final int COOLDOWN = 6;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** Temporary rail position -> the rail it's there for. */
    private final Map<BlockPos, BlockPos> temps = new HashMap<>();
    /** Temporary rails to break (shared with the breaker). */
    private final Set<BlockPos> release;
    @Nullable
    private BlockPos pendingTemp;
    @Nullable
    private Direction pendingFacing;
    private int cooldown;

    public RailHelper(Set<BlockPos> release) {
        this.release = release;
    }

    public void reset() {
        temps.clear();
        pendingTemp = null;
    }

    public boolean isBusy() {
        return pendingTemp != null;
    }

    public boolean isTemp(BlockPos pos) {
        return temps.containsKey(pos);
    }

    /** What a rail needs before it's placed: wait for other rails first, or temporary rails at these spots. */
    public record Plan(boolean waitForRails, List<BlockPos> temps) {
        static final Plan READY = new Plan(false, List.of());
    }

    private static boolean isRail(BlockState state) {
        return state.getBlock() instanceof BaseRailBlock;
    }

    /** The horizontal directions a rail shape connects to (a slope counts as straight along its line). */
    public static List<Direction> connections(BlockState rail) {
        if (!isRail(rail)) {
            return List.of();
        }
        RailShape shape = rail.getValue(((BaseRailBlock) rail.getBlock()).getShapeProperty());
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> List.of(Direction.NORTH, Direction.SOUTH);
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> List.of(Direction.EAST, Direction.WEST);
            case NORTH_EAST -> List.of(Direction.NORTH, Direction.EAST);
            case NORTH_WEST -> List.of(Direction.NORTH, Direction.WEST);
            case SOUTH_EAST -> List.of(Direction.SOUTH, Direction.EAST);
            case SOUTH_WEST -> List.of(Direction.SOUTH, Direction.WEST);
        };
    }

    private static boolean isCurve(BlockState rail) {
        List<Direction> c = connections(rail);
        return c.size() == 2 && c.get(0).getAxis() != c.get(1).getAxis();
    }

    /** A rail at the spot, or one block above/below it (slopes connect up and down). */
    private static boolean railAround(Level level, BlockPos pos) {
        return isRail(level.getBlockState(pos)) || isRail(level.getBlockState(pos.above()))
                || isRail(level.getBlockState(pos.below()));
    }

    private static boolean railAroundInSchematic(WorldSchematic schematic, BlockPos pos) {
        return isRail(schematic.getBlockState(pos)) || isRail(schematic.getBlockState(pos.above()))
                || isRail(schematic.getBlockState(pos.below()));
    }

    public Plan plan(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (!isRail(target)) {
            return Plan.READY;
        }
        List<Direction> desired = connections(target);
        List<BlockPos> needed = new ArrayList<>();

        // Lock placed rails next to it that it must not connect to
        for (Direction d : HORIZONTAL) {
            if (desired.contains(d)) {
                continue;
            }
            BlockPos beside = pos.relative(d);
            BlockState besideState = level.getBlockState(beside);
            if (!isRail(besideState)) {
                continue;
            }
            for (Direction end : connections(besideState)) {
                BlockPos endPos = beside.relative(end);
                if (endPos.equals(pos) || railAround(level, endPos)) {
                    continue;
                }
                if (canHoldTemp(level, endPos)) {
                    needed.add(endPos.immutable());
                }
            }
        }

        // A curve needs both rails it joins first
        if (isCurve(target)) {
            for (Direction d : desired) {
                BlockPos next = pos.relative(d);
                if (railAround(level, next)) {
                    continue;
                }
                if (railAroundInSchematic(schematic, next)) {
                    return new Plan(true, List.of());
                }
                if (canHoldTemp(level, next)) {
                    needed.add(next.immutable());
                }
            }
        }

        return needed.isEmpty() ? Plan.READY : new Plan(false, needed);
    }

    /** True when the rail can be placed now and will get the right shape. */
    public boolean isReady(Level level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        Plan plan = plan(level, schematic, pos, target);
        return !plan.waitForRails() && plan.temps().isEmpty();
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            return;
        }

        releaseFinished(mc.level, schematic);

        if (pendingTemp != null) {
            placeTemp(mc, player, pendingTemp, pendingFacing);
            pendingTemp = null;
            cooldown = COOLDOWN;
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        BlockPos[] next = findTempToPlace(mc, player, schematic);
        if (next == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(new ItemStack(Items.RAIL));
        if (slot == -1) {
            return;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        } else {
            InventoryUtils.setPickedItemToHand(slot, new ItemStack(Items.RAIL), mc);
        }
        pendingTemp = next[1];
        // Face along the line from the rail it locks, so a lone temporary rail lies that way
        pendingFacing = Direction.getApproximateNearest(next[1].getX() - next[2].getX(), 0, next[1].getZ() - next[2].getZ());
        temps.put(next[1], next[0]);
    }

    /** A missing rail within reach whose plan needs a temporary rail: returns {rail, temp spot, locked neighbour}. */
    @Nullable
    private BlockPos[] findTempToPlace(Minecraft mc, LocalPlayer player, WorldSchematic schematic) {
        double reach = player.blockInteractionRange() - 0.5;
        int r = (int) Math.ceil(reach);
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        List<SchematicHelper.Area> areas = SchematicHelper.areas();

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            boolean deferred = AutoBuild.INSTANCE.deferred.contains(pos);
            if ((!deferred && !DataManager.getRenderLayerRange().isPositionWithinRange(pos)) || !SchematicHelper.isInAnyArea(areas, pos)) {
                continue;
            }
            BlockState target = schematic.getBlockState(pos);
            if (!isRail(target) || BlockCheck.check(mc.level, schematic, pos) != BlockCheck.Result.MISSING) {
                continue;
            }
            Plan plan = plan(mc.level, schematic, pos, target);
            for (BlockPos spot : plan.temps()) {
                if (eye.distanceToSqr(Vec3.atCenterOf(spot)) <= reach * reach) {
                    return new BlockPos[]{pos.immutable(), spot, neighbourOf(mc.level, spot, pos)};
                }
            }
        }
        return null;
    }

    /** The rail next to a temporary spot that it locks (or the rail itself for a curve's stand-in). */
    private static BlockPos neighbourOf(Level level, BlockPos spot, BlockPos rail) {
        for (Direction d : HORIZONTAL) {
            BlockPos n = spot.relative(d);
            if (!n.equals(rail) && isRail(level.getBlockState(n)) && n.distManhattan(rail) == 1) {
                return n.immutable();
            }
        }
        return rail.immutable();
    }

    private static boolean canHoldTemp(Level level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        return (here.isAir() || here.canBeReplaced()) && here.getFluidState().isEmpty()
                && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP);
    }

    private void placeTemp(Minecraft mc, LocalPlayer player, BlockPos at, @Nullable Direction facing) {
        if (!player.getMainHandItem().is(Items.RAIL) || !canHoldTemp(mc.level, at)) {
            temps.remove(at);
            return;
        }
        if (facing != null) {
            player.setYRot(facing.toYRot());
            player.yRotO = facing.toYRot();
        }
        BlockPos below = at.below();
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
        BlockPos rail = temps.get(at);
        PrinterAddon.debug("Temporary rail at {} so the rail at {} gets the right shape", at.toShortString(),
                rail == null ? "?" : rail.toShortString());
    }

    private static boolean shapeDone(Level level, WorldSchematic schematic, BlockPos rail) {
        BlockCheck.Result result = BlockCheck.check(level, schematic, rail);
        return result == BlockCheck.Result.OK || result == BlockCheck.Result.BUCKET;
    }

    /** A temporary rail goes once its rail and every rail next to it have their shape. */
    private void releaseFinished(Level level, WorldSchematic schematic) {
        for (Map.Entry<BlockPos, BlockPos> entry : Set.copyOf(temps.entrySet())) {
            BlockPos temp = entry.getKey();
            if (!shapeDone(level, schematic, entry.getValue())) {
                continue;
            }
            boolean aroundDone = true;
            for (Direction d : HORIZONTAL) {
                BlockPos n = temp.relative(d);
                if (temps.containsKey(n)) {
                    continue;
                }
                if (isRail(schematic.getBlockState(n)) && !shapeDone(level, schematic, n)) {
                    aroundDone = false;
                }
            }
            if (aroundDone) {
                temps.remove(temp);
                if (isRail(level.getBlockState(temp))) {
                    release.add(temp);
                    PrinterAddon.debug("Removing the temporary rail at {}", temp.toShortString());
                }
            }
        }
    }
}
