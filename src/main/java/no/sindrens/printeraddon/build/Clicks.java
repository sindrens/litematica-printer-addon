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
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
    }
}
