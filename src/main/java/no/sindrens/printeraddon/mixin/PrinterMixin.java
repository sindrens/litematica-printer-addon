package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.Printer;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pauses the printer while the add-on is breaking a block, so both don't fight over the held item. */
@Mixin(value = Printer.class, remap = false)
public class PrinterMixin {
    @Inject(method = "onGameTick", at = @At("HEAD"), cancellable = true)
    private void printer_addon$pauseWhileBreaking(CallbackInfoReturnable<Boolean> cir) {
        if (AutoBuild.INSTANCE.shouldPausePrinter()) {
            cir.setReturnValue(false);
        }
    }
}
