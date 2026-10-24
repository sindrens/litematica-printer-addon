package no.sindrens.printeraddon.build;

import me.aleksilassila.litematica.printer.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.player.LocalPlayer;
import no.sindrens.printeraddon.config.Configs.Generic;
import no.sindrens.printeraddon.config.Configs.Speed;

/** Everything the add-on touches in Litematica Printer, in one place. */
public final class PrinterBridge {
    private PrinterBridge() {
    }

    public static boolean isPrinting() {
        return Configs.PRINT_MODE.getBooleanValue();
    }

    public static void setPrinting(boolean on) {
        Configs.PRINT_MODE.setBooleanValue(on);
    }

    public static boolean isAirPlace() {
        return Configs.PRINT_IN_AIR.getBooleanValue();
    }

    public static void setAirPlace(boolean on) {
        Configs.PRINT_IN_AIR.setBooleanValue(on);
    }

    /** True when the printer has no placement queued. */
    public static boolean isIdle() {
        Printer printer = LitematicaMixinMod.printer;
        return printer == null || printer.actionHandler.acceptsActions();
    }

    /**
     * Before the add-on turns the player itself. Litematica Printer keeps its last look direction after its last
     * action (until its next turn) and puts that direction into every look packet sent meanwhile, including the
     * add-on's: a piston meant to face down then faces up. With the printer's queue empty that look is used up, so
     * it's dropped. Returns false while the printer still has actions queued (then don't turn).
     */
    public static boolean releaseLook() {
        Printer printer = LitematicaMixinMod.printer;
        if (printer == null) {
            return true;
        }
        if (!printer.actionHandler.acceptsActions()) {
            return false;
        }
        printer.actionHandler.lookAction = null;
        return true;
    }

    /**
     * While the printer has nothing to do: the head it turned for its last block turns back to where the camera
     * looks, a part each tick (the server shows the head the printer turned until its next block: it stayed looking
     * straight down or up, then snapped). Returns true while still turning.
     */
    public static boolean easeLookBack(net.minecraft.client.player.LocalPlayer player) {
        Printer printer = LitematicaMixinMod.printer;
        if (printer == null || !printer.actionHandler.acceptsActions()) {
            return false;
        }
        me.aleksilassila.litematica.printer.actions.PrepareAction look = printer.actionHandler.lookAction;
        if (look == null || (!look.modifyYaw && !look.modifyPitch)) {
            return false;
        }
        boolean turning = false;
        if (look.modifyPitch) {
            float d = player.getXRot() - look.pitch;
            if (Math.abs(d) > 2) {
                look.pitch += d * 0.3f;
                turning = true;
            }
        }
        if (look.modifyYaw) {
            float d = net.minecraft.util.Mth.wrapDegrees(player.getYRot() - look.yaw);
            if (Math.abs(d) > 2) {
                look.yaw += d * 0.3f;
                turning = true;
            }
        }
        if (!turning) {
            // Back where the camera looks: the printer's look is used up
            printer.actionHandler.lookAction = null;
        }
        // The client only sends its direction when the camera moves: told to the server here (the printer puts
        // its look into the packet while it has one)
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
        return turning;
    }

    private static final class LITEMATICA {
        static final fi.dy.masa.malilib.config.options.ConfigBoolean EASY_PLACE_MODE = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE;
        static final fi.dy.masa.malilib.config.options.ConfigBoolean PICK_BLOCK_AVOID_TOOLS = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_TOOLS;
        static final fi.dy.masa.malilib.config.options.ConfigBoolean PICK_BLOCK_AVOID_DAMAGEABLE = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE;
    }

    /** The player's settings before auto build changed them. */
    private record Saved(int interval, double range, boolean rotate, boolean interact, boolean printInAir,
                         boolean easyPlace, boolean avoidTools, boolean avoidDamageable) {
    }

    private static Saved saved;

