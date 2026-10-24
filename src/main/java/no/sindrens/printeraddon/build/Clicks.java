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

    /** A block that does something when clicked (opens a menu, switches): placing against it needs sneaking. */
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
