package no.sindrens.printeraddon.mixin;

import me.aleksilassila.litematica.printer.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.implementation.actions.InteractActionImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import no.sindrens.printeraddon.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The printer's click sends "use item on" and then "use item", and in 26.2 the server takes the player's direction
 * from that second packet, which is built from where the camera looks. So for the click only, the player faces the
 * way the printer prepared (pistons, observers, hoppers... face right, also in the block the client shows at once),
 * and right after it the camera's own direction comes back, within the same tick, before anything is drawn:
 * the player can look around freely while it builds.
 */
@Mixin(value = InteractActionImpl.class, remap = false)
public class InteractActionMixin {
    @Unique
    private static boolean printer_addon$turned;
    @Unique
    private static float printer_addon$yaw;
    @Unique
    private static float printer_addon$pitch;
    @Unique
    private static float printer_addon$yawO;
    @Unique
    private static float printer_addon$pitchO;

    /** The sequence number before the click: its first packet ("use item on") goes out with the next one. */
    @Unique
    private static int printer_addon$sequenceBefore = -1;

    @Unique
    private static boolean printer_addon$sneaked;
    @Unique
    private static net.minecraft.world.entity.player.Input printer_addon$keys;
    /** The click last put back for a tick after its item switch (see beforeClick). */
    @Unique
    private static Object printer_addon$deferred;

