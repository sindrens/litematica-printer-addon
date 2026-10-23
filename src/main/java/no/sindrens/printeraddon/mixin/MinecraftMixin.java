package no.sindrens.printeraddon.mixin;

import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla cancels block breaking every tick unless the attack key is held.
 * Skip that while the add-on is breaking a block.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void printer_addon$keepBreaking(boolean leftClick, CallbackInfo ci) {
        if (!leftClick && AutoBuild.INSTANCE.breaker.isBusy()) {
            ci.cancel();
        }
    }
}
