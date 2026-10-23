package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.guides.Guide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the spot a printer guide is for. */
@Mixin(value = Guide.class, remap = false)
public interface GuideExecuteMixin {
    @Accessor("state")
    SchematicBlockState printer_addon$state();
}
