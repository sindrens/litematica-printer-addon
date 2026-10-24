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

    /** The player's settings before auto build changed them. */
    private record Saved(int interval, double range, boolean rotate, boolean interact) {
    }

    private static Saved saved;

    /** Remembers the player's printer settings so {@link #restoreSettings()} can put them back. */
    /** The player's own printer interval (from before auto build changed it): where the automatic speed starts. */
    public static int userInterval() {
        return saved != null ? saved.interval() : Configs.PRINTING_INTERVAL.getIntegerValue();
    }

    public static void saveSettings() {
        saved = new Saved(Configs.PRINTING_INTERVAL.getIntegerValue(), Configs.PRINTING_RANGE.getDoubleValue(),
                Configs.ROTATE.getBooleanValue(), Configs.INTERACT_BLOCKS.getBooleanValue());
    }

    public static void restoreSettings() {
        if (saved == null) {
            return;
        }
        Configs.PRINTING_INTERVAL.setIntegerValue(saved.interval());
        Configs.PRINTING_RANGE.setDoubleValue(saved.range());
        Configs.ROTATE.setBooleanValue(saved.rotate());
        Configs.INTERACT_BLOCKS.setBooleanValue(saved.interact());
        saved = null;
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
    }
}
