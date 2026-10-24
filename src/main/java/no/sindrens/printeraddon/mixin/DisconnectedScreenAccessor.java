package no.sindrens.printeraddon.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why the connection ended, as the "Disconnected" screen shows it (for the build log, and to join again). */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("details")
    DisconnectionDetails printer_addon$details();
}
