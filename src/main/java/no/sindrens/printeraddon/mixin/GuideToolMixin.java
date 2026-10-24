package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.guides.Guide;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.Tools;
import no.sindrens.printeraddon.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * A printer action that needs a tool (a hoe for tilling, an axe for stripping logs) takes a plain one of the kind
 * when there is one (the best material, the most worn first), not the player's own: the printer goes by its own list,
 * netherite first, and used the player's netherite hoe. Only during auto build; with no plain tool, the printer's
 * own choice.
 */
@Mixin(value = Guide.class, remap = false)
public abstract class GuideToolMixin {
    @Shadow
    protected abstract List<ItemStack> getRequiredItems();

    @Inject(method = "getRequiredItem", at = @At("HEAD"), cancellable = true)
    private void printer_addon$plainTool(LocalPlayer player, CallbackInfoReturnable<Optional<ItemStack>> cir) {
        int slot = printer_addon$plainToolSlot(player);
        if (slot >= 0) {
            cir.setReturnValue(Optional.of(player.getInventory().getItem(slot)));
        }
    }

    @Inject(method = "getRequiredItemStackSlot", at = @At("HEAD"), cancellable = true)
    private void printer_addon$plainToolSlot(LocalPlayer player, CallbackInfoReturnable<Integer> cir) {
        int slot = printer_addon$plainToolSlot(player);
        if (slot >= 0) {
            cir.setReturnValue(slot);
        }
    }

    @Unique
    private int printer_addon$plainToolSlot(LocalPlayer player) {
        if (!Configs.enabled() || !AutoBuild.INSTANCE.isRunning() || player.getAbilities().instabuild) {
            return -1;
        }
        Item kind = null;
        for (ItemStack required : getRequiredItems()) {
            kind = Tools.kindOf(required);
            if (kind != null) {
                break;
            }
        }
        return kind == null ? -1 : Tools.bestSlot(kind, player.getInventory());
    }
}
