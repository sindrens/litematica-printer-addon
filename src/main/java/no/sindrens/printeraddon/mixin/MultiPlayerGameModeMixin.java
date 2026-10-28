package no.sindrens.printeraddon.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When the client last told the server about another hotbar slot (see InteractActionMixin: seeds wait a tick after). */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Shadow
    private int carriedIndex;

    @Shadow
    @Final
    private Minecraft minecraft;

    /** Official build: the inventory opened before moving items in it, as a player must (see InvScreen). */
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void printer_addon$openInventory(int containerId, int slot, int button, net.minecraft.world.inventory.ContainerInput input,
                                             net.minecraft.world.entity.player.Player player, CallbackInfo ci) {
        try {
            no.sindrens.printeraddon.build.InvScreen.beforeClick(minecraft, containerId);
        } catch (RuntimeException | LinkageError e) {
            no.sindrens.printeraddon.PrinterAddon.LOGGER.warn("[Printer Addon] inventory screen", e);
        }
    }

    @Inject(method = "ensureHasSentCarriedItem", at = @At("HEAD"))
    private void printer_addon$noteSwitch(CallbackInfo ci) {
        if (minecraft.player != null && minecraft.player.getInventory().getSelectedSlot() != carriedIndex) {
            AutoBuild.carriedSentTick = AutoBuild.clientTicks;
        }
    }
}
