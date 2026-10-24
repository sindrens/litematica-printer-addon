package no.sindrens.printeraddon.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hotbar slot the client last told the server it holds. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("carriedIndex")
    int printer_addon$carriedIndex();

    @Accessor("carriedIndex")
    void printer_addon$setCarriedIndex(int slot);
}
