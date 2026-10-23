package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
    public final Mover mover = new Mover();
    public final AirPlace airPlace = new AirPlace();
    private final Safety safety = new Safety();

    private boolean running;
    private boolean printerWasOn;
    private boolean airPlaceWasOn;
    private ManualTask manualTask = ManualTask.NONE;
    /**
     * Spots that were empty at some point during this build. A container there was placed during the build
     * (by the printer, in the wrong spot): it holds nothing of the player's and may be broken.
     */
    private final java.util.Set<net.minecraft.core.BlockPos> emptyDuringBuild = new java.util.HashSet<>();
    /**
     * Blocks whose layer was finished while they still waited for something in a higher layer (an ascending
     * rail, an observer watching a block above). They are placed as soon as what they need is there.
     */
    public final java.util.Set<net.minecraft.core.BlockPos> deferred = new java.util.LinkedHashSet<>();
    /** Items the current layers need that aren't in the inventory (what to buy). */
    public final java.util.Map<net.minecraft.world.item.Item, Integer> shoppingList = new java.util.LinkedHashMap<>();
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

    /**
     * What the printer gets to see: during auto build only blocks that are ready, full blocks first, plus deferred
     * blocks within reach that have become ready. During air place only the one block it's meant for.
     */
    public java.util.List<net.minecraft.core.BlockPos> filterPrinterPositions(java.util.List<net.minecraft.core.BlockPos> positions) {
        Minecraft mc = Minecraft.getInstance();
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (!running || mc.player == null || mc.level == null || schematic == null) {
            return positions;
        }
        if (airPlace.hasAnchor()) {
            return airPlace.filterPrinterPositions(positions);
        }

        java.util.List<net.minecraft.core.BlockPos> all = positions;
        if (!deferred.isEmpty()) {
            all = new java.util.ArrayList<>(positions);
            double range = me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
            net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
            for (net.minecraft.core.BlockPos pos : deferred) {
                if (eye.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= range * range && !all.contains(pos)) {
                    all.add(pos);
                }
            }
        }
        return PlacementOrder.order(mc.level, schematic, all, mc.player.position());
    }

    public void markEmpty(net.minecraft.core.BlockPos pos) {
        if (running) {
            emptyDuringBuild.add(pos.immutable());
        }
    }

    /** True for a protected (container) block that wasn't there when the build began. */
    public boolean placedDuringBuild(net.minecraft.core.BlockPos pos) {
        return running && emptyDuringBuild.contains(pos);
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
        emptyDuringBuild.clear();
        deferred.clear();
        shoppingList.clear();
        breaker.reset();
        mover.reset();
        PrinterBridge.saveSettings();
        PrinterBridge.applySettings(mc.player);
        printerWasOn = PrinterBridge.isPrinting();
        PrinterBridge.setPrinting(true);
        // Air place only gets switched on for single floating blocks (see AirPlace)
        airPlaceWasOn = PrinterBridge.isAirPlace();
        PrinterBridge.setAirPlace(false);
        airPlace.reset();
        breaker.setForced(airPlace.misplaced());

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
        mover.stop(mc);
        airPlace.stop();

        if (wasRunning) {
            PrinterBridge.setPrinting(printerWasOn);
            PrinterBridge.setAirPlace(airPlaceWasOn);
            PrinterBridge.restoreSettings();
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

    /**
     * A container menu (hopper, chest...) that opened during auto build blocks the printer: the printer clicked
     * a container instead of placing against it. Close it.
     */
    private static void closeUnwantedMenu(Minecraft mc) {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> && !(mc.gui.screen() instanceof InventoryScreen)) {
            PrinterAddon.debug("Closing {} that opened during auto build", mc.gui.screen().getTitle().getString());
            mc.player.closeContainer();
        }
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

        PrinterBridge.applySettings(mc.player);
        closeUnwantedMenu(mc);

        if (++debugTicks % 40 == 0) {
            PrinterAddon.debug("Tick: printer idle {}, breaker busy {}, layer {}", PrinterBridge.isIdle(),
                    breaker.isBusy(), layers.getLayer());
        }

        boolean breakSomething = Configs.Breaking.BREAK_WRONG_BLOCKS.getBooleanValue() || !airPlace.misplaced().isEmpty();
        if (breakSomething && PrinterBridge.isIdle() && !airPlace.isActive()) {
            breaker.tick(mc, BlockBreaker.Mode.WRONG);
        } else if (breaker.isBusy()) {
            breaker.tick(mc, BlockBreaker.Mode.WRONG);
        }

        airPlace.tick(mc, breaker.isBusy());

        if (layers.isActive() && !layers.tick(mc)) {
            stop(mc, null);
            return;
        }

        mover.tick(mc);
    }
}