    /** Remembers the player's printer settings so {@link #restoreSettings()} can put them back. */
    /** The player's own printer interval (from before auto build changed it): where the automatic speed starts. */
    public static int userInterval() {
        return saved != null ? saved.interval() : Configs.PRINTING_INTERVAL.getIntegerValue();
    }

    public static void saveSettings() {
        saved = new Saved(Configs.PRINTING_INTERVAL.getIntegerValue(), Configs.PRINTING_RANGE.getDoubleValue(),
                Configs.ROTATE.getBooleanValue(), Configs.INTERACT_BLOCKS.getBooleanValue(), Configs.PRINT_IN_AIR.getBooleanValue(),
                LITEMATICA.EASY_PLACE_MODE.getBooleanValue(), LITEMATICA.PICK_BLOCK_AVOID_TOOLS.getBooleanValue(),
                LITEMATICA.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue());
    }

    public static void restoreSettings() {
        if (saved == null) {
            return;
        }
        Configs.PRINTING_INTERVAL.setIntegerValue(saved.interval());
        Configs.PRINTING_RANGE.setDoubleValue(saved.range());
        Configs.ROTATE.setBooleanValue(saved.rotate());
        Configs.INTERACT_BLOCKS.setBooleanValue(saved.interact());
        Configs.PRINT_IN_AIR.setBooleanValue(saved.printInAir());
        LITEMATICA.EASY_PLACE_MODE.setBooleanValue(saved.easyPlace());
        LITEMATICA.PICK_BLOCK_AVOID_TOOLS.setBooleanValue(saved.avoidTools());
        LITEMATICA.PICK_BLOCK_AVOID_DAMAGEABLE.setBooleanValue(saved.avoidDamageable());
        saved = null;
    }

    /** The printer's interval right now (each kind of block has its own, set as the printer picks the block). */
    public static void applyInterval(int interval) {
        if (Generic.APPLY_SPEED_TO_PRINTER.getBooleanValue() && Configs.PRINTING_INTERVAL.getIntegerValue() != interval) {
            Configs.PRINTING_INTERVAL.setIntegerValue(interval);
        }
    }

    /**
     * Settings that make the printer reliable: interval from the speed preset, range no farther than the
     * server lets the player reach (farther gives ghost blocks), and rotation and block interaction on.
     */
    public static void applySettings(LocalPlayer player) {
        if (!Generic.APPLY_SPEED_TO_PRINTER.getBooleanValue()) {
            return;
        }
        int interval = AutoBuild.INSTANCE.speed.interval();
        if (Configs.PRINTING_INTERVAL.getIntegerValue() != interval) {
            Configs.PRINTING_INTERVAL.setIntegerValue(interval);
        }
        if (!Configs.ROTATE.getBooleanValue()) {
            Configs.ROTATE.setBooleanValue(true);
        }
        if (!Configs.INTERACT_BLOCKS.getBooleanValue()) {
            Configs.INTERACT_BLOCKS.setBooleanValue(true);
        }
        // Never into the air (anticheats cancel it); only the add-on's own air place, if switched on, turns it on
        // for one floating block at a time
        if (Configs.PRINT_IN_AIR.getBooleanValue() && !AutoBuild.INSTANCE.airPlace.isActive()) {
            Configs.PRINT_IN_AIR.setBooleanValue(false);
        }
        // Litematica: easy place would click as well; picking blocks never swaps tools out of the hotbar
        if (LITEMATICA.EASY_PLACE_MODE.getBooleanValue()) {
            LITEMATICA.EASY_PLACE_MODE.setBooleanValue(false);
        }
        if (!LITEMATICA.PICK_BLOCK_AVOID_TOOLS.getBooleanValue()) {
            LITEMATICA.PICK_BLOCK_AVOID_TOOLS.setBooleanValue(true);
        }
        if (!LITEMATICA.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue()) {
            LITEMATICA.PICK_BLOCK_AVOID_DAMAGEABLE.setBooleanValue(true);
        }
    }
}
