package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Small helpers around the printer's clicks (the add-on doesn't place blocks itself). */
public final class Clicks {
    private static final me.aleksilassila.litematica.printer.guides.Guides PRINTER_GUIDES = new me.aleksilassila.litematica.printer.guides.Guides();

    private Clicks() {
    }

    /**
     * A block about to go away: a cactus flower (broken as one of the "always break" blocks), a cactus that can't stand
     * any more (a block beside it, nothing under it: it drops on the server's next tick) or that is being broken. A
     * block clicked against one of those that is gone on the server by the time the click gets there is put INTO its
     * spot: a string meant to float over a grown cactus landed in the growth spot under it. A grown cactus that still
     * stands is as good as any block to click against: the sand placed against it is what makes it drop (holding
     * those back left 379 blocks of a test layer given up).
     */
    public static boolean goingAway(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(net.minecraft.world.level.block.Blocks.CACTUS_FLOWER) || BlockBreaker.alwaysBreak(state)) {
            // Only when it's actually going to be broken (see BlockBreaker.breakAnyway): else the block beside it
            // waited for a flower nobody was breaking
            WorldSchematic schematic = SchematicHelper.world();
            return schematic == null || BlockBreaker.breakAnyway(level, schematic, pos);
        }
        return state.is(net.minecraft.world.level.block.Blocks.CACTUS)
                && (!state.canSurvive(level, pos) || AutoBuild.INSTANCE.breaker.breakingUnder(pos));
    }

    /** The sides the printer's guesser guides try, in their order: what most blocks are placed with (sand, cactus, string...). */
    private static final net.minecraft.core.Direction[] GUESSER_ORDER = {net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST,
            net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN};

    /**
     * The block the printer would click against to place this one, or null when there's none. Its own choice: most
     * blocks go by its guesser guides (the first side of north, south, east, west, up, down with something clickable
     * there); slabs, torches, signs, heads, banners and chests by its general guide (down, up, north, south, west, east,
     * one that doesn't open first).
     */
    @javax.annotation.Nullable
    public static BlockPos clickedFor(net.minecraft.world.level.Level level, BlockPos pos) {
        WorldSchematic schematic = SchematicHelper.world();
        Block wanted = schematic == null ? null : schematic.getBlockState(pos).getBlock();
        boolean general = wanted instanceof net.minecraft.world.level.block.SlabBlock || wanted instanceof net.minecraft.world.level.block.TorchBlock
                || wanted instanceof net.minecraft.world.level.block.SignBlock || wanted instanceof net.minecraft.world.level.block.AbstractSkullBlock
                || wanted instanceof net.minecraft.world.level.block.AbstractBannerBlock || wanted instanceof net.minecraft.world.level.block.ChestBlock;
        BlockPos first = null;
        for (net.minecraft.core.Direction side : general ? net.minecraft.core.Direction.values() : GUESSER_ORDER) {
            BlockPos next = pos.relative(side);
            BlockState state = level.getBlockState(next);
            if (state.getShape(level, next).isEmpty() || state.getBlock() instanceof net.minecraft.world.level.block.SignBlock
                    || state.canBeReplaced()) {
                continue;
            }
            if (!general || !isInteractive(state)) {
                return next;
            }
            if (first == null) {
                first = next;
            }
        }
        return first;
    }

    /** Properties that make a block's placement depend on the side clicked: those clicks are never moved. */
    private static final java.util.Set<String> SIDED = java.util.Set.of("facing", "axis", "half", "type", "face", "hinge",
            "rotation", "attachment", "orientation", "vertical_direction", "hanging");

    /** How many clicks were moved to a side the player can see, and to which block (for the build log). */
    public static int facesMoved;
    public static int faceHitsFixed;

    /**
     * The click as the server can take it: on a face the player's eyes are in front of, at a spot on the block's real
     * shape. A cactus is 1/16 thinner than its block: a click at the block's edge hits air there. String between two
     * cactus was clicked against the one whose face looked away from the player (the lane had flown past it): the
     * player's server refused 500 of 4000 clicks, 12 %, and those were the holes left after the lanes. Now the other
     * cactus (or any block beside the spot whose face the player sees) is clicked, for blocks that go in the same way
     * whatever side they're clicked on.
     */
    public static net.minecraft.world.phys.BlockHitResult visibleFace(Minecraft mc, net.minecraft.world.phys.BlockHitResult hit) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return hit;
        }
        net.minecraft.world.level.Level level = mc.level;
        BlockPos against = hit.getBlockPos();
        net.minecraft.core.Direction face = hit.getDirection();
        BlockPos target = against.relative(face);
        BlockState clicked = level.getBlockState(against);
        if (clicked.canBeReplaced()) {
            return hit;
        }
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.BlockHitResult onShape = onShape(level, against, face, eye);
        if (onShape != null && seen(onShape, eye)) {
            if (!onShape.getLocation().equals(hit.getLocation())) {
                faceHitsFixed++;
            }
            return onShape.getDirection() == face ? new net.minecraft.world.phys.BlockHitResult(onShape.getLocation(), face, against, hit.isInside()) : hit;
        }
        // Another block beside the spot, its face toward the spot seen from here: only for blocks placed the same
        // whatever side is clicked (string, sand, cactus, plain blocks)
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return hit;
        }
        for (net.minecraft.world.level.block.state.properties.Property<?> property : blockItem.getBlock().defaultBlockState().getProperties()) {
            if (SIDED.contains(property.getName())) {
                return hit;
            }
        }
        net.minecraft.world.phys.BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        double reach = player.blockInteractionRange();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            BlockPos next = target.relative(side);
            if (next.equals(against)) {
                continue;
            }
            BlockState state = level.getBlockState(next);
            if (state.canBeReplaced() || state.getShape(level, next).isEmpty() || isInteractive(state) || goingAway(level, next)) {
                continue;
            }
            net.minecraft.world.phys.BlockHitResult candidate = onShape(level, next, side.getOpposite(), eye);
            if (candidate == null || !seen(candidate, eye)) {
                continue;
            }
            double distance = candidate.getLocation().distanceToSqr(eye);
            if (distance <= reach * reach && distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best != null) {
            facesMoved++;
            return best;
        }
        return hit;
    }

    /** The spot on this face of the block's shape nearest to the face's middle, or null when the shape has no such face. */
    @javax.annotation.Nullable
    private static net.minecraft.world.phys.BlockHitResult onShape(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.core.Direction face,
                                                                   net.minecraft.world.phys.Vec3 eye) {
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        net.minecraft.world.phys.AABB box = shape.bounds().move(pos);
        double x = Math.max(box.minX + 0.05, Math.min(box.maxX - 0.05, box.getCenter().x));
        double y = Math.max(box.minY + 0.05, Math.min(box.maxY - 0.05, box.getCenter().y));
        double z = Math.max(box.minZ + 0.05, Math.min(box.maxZ - 0.05, box.getCenter().z));
        switch (face) {
            case WEST -> x = box.minX;
            case EAST -> x = box.maxX;
            case DOWN -> y = box.minY;
            case UP -> y = box.maxY;
            case NORTH -> z = box.minZ;
            case SOUTH -> z = box.maxZ;
        }
        return new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(x, y, z), face, pos, false);
    }

    /** The eyes are in front of the face the hit is on (a little margin: exactly in its plane is fine). */
    private static boolean seen(net.minecraft.world.phys.BlockHitResult hit, net.minecraft.world.phys.Vec3 eye) {
        net.minecraft.core.Direction face = hit.getDirection();
        net.minecraft.world.phys.Vec3 d = eye.subtract(hit.getLocation());
        return d.x * face.getStepX() + d.y * face.getStepY() + d.z * face.getStepZ() > 0.05;
    }

    public static boolean isInteractive(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BaseEntityBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock || block instanceof LeverBlock || block instanceof ButtonBlock
                || block instanceof DiodeBlock || block instanceof NoteBlock || block instanceof CraftingTableBlock
                || block instanceof BedBlock || block instanceof AnvilBlock || block instanceof CakeBlock
                || block instanceof RedStoneWireBlock;
    }

    /** True if one of the printer's own placement guides can place the block now. */
    public static boolean printerCan(Minecraft mc, BlockPos pos) {
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

    /** Turns the view back (and tells the server), after something that needed another direction for one click. */
    public static void restoreView(LocalPlayer player, float yaw, float pitch) {
        if (no.sindrens.printeraddon.config.Configs.Generic.TURN_CAMERA.getBooleanValue()) {
            // The camera really turned to the block and stays there
            return;
        }
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
    }
}
