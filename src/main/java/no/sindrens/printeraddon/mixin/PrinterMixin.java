package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.Printer;
import net.minecraft.core.BlockPos;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = Printer.class, remap = false)
public class PrinterMixin {
    /** Pauses the printer while the add-on is breaking a block, so both don't fight over the held item. */
    @Inject(method = "onGameTick", at = @At("HEAD"), cancellable = true)
    private void printer_addon$pauseWhileBreaking(CallbackInfoReturnable<Boolean> cir) {
        if (AutoBuild.INSTANCE.shouldPausePrinter()) {
            cir.setReturnValue(false);
        }
    }

    /** As soon as the printer queued a placement, air place is switched off again. */
    @Inject(method = "onGameTick", at = @At("RETURN"))
    private void printer_addon$afterPlacing(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            AutoBuild.INSTANCE.airPlace.onPrinterQueued();
            if (AutoBuild.INSTANCE.isRunning()) {
                AutoBuild.INSTANCE.stats.placed();
            }
        }
    }

    /**
     * Build order: during auto build the printer only sees blocks that are ready to be placed, full blocks
     * first; while air place is on, only the one block it's meant for.
     */
    @Inject(method = "getReachablePositions", at = @At("RETURN"), cancellable = true)
    private void printer_addon$buildOrder(CallbackInfoReturnable<List<BlockPos>> cir) {
        cir.setReturnValue(AutoBuild.INSTANCE.filterPrinterPositions(cir.getReturnValue()));
    }
}
