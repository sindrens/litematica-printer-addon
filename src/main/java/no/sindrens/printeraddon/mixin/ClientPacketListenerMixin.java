package no.sindrens.printeraddon.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import no.sindrens.printeraddon.build.ServerLag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server's time updates tell how fast it runs (see {@link ServerLag}). */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    // TAIL: only reached on the game thread (the network thread hands the packet over and returns early)
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void printer_addon$measureLag(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerLag.onTime(packet.gameTime());
    }
}
