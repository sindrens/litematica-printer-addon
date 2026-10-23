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

    /** The player's settings before auto build changed them. */
    private record Saved(int interval, double range, boolean rotate, boolean interact) {
    }

    private static Saved saved;

    /** Remembers the player's printer settings so {@link #restoreSettings()} can put them back. */
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
        int interval = Speed.printerInterval();
        if (Configs.PRINTING_INTERVAL.getIntegerValue() != interval) {
            Configs.PRINTING_INTERVAL.setIntegerValue(interval);
        }
        double range = Math.min(5.0, player.blockInteractionRange());
        if (Math.abs(Configs.PRINTING_RANGE.getDoubleValue() - range) > 0.001) {
            Configs.PRINTING_RANGE.setDoubleValue(range);
        }
        if (!Configs.ROTATE.getBooleanValue()) {
            Configs.ROTATE.setBooleanValue(true);
        }
        if (!Configs.INTERACT_BLOCKS.getBooleanValue()) {
            Configs.INTERACT_BLOCKS.setBooleanValue(true);
        }
    }
}
