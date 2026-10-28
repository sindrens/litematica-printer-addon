package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.Edition;
import no.sindrens.printeraddon.ServerPolicy;

/**
 * Every movement of auto build and Auto break goes through here.
 * <p>
 * Mythian build (and the official one where the server allows faster movement, or in singleplayer): the wanted motion is
 * set on the player directly, as before.
 * <p>
 * Official build on a server: the wanted motion becomes key presses (forward, back, left, right, jump, sneak, sprint),
 * which the game then moves with by its own rules, so never faster than a player holding those keys
 * (see {@code KeyboardInputMixin}). The keys are what the server is told too, like a player's own.
 */
public final class Motion {
    /** How long a wanted motion lasts without being set again (ticks): then the keys are let go. */
    private static final int HOLD_TICKS = 3;
    /** Ground speed of a walking player with all forward (blocks a tick), and flying. */
    private static final double WALK_BASE = 0.2;
    private static final double FLY_BASE = 0.5;

    private static Vec3 wanted;
    private static int wantedLeft;
    private static boolean jump;
    private static int sneakLeft;

    private Motion() {
    }

    /** True when movement is done with keys (official build, on a server that didn't allow faster movement). */
    public static boolean keys() {
        return Edition.OFFICIAL && !ServerPolicy.fastMovementAllowed();
    }

    /** Moves the player with this motion (blocks a tick) this tick. */
    public static void set(LocalPlayer player, Vec3 motion) {
        if (keys()) {
            wanted = motion;
            wantedLeft = HOLD_TICKS;
        } else {
            player.setDeltaMovement(motion);
        }
    }

    public static void set(LocalPlayer player, double x, double y, double z) {
        set(player, new Vec3(x, y, z));
    }

    /** A jump (off the ground, or up while flying). */
    public static void jump(LocalPlayer player) {
        if (keys()) {
            jump = true;
        } else {
            Vec3 m = player.getDeltaMovement();
            player.setDeltaMovement(m.x, 0.42, m.z);
        }
    }

    /** Stop moving: with keys, the keys are let go and the game slows the player down as it does for anyone. */
    public static void stop(LocalPlayer player, boolean keepFalling) {
        if (keys()) {
            wanted = null;
            wantedLeft = 0;
            jump = false;
        } else {
            Vec3 m = player.getDeltaMovement();
            player.setDeltaMovement(0, keepFalling ? m.y : 0, 0);
        }
    }

    /** Hold the sneak key for this many ticks (a click against a chest or hopper, as a player would). */
    public static void sneak(int ticks) {
        sneakLeft = Math.max(sneakLeft, ticks);
    }

    public static boolean sneaking() {
        return sneakLeft > 0;
    }

    /**
     * Called by the keyboard input after it read the real keys: in key mode, while auto build or Auto break wants to move,
     * the keys for that motion. Nothing while an inventory or chest is open.
     */
    public static void drive(ClientInput input, LocalPlayer player, Vec2[] moveVector) {
        boolean sneakNow = sneakLeft > 0;
        if (sneakLeft > 0) {
            sneakLeft--;
        }
        if (!keys()) {
            wanted = null;
            jump = false;
            if (sneakNow && Edition.OFFICIAL) {
                Input k = input.keyPresses;
                input.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), true, k.sprint());
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean busy = AutoBuild.INSTANCE.isRunning() || AreaBreaker.INSTANCE.isRunning();
        // Never with an inventory or chest open (moving with one open is what anticheats look for); the chat or the
        // pause menu (the game alt-tabbed) don't stop it
        if (!busy || mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            wanted = null;
            jump = false;
            if (sneakNow) {
                Input k = input.keyPresses;
                input.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), true, k.sprint());
            }
            return;
        }
        if ((wanted == null || wantedLeft <= 0) && !jump) {
            // Nothing wanted: the player's own keys
            wanted = null;
            if (sneakNow) {
                Input k = input.keyPresses;
                input.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), true, k.sprint());
            }
            return;
        }
        boolean flying = player.getAbilities().flying;
        boolean forward = false, back = false, left = false, right = false, up = jump, down = false, sprint = false;
        Vec2 move = Vec2.ZERO;
        if (wanted != null && wantedLeft > 0) {
            wantedLeft--;
            double vx = wanted.x;
            double vz = wanted.z;
            double speed = Math.sqrt(vx * vx + vz * vz);
            if (speed > 0.01) {
                double yaw = Math.toRadians(player.getYRot());
                double sin = Math.sin(yaw);
                double cos = Math.cos(yaw);
                // The player's own directions: forward is (-sin, cos), left is (cos, sin)
                double f = (-vx * sin + vz * cos) / speed;
                double l = (vx * cos + vz * sin) / speed;
                double base = flying ? FLY_BASE : WALK_BASE;
                double strength = Math.min(1.0, speed / base);
                // Slow at the end of a way: a part of the key's push, as with a controller's stick
                move = new Vec2((float) (l * strength), (float) (f * strength));
                forward = f > 0.38;
                back = f < -0.38;
                left = l > 0.38;
                right = l < -0.38;
                sprint = forward && f > 0.9 && speed > base * 1.05;
            }
            if (flying) {
                up |= wanted.y > 0.08;
                down = wanted.y < -0.08;
            } else {
                up |= wanted.y > 0.05 && (player.onGround() || player.isInWater());
            }
        } else {
            wanted = null;
        }
        jump = false;
        input.keyPresses = new Input(forward, back, left, right, up, down || sneakNow, sprint);
        moveVector[0] = move;
    }
}
