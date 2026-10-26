package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.guides.placement.PropertySpecificGuesserGuide;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;
import java.util.Set;

/**
 * The printer compares the block it would place with the schematic, including a comparator's mode and a
 * repeater's delay. A freshly placed comparator is always in compare mode and a repeater always has delay 1, so a
 * comparator in subtract mode or a repeater with delay 2-4 was never placed. Ignore those (and a note block's
 * note) when placing: the printer clicks them to the right setting afterwards.
 */
@Mixin(value = PropertySpecificGuesserGuide.class, remap = false)
public class PropertySpecificGuesserGuideMixin {
    @Shadow
    protected static Property<?>[] ignoredProperties;

    private static final Set<Property<?>> printer_addon$setAfterPlacing = Set.of(
            BlockStateProperties.MODE_COMPARATOR, BlockStateProperties.DELAY, BlockStateProperties.NOTE);

    @Inject(method = "statesEqual", at = @At("HEAD"), cancellable = true)
    private void printer_addon$ignoreSettings(BlockState result, BlockState target, CallbackInfoReturnable<Boolean> cir) {
        if (!no.sindrens.printeraddon.config.Configs.enabled()) {
            return;
        }
        if (result.getBlock() != target.getBlock()) {
            cir.setReturnValue(false);
            return;
        }
        // The printer's list has the same property twice (sapling and bamboo "stage"), so no Set.of here
        java.util.List<Property<?>> ignored = java.util.Arrays.asList(ignoredProperties);
        for (Property<?> property : target.getProperties()) {
            if (ignored.contains(property) || printer_addon$setAfterPlacing.contains(property)
                    || no.sindrens.printeraddon.build.StateRules.changesByItself(target.getBlock(), property)) {
                continue;
            }
            if (!result.hasProperty(property) || !Objects.equals(result.getValue(property), target.getValue(property))) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}
