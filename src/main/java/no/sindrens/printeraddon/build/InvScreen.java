package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import no.sindrens.printeraddon.config.Configs;

/**
 * Official build: items are moved in the inventory (hotbar refills, tool swaps, stacks put together) with the inventory
 * open, as a player has to, and never while moving (the movement keys are let go while a screen is open, see Motion).
 * The inventory closes again by itself after half a second without moves.
 */
public final class InvScreen {
    private static final int CLOSE_AFTER_TICKS = 10;

    private static InventoryScreen ours;
    private static int idleTicks;

    private InvScreen() {
    }

    public static boolean wanted() {
        return Configs.Generic.OPEN_INVENTORY_FOR_MOVES.getBooleanValue()
                && (AutoBuild.INSTANCE.isRunning() || AreaBreaker.INSTANCE.isRunning());
    }

    /** Right before a click in the player's own inventory: opens it, if nothing else is open. */
    public static void beforeClick(Minecraft mc, int containerId) {
        if (mc.player == null || containerId != mc.player.inventoryMenu.containerId || !wanted()) {
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen == null) {
            ours = new InventoryScreen(mc.player);
            mc.gui.setScreen(ours);
        }
        if (screen == null || screen == ours) {
            idleTicks = 0;
        }
    }

    /** True if the screen open is the inventory opened here (not one the player opened). */
    public static boolean isOurs(Minecraft mc) {
        return ours != null && mc.gui.screen() == ours;
    }

    /** Every tick: closes the inventory opened here once the moves are done. */
    public static void tick(Minecraft mc) {
        if (ours == null) {
            return;
        }
        if (mc.gui.screen() != ours) {
            ours = null;
            return;
        }
        if (++idleTicks >= CLOSE_AFTER_TICKS || !wanted()) {
            ours = null;
            if (mc.player != null) {
                mc.player.closeContainer();
            } else {
                mc.gui.setScreen(null);
            }
        }
    }
}
