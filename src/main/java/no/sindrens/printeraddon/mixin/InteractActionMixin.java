package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.implementation.actions.InteractActionImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import no.sindrens.printeraddon.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The printer's click sends "use item on" and then "use item", and in 26.2 the server takes the player's direction
 * from that second packet, which is built from where the camera looks. So for the click only, the player faces the
 * way the printer prepared (pistons, observers, hoppers... face right, also in the block the client shows at once),
 * and right after it the camera's own direction comes back, within the same tick, before anything is drawn:
 * the player can look around freely while it builds.
 */
@Mixin(value = InteractActionImpl.class, remap = false)
public class InteractActionMixin {
    @Unique
    private static boolean printer_addon$turned;
    @Unique
    private static float printer_addon$yaw;
    @Unique
    private static float printer_addon$pitch;
    @Unique
    private static float printer_addon$yawO;
    @Unique
    private static float printer_addon$pitchO;

    @Inject(method = "interact", at = @At("HEAD"))
    private void printer_addon$faceForClick(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        printer_addon$turned = false;
        if (!Configs.enabled() || !Configs.Generic.TURN_VIEW_FOR_PRINTER.getBooleanValue()
                || !me.aleksilassila.litematica.printer.config.Configs.ROTATE.getBooleanValue()) {
            return;
        }
        Printer printer = LitematicaMixinMod.printer;
        PrepareAction look = printer == null ? null : printer.actionHandler.lookAction;
        if (look == null || (!look.modifyYaw && !look.modifyPitch)) {
            return;
        }
        printer_addon$yaw = player.getYRot();
        printer_addon$pitch = player.getXRot();
        printer_addon$yawO = player.yRotO;
        printer_addon$pitchO = player.xRotO;
        if (look.modifyYaw) {
            player.setYRot(look.yaw);
        }
        if (look.modifyPitch) {
            player.setXRot(look.pitch);
        }
        printer_addon$turned = true;
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void printer_addon$lookBack(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        if (!printer_addon$turned) {
            return;
        }
        printer_addon$turned = false;
        player.setYRot(printer_addon$yaw);
        player.setXRot(printer_addon$pitch);
        player.yRotO = printer_addon$yawO;
        player.xRotO = printer_addon$pitchO;
    }
}
