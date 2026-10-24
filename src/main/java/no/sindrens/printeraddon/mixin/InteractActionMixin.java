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

    @Unique
    private static boolean printer_addon$sneaked;
    @Unique
    private static net.minecraft.world.entity.player.Input printer_addon$keys;

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void printer_addon$faceForClick(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
        printer_addon$turned = false;
        printer_addon$sneaked = false;
        // The stack ran out between the printer's prepare and this click: an empty hand places nothing and opens a
        // hopper or chest it clicks on (sneaking doesn't help with empty hands). Skip it; the printer tries again
        if (Configs.enabled() && player.getItemInHand(hand).isEmpty()) {
            no.sindrens.printeraddon.PrinterAddon.trace("Skipped a printer click with an empty hand at {}", hit.getBlockPos().toShortString());
            if (no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()) {
                no.sindrens.printeraddon.build.AutoBuild.INSTANCE.speed.emptyHand(hit.getBlockPos());
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
    private void printer_addon$lookBack(Minecraft client, LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfo ci) {
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
