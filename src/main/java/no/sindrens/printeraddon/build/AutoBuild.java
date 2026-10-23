package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

/**
 * Ties the parts together every tick: safety checks, layer stepping, breaking, and the printer's own switch.
 */
public class AutoBuild {
    public static final AutoBuild INSTANCE = new AutoBuild();

    /** One-off breaking started from a key, independent of auto build. */
    private enum ManualTask { NONE, FIX_WRONG, CLEAR_EXTRA }

    public final BlockBreaker breaker = new BlockBreaker();
    public final LayerManager layers = new LayerManager();
    private final Safety safety = new Safety();

    private boolean running;
    private boolean printerWasOn;
    private ManualTask manualTask = ManualTask.NONE;
    private int debugTicks;

    private AutoBuild() {
    }

    /** The printer is paused while a block is being broken, so the two don't swap the held item. */
    public boolean shouldPausePrinter() {
        return breaker.isBusy();
    }

    public boolean isRunning() {
        return running;
    }

    /** True while a key-started "fix wrong" / "clear extra" is still working. */
    public boolean isManualTaskRunning() {
        return manualTask != ManualTask.NONE;
    }

    public void start(Minecraft mc) {
        if (running) {
            return;
        }
        if (SchematicHelper.world() == null || SchematicHelper.areas().isEmpty()) {
            Chat.warn("No schematic placement found. Load and place a schematic in Litematica first.");
            Configs.Generic.AUTO_BUILD.setBooleanValue(false);
            return;
        }

        running = true;
        Configs.Generic.AUTO_BUILD.setBooleanValue(true);
        safety.reset();
        breaker.reset();
        PrinterBridge.applySpeed();
        printerWasOn = PrinterBridge.isPrinting();
        PrinterBridge.setPrinting(true);

        if (Configs.Layers.AUTO_LAYER.getBooleanValue() && !layers.start(mc)) {
            stop(mc, null);
            return;
        }

        Chat.info("Auto build §astarted§r.");
    }

    /** Stops auto build. {@code reason} is shown as a warning when it isn't the player's own choice. */
    public void stop(Minecraft mc, String reason) {
        boolean wasRunning = running;
        running = false;
        Configs.Generic.AUTO_BUILD.setBooleanValue(false);
        layers.stop();
        breaker.stop(mc);

        if (wasRunning) {
            PrinterBridge.setPrinting(printerWasOn);
            if (reason != null) {
                Chat.warn("Auto build stopped: " + reason + ".");
            } else {
                Chat.info("Auto build §cstopped§r.");
            }
        }
    }

    public void stopEverything(Minecraft mc) {
        manualTask = ManualTask.NONE;
        stop(mc, null);
    }

    public void toggle(Minecraft mc) {
        if (running) {
            stop(mc, null);
        } else {
            start(mc);
        }
    }

    public void fixWrongNow() {
        manualTask = ManualTask.FIX_WRONG;
        breaker.reset();
        breaker.resetBrokenCount();
    }

    public void clearExtraNow() {
        manualTask = ManualTask.CLEAR_EXTRA;
        breaker.reset();
        breaker.resetBrokenCount();
    }

    public void tick(Minecraft mc) {
        // The settings screen (or a hotkey) can flip the switch too
        if (Configs.Generic.AUTO_BUILD.getBooleanValue() != running) {
            if (running) {
                stop(mc, null);
            } else {
                start(mc);
            }
        }

        if (running) {
            String reason = safety.check(mc);
            if (reason != null) {
                stop(mc, reason);
                return;
            }
        }

        if (manualTask != ManualTask.NONE) {
            BlockBreaker.Mode mode = manualTask == ManualTask.FIX_WRONG ? BlockBreaker.Mode.WRONG : BlockBreaker.Mode.EXTRA;
            if (!breaker.tick(mc, mode)) {
                int count = breaker.getBrokenCount();
                Chat.info(count == 0 ? "Nothing to break within reach." : "Broke " + count + " block" + (count == 1 ? "" : "s") + ".");
                manualTask = ManualTask.NONE;
            }
            return;
        }

        if (!running) {
            return;
        }

        PrinterBridge.applySpeed();

        if (++debugTicks % 40 == 0) {
            PrinterAddon.debug("Tick: printer idle {}, breaker busy {}, layer {}", PrinterBridge.isIdle(),
                    breaker.isBusy(), layers.getLayer());
        }

        if (Configs.Breaking.BREAK_WRONG_BLOCKS.getBooleanValue() && PrinterBridge.isIdle()) {
            breaker.tick(mc, BlockBreaker.Mode.WRONG);
        } else if (breaker.isBusy()) {
            breaker.tick(mc, BlockBreaker.Mode.WRONG);
        }

        if (layers.isActive() && !layers.tick(mc)) {
            stop(mc, null);
        }
    }
}
