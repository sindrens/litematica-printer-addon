package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block that faces up or down (pistons, observers, droppers) needs the player to look up or down when it's
 * clicked. The printer turns the head straight up or down for it (90 degrees), so between a piston and an observer
 * the head flipped all the way over. 60 degrees is still clearly up or down (the game takes the nearest direction),
 * the block faces the same way, and the head nods instead of flipping. Only during auto build.
 */
@Mixin(value = PrepareAction.class, remap = false)
public class PrepareActionMixin {
    private static final float PITCH = 60;

    @Inject(method = "<init>(Lme/aleksilassila/litematica/printer/implementation/PrinterPlacementContext;)V", at = @At("RETURN"))
    private void printer_addon$gentlerLook(PrinterPlacementContext context, CallbackInfo ci) {
        PrepareAction self = (PrepareAction) (Object) this;
        if (Configs.enabled() && AutoBuild.INSTANCE.isRunning() && self.modifyPitch && Math.abs(self.pitch) >= 89.9f) {
            self.pitch = Math.copySign(PITCH, self.pitch);
        }
    }
}
