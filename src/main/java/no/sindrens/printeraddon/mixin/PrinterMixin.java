package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.Printer;
import net.minecraft.core.BlockPos;
import no.sindrens.printeraddon.build.AutoBuild;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = Printer.class, remap = false)
public class PrinterMixin {
    /** Pauses the printer while the add-on is breaking a block, so both don't fight over the held item. */
    @Inject(method = "onGameTick", at = @At("HEAD"), cancellable = true)
    private void printer_addon$pauseWhileBreaking(CallbackInfoReturnable<Boolean> cir) {
        // A free turn: the printer has nothing queued and looks for the next block now
        printer_addon$freeTurn = ((Printer) (Object) this).actionHandler.acceptsActions();
        if (no.sindrens.printeraddon.config.Configs.enabled() && AutoBuild.INSTANCE.shouldPausePrinter()) {
            if (printer_addon$freeTurn && AutoBuild.INSTANCE.isRunning()) {
                AutoBuild.INSTANCE.stats.printerTurn(no.sindrens.printeraddon.build.BuildStats.Turn.PAUSED);
            }
            printer_addon$freeTurn = false;
            cir.setReturnValue(false);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean printer_addon$freeTurn;

    /** As soon as the printer queued a placement, air place is switched off again. */
    @Inject(method = "onGameTick", at = @At("RETURN"))
    private void printer_addon$afterPlacing(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            AutoBuild.INSTANCE.airPlace.onPrinterQueued();
            if (AutoBuild.INSTANCE.isRunning()) {
                AutoBuild.INSTANCE.stats.placed();
            }
        }
        // Each free turn, exactly what happened: placed, or why not (from what it was offered in this very turn)
        if (printer_addon$freeTurn && AutoBuild.INSTANCE.isRunning()) {
            AutoBuild build = AutoBuild.INSTANCE;
            no.sindrens.printeraddon.build.BuildStats.Turn turn;
            if (cir.getReturnValueZ()) {
                turn = no.sindrens.printeraddon.build.BuildStats.Turn.PLACED;
            } else if (build.lastOfferedTick != AutoBuild.clientTicks || build.lastRawCount == 0 || build.lastUnfinishedCount == 0) {
                // Nothing left to place in the printer's range (all placed here)
                turn = no.sindrens.printeraddon.build.BuildStats.Turn.NOTHING_IN_REACH;
            } else if (build.lastOfferedCount == 0 && build.lastHeldCount > 0) {
                turn = no.sindrens.printeraddon.build.BuildStats.Turn.JUST_TRIED;
            } else if (build.lastOfferedCount == 0) {
                turn = no.sindrens.printeraddon.build.BuildStats.Turn.NONE_READY;
            } else if (build.lastExecutableCount == 0) {
                turn = no.sindrens.printeraddon.build.BuildStats.Turn.CANT_PLACE_YET;
            } else {
                turn = no.sindrens.printeraddon.build.BuildStats.Turn.SKIPPED;
            }
            build.stats.printerTurn(turn);
        }
    }

    /**
     * Remembers which spot the printer is about to place and with what, so auto build doesn't hand it the same spot
     * again too soon, and the interval for that kind of block is set before its actions go out.
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "onGameTick", at = @At(value = "INVOKE",
            target = "Lme/aleksilassila/litematica/printer/guides/Guide;execute(Lnet/minecraft/client/player/LocalPlayer;)Ljava/util/List;"))
    private List<?> printer_addon$rememberSpot(me.aleksilassila.litematica.printer.guides.Guide guide,
                                               net.minecraft.client.player.LocalPlayer player) {
        List<?> actions = guide.execute(player);
        if (AutoBuild.INSTANCE.isRunning()) {
            AutoBuild.INSTANCE.printerTried(((GuideExecuteMixin) guide).printer_addon$state().blockPos, printer_addon$item(actions));
        }
        return actions;
    }

    /** The item the printer will hold for these actions (null if none). */
    @org.spongepowered.asm.mixin.Unique
    @javax.annotation.Nullable
    private static net.minecraft.world.item.Item printer_addon$item(List<?> actions) {
        for (Object action : actions) {
            me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext context = null;
            if (action instanceof me.aleksilassila.litematica.printer.actions.PrepareAction prepare) {
                context = prepare.context;
            } else if (action instanceof me.aleksilassila.litematica.printer.actions.InteractAction interact) {
                context = interact.context;
            }
            if (context != null) {
                net.minecraft.world.item.ItemStack stack = context.getItemInHand();
                return stack == null || stack.isEmpty() ? null : stack.getItem();
            }
        }
        return null;
    }

    /**
     * Build order: during auto build the printer only sees blocks that are ready to be placed, full blocks
     * first; while air place is on, only the one block it's meant for.
     */
    @Inject(method = "getReachablePositions", at = @At("RETURN"), cancellable = true)
    private void printer_addon$buildOrder(CallbackInfoReturnable<List<BlockPos>> cir) {
        cir.setReturnValue(AutoBuild.INSTANCE.filterPrinterPositions(cir.getReturnValue()));
    }
}
