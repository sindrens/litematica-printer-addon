package no.sindrens.printeraddon.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Official build: auto build and Auto break move with the movement keys (see {@code Motion}), set right after the game
 * read the keyboard, so the game moves the player by its own rules, at a player's speed.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void printer_addon$drive(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.input != (Object) this) {
            return;
        }
        try {
            Vec2[] move = {this.moveVector};
            no.sindrens.printeraddon.build.Motion.drive(this, mc.player, move);
            this.moveVector = move[0];
        } catch (RuntimeException | LinkageError e) {
            no.sindrens.printeraddon.PrinterAddon.LOGGER.warn("[Printer Addon] movement keys", e);
        }
    }
}
