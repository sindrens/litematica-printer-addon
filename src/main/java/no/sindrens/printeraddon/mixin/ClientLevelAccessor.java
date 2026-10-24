package no.sindrens.printeraddon.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The client's block predictions: the sequence number the last click went out with (see ServerAcks). */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler printer_addon$predictions();
}
