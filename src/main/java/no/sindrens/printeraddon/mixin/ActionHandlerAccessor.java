package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.ActionHandler;
import me.aleksilassila.litematica.printer.actions.Action;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

/** The printer's queue of actions: a click is put back into it for the next tick (see InteractActionMixin). */
@Mixin(value = ActionHandler.class, remap = false)
public interface ActionHandlerAccessor {
    @Accessor("actionQueue")
    Queue<Action> printer_addon$queue();
}