    /** The click moved to a face the player sees, on the block's real shape (see Clicks.visibleFace). */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "interact", at = @At("HEAD"), argsOnly = true)
    private BlockHitResult printer_addon$visibleFace(BlockHitResult hit) {
        try {
            if (Configs.enabled() && no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()
                    && Configs.Speed.CLICK_SEEN_FACE.getBooleanValue()) {
                return no.sindrens.printeraddon.build.Clicks.visibleFace(Minecraft.getInstance(), hit);
            }
        } catch (RuntimeException | LinkageError e) {
            no.sindrens.printeraddon.PrinterAddon.LOGGER.warn("visibleFace: {}", e.toString());
        }
        return hit;
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void printer_addon$beforeClick(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        // An error in the add-on's part stops auto build and goes into the build log; the click and the game go on
        try {
            printer_addon$sequenceBefore = Configs.enabled() && client.level != null
                    ? ((ClientLevelAccessor) client.level).printer_addon$predictions().currentSequence() : -1;
            // Never against a block about to go (a flower, a grown cactus): gone on the server first, the block would go
            // into its spot. Held for a moment instead (it's broken or drops meanwhile)
            if (Configs.enabled() && client.level != null && no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()
                    && !client.level.getBlockState(hit.getBlockPos()).canBeReplaced()
                    && no.sindrens.printeraddon.build.Clicks.goingAway(client.level, hit.getBlockPos())) {
                no.sindrens.printeraddon.build.AutoBuild.INSTANCE.clickSkipped(client, hit.getBlockPos().relative(hit.getDirection()), hit.getBlockPos());
                ci.cancel();
                return;
            }
            // A click in the same tick as the switch to its item: the player's server refused 36 of a log's 40 refused
            // clicks so (seeds, pistons, observers alike; 7 % of the clicks right after a switch, 0.5 % of the others).
            // The click goes back into the printer's queue (nothing else is queued meanwhile) and out a tick later
            if (Configs.enabled() && client.gameMode != null && no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()
                    && no.sindrens.printeraddon.config.Configs.Speed.WAIT_AFTER_SWITCH.getBooleanValue()
                    && !player.getItemInHand(hand).isEmpty() && LitematicaMixinMod.printer != null) {
                MultiPlayerGameModeAccessor gameMode = (MultiPlayerGameModeAccessor) client.gameMode;
                int selected = player.getInventory().getSelectedSlot();
                boolean unsent = selected != gameMode.printer_addon$carriedIndex();
                if (unsent || no.sindrens.printeraddon.build.AutoBuild.carriedSentTick == no.sindrens.printeraddon.build.AutoBuild.clientTicks) {
                    if (unsent) {
                        // The switch goes out now, the click a tick later
                        gameMode.printer_addon$setCarriedIndex(selected);
                        player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selected));
                        no.sindrens.printeraddon.build.AutoBuild.carriedSentTick = no.sindrens.printeraddon.build.AutoBuild.clientTicks;
                    }
                    ((ActionHandlerAccessor) LitematicaMixinMod.printer.actionHandler).printer_addon$queue()
                            .add((me.aleksilassila.litematica.printer.actions.Action) (Object) this);
                    // Counted once a click (the printer comes back to it several times within the tick)
                    if (printer_addon$deferred != (Object) this) {
                        printer_addon$deferred = this;
                        no.sindrens.printeraddon.build.RunLog.switchWait();
                    }
                    ci.cancel();
                    return;
                }
            }
            printer_addon$faceForClick(client, player, hand, hit, ci);
            if (!ci.isCancelled() && Configs.enabled()) {
                // The click's side, spot, the head's turn and the speed, for the line with its result (see RunLog)
                no.sindrens.printeraddon.build.RunLog.click(client, hit.getBlockPos(), hit.getDirection(), hit.getLocation());
            }
        } catch (RuntimeException | LinkageError e) {
            no.sindrens.printeraddon.build.AutoBuild.INSTANCE.error("the printer's click", e);
        }
    }

    @Unique
    private void printer_addon$faceForClick(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        printer_addon$turned = false;
        printer_addon$sneaked = false;
        // The stack ran out between the printer's prepare and this click: an empty hand places nothing and opens a
        // hopper or chest it clicks on (sneaking doesn't help with empty hands). Skip it; the printer tries again
        if (Configs.enabled() && player.getItemInHand(hand).isEmpty()) {
            no.sindrens.printeraddon.PrinterAddon.trace("Skipped a printer click with an empty hand at {}", hit.getBlockPos().toShortString());
            if (no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()) {
                no.sindrens.printeraddon.build.AutoBuild.INSTANCE.speed.emptyHand(hit.getBlockPos());
                // The item it wanted is in the inventory (the hotbar ran dry, e.g. with the chat open): into the
                // hand now, the printer clicks again in a moment
                printer_addon$refillEmpty(client, player, hand);
            }
            ci.cancel();
            return;
        }
        // Placing against a hopper, chest, door...: the server must see the player sneaking at the moment of the
        // click, or it opens the hopper instead (and nothing is placed). The printer doesn't always sneak, and its
        // sneak can be overtaken by the click. Sneak right before the click, in the same packet order a player would
        if (Configs.enabled() && client.level != null
                && no.sindrens.printeraddon.build.Clicks.isInteractive(client.level.getBlockState(hit.getBlockPos()))
                && printer_addon$refillFirst(client, player, hand)) {
            ci.cancel();
            return;
        }
        if (Configs.enabled() && client.level != null
                && no.sindrens.printeraddon.build.Clicks.isInteractive(client.level.getBlockState(hit.getBlockPos()))) {
            net.minecraft.world.entity.player.Input keys = player.input.keyPresses;
            net.minecraft.world.entity.player.Input sneaking = new net.minecraft.world.entity.player.Input(
                    keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerInputPacket(sneaking));
            // The client sneaks for the click too: otherwise it doesn't place (or count) the block while the server
            // does, the stack runs out a click earlier on the server, and that click opens the hopper
            printer_addon$keys = keys;
            player.input.keyPresses = sneaking;
            printer_addon$sneaked = true;
        }
        if (!Configs.enabled() || !Configs.Generic.TURN_VIEW_FOR_PRINTER.getBooleanValue()
                || !me.aleksilassila.litematica.printer.config.Configs.ROTATE.getBooleanValue()) {
            return;
        }
        Printer printer = LitematicaMixinMod.printer;
        PrepareAction look = printer == null ? null : printer.actionHandler.lookAction;
        if (look == null || (!look.modifyYaw && !look.modifyPitch)) {
            return;
        }
        printer_addon$yaw = player.getYRot();
        printer_addon$pitch = player.getXRot();
        printer_addon$yawO = player.yRotO;
        printer_addon$pitchO = player.xRotO;
        if (look.modifyYaw) {
            player.setYRot(look.yaw);
        }
        if (look.modifyPitch) {
            player.setXRot(look.pitch);
        }
        printer_addon$turned = true;
    }

    /** An empty hand at the printer's click: the item it picked, from the inventory into the hand. */
    @Unique
    private static void printer_addon$refillEmpty(Minecraft client, LocalPlayer player, InteractionHand hand) {
        net.minecraft.world.item.Item wanted = no.sindrens.printeraddon.build.AutoBuild.INSTANCE.lastTriedItem;
        if (wanted == null || hand != InteractionHand.MAIN_HAND || client.gameMode == null
                || client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            return;
        }
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        int best = -1;
        for (int slot = net.minecraft.world.entity.player.Inventory.getSelectionSize(); slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.is(wanted) && (best < 0 || stack.getCount() > inventory.getItem(best).getCount())) {
                best = slot;
            }
        }
        if (best < 0) {
            return;
        }
        client.gameMode.handleContainerInput(player.inventoryMenu.containerId, best, inventory.getSelectedSlot(),
                net.minecraft.world.inventory.ContainerInput.SWAP, player);
        no.sindrens.printeraddon.build.AutoBuild.INSTANCE.inventory.moved();
        no.sindrens.printeraddon.build.BuildLog.line("HOTBAR", "empty hand at a click: " + wanted.toString().replace("minecraft:", "")
                + " from slot " + (best + 1) + " into hotbar slot " + (inventory.getSelectedSlot() + 1));
    }

    /**
     * The last item of a stack, clicked against a hopper or chest: the server can already be at zero (it counts
     * placements the client didn't see) and then opens the hopper. Swap a full stack of the same item into the hand
     * first and skip this click; the printer clicks again after.
     */
    @Unique
    private static boolean printer_addon$refillFirst(Minecraft client, LocalPlayer player, InteractionHand hand) {
        net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || held.getCount() > 1 || client.gameMode == null) {
            return false;
        }
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        for (int slot = net.minecraft.world.entity.player.Inventory.getSelectionSize(); slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.getCount() > 1 && net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, held)) {
                client.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, selected,
                        net.minecraft.world.inventory.ContainerInput.SWAP, player);
                no.sindrens.printeraddon.build.AutoBuild.INSTANCE.inventory.moved();
                no.sindrens.printeraddon.PrinterAddon.trace("Last {} in hand: swapped in a full stack before clicking", held.getItem());
                return true;
            }
        }
        return false;
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void printer_addon$afterClick(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        try {
            printer_addon$lookBack(client, player, hand, hit, ci);
        } catch (RuntimeException | LinkageError e) {
            no.sindrens.printeraddon.build.AutoBuild.INSTANCE.error("after the printer's click", e);
        }
    }

    @Unique
    private void printer_addon$lookBack(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        if (Configs.enabled() && client.level != null && printer_addon$sequenceBefore >= 0
                && ((ClientLevelAccessor) client.level).printer_addon$predictions().currentSequence() > printer_addon$sequenceBefore) {
            // The click's first packet ("use item on") places the block, and the server answers it when it's done (see
            // ServerAcks). Not the second one ("use item"): the player's server left that one unanswered at times, and
            // with no click after it (a trip to the vaults next) placing waited 5 to 15 s for an answer that never came
            no.sindrens.printeraddon.build.ServerAcks.clicked(printer_addon$sequenceBefore + 1);
        }
        if (printer_addon$sneaked) {
            // Back to what the keyboard says
            printer_addon$sneaked = false;
            player.input.keyPresses = printer_addon$keys;
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerInputPacket(printer_addon$keys));
        }
        if (!printer_addon$turned) {
            return;
        }
        printer_addon$turned = false;
        player.setYRot(printer_addon$yaw);
        player.setXRot(printer_addon$pitch);
        player.yRotO = printer_addon$yawO;
        player.xRotO = printer_addon$pitchO;
    }
}
