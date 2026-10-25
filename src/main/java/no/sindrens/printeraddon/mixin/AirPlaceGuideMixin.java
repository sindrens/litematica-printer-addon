package no.sindrens.printeraddon.mixin;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guides.placement.GeneralPlacementGuide;
import me.aleksilassila.litematica.printer.guides.placement.GuesserGuide;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The printer's air place works for every block while it's on. During auto build it's only for the blocks that need it
 * (nothing beside or under them, see AirPlace#floatsForGood): the rest is clicked against the block it stands on, as
 * a player would. Sand on string was placed in the air too while a cactus farm's floating string went in, and 53 of
 * those clicks placed nothing on the player's server.
 */
@Mixin(value = {GuesserGuide.class, GeneralPlacementGuide.class}, remap = false)
public class AirPlaceGuideMixin {
    @Redirect(method = {"getPlacementContext", "getValidSide", "getHitVector"}, require = 1, allow = 8,
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/config/options/ConfigBoolean;getBooleanValue()Z"))
    private boolean printer_addon$airOnlyWhereNeeded(ConfigBoolean option) {
        boolean value = option.getBooleanValue();
        if (option != Configs.PRINT_IN_AIR || !value || !no.sindrens.printeraddon.config.Configs.enabled()
                || !AutoBuild.INSTANCE.isRunning()) {
            return value;
        }
        SchematicBlockState state = ((GuideExecuteMixin) (Object) this).printer_addon$state();
        return AutoBuild.INSTANCE.airPlaceAt(state.blockPos);
    }
}
