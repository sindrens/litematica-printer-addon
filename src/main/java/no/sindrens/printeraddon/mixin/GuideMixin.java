package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.guides.Guide;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import no.sindrens.printeraddon.build.StateRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * Every printer guide compares blocks through this method, both to decide what to place and whether a spot still
 * needs work. The printer compared everything, so a seed (age 0) never matched a grown stem (age 7) in the
 * schematic and was never planted; the same for extended pistons, switched-off hoppers, farmland moisture and so
 * on. Skip what changes by itself, and treat a grown (attached) stem as the same plant as a fresh one.
 */
@Mixin(value = Guide.class, remap = false)
public class GuideMixin {
    @Inject(method = "statesEqualIgnoreProperties", at = @At("HEAD"), cancellable = true)
    private void printer_addon$ignoreWhatChangesByItself(BlockState state1, BlockState state2,
                                                          Property<?>[] propertiesToIgnore,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (state1.getBlock() != state2.getBlock()) {
            cir.setReturnValue(StateRules.isGrowthVariant(state1.getBlock(), state2.getBlock())
                    || StateRules.isPlaceableBase(state1.getBlock(), state2.getBlock())
                    || StateRules.isPlaceableBase(state2.getBlock(), state1.getBlock()));
            return;
        }

        loop:
        for (Property<?> property : state1.getProperties()) {
            // Same exception as the printer: waterlogging can't be placed, except on coral
            if (property == BlockStateProperties.WATERLOGGED && !(state1.getBlock() instanceof CoralPlantBlock)) {
                continue;
            }
            if (StateRules.changesByItself(property)) {
                continue;
            }
            for (Property<?> ignored : propertiesToIgnore) {
                if (property == ignored) {
                    continue loop;
                }
            }
            if (!state2.hasProperty(property) || !Objects.equals(state1.getValue(property), state2.getValue(property))) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}
