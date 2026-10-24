package no.sindrens.printeraddon.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why the connection ended (kicked, timed out...): into the build log, before auto build stops for it. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void printer_addon$disconnected(DisconnectionDetails details, CallbackInfo ci) {
        AutoBuild.INSTANCE.disconnected(details.reason() == null ? "?" : details.reason().getString());
    }
}
