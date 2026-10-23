package no.sindrens.printeraddon.build;

import me.aleksilassila.litematica.printer.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.config.Configs;
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

    /** True when the printer has no placement queued. */
    public static boolean isIdle() {
        Printer printer = LitematicaMixinMod.printer;
        return printer == null || printer.actionHandler.acceptsActions();
    }

    public static void applySpeed() {
        if (Generic.APPLY_SPEED_TO_PRINTER.getBooleanValue()) {
            int interval = Speed.printerInterval();
            if (Configs.PRINTING_INTERVAL.getIntegerValue() != interval) {
                Configs.PRINTING_INTERVAL.setIntegerValue(interval);
            }
        }
    }
}
