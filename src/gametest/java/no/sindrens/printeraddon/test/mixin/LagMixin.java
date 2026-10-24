package no.sindrens.printeraddon.test.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;

/**
 * A ping like on a real server for the test world: every packet, both ways, arrives half of PRINTER_ADDON_PING_MS
 * later (in order). Without it the test's server answers every click at once, and nothing the add-on does to wait
 * for the server's answer is tested.
 */
@Mixin(Connection.class)
public abstract class LagMixin {
    @Unique
    private static final long ONE_WAY_MS = oneWay();

    @Unique
    private boolean printer_addon_test$passing;

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext context, Packet<?> packet);

    @Unique
    private static long oneWay() {
        String ping = System.getenv("PRINTER_ADDON_PING_MS");
        return ping == null || ping.isBlank() ? 0 : Long.parseLong(ping.trim()) / 2;
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void printer_addon_test$lag(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (ONE_WAY_MS <= 0 || printer_addon_test$passing) {
            return;
        }
        ci.cancel();
        context.channel().eventLoop().schedule(() -> {
            printer_addon_test$passing = true;
            try {
                channelRead0(context, packet);
            } finally {
                printer_addon_test$passing = false;
            }
        }, ONE_WAY_MS, TimeUnit.MILLISECONDS);
    }
}
