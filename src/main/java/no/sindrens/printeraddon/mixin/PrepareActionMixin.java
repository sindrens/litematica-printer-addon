package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.actions.PrepareAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import no.sindrens.printeraddon.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The printer only sends its rotation in a packet. After placing it also sends a "use item" packet, and in 26.2
 * the server takes the player's rotation from that packet, i.e. where the player really looks. Turning the real
 * view the same way keeps both in agreement, so directional blocks face the way the schematic says.
 */
@Mixin(value = PrepareAction.class, remap = false)
public class PrepareActionMixin {
    @Inject(method = "send", at = @At("TAIL"))
    private void printer_addon$turnView(Minecraft client, LocalPlayer player, CallbackInfo ci) {
        // Only during auto build: otherwise the player looks where they want
        if (!Configs.enabled() || !no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()
                || !Configs.Generic.TURN_VIEW_FOR_PRINTER.getBooleanValue()
                || !me.aleksilassila.litematica.printer.config.Configs.ROTATE.getBooleanValue()) {
            return;
        }

        PrepareAction action = (PrepareAction) (Object) this;
        if (action.modifyYaw) {
            player.setYRot(action.yaw);
            player.yRotO = action.yaw;
        }
        if (action.modifyPitch) {
            player.setXRot(action.pitch);
            player.xRotO = action.pitch;
        }
    }
}
