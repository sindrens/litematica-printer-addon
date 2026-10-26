package no.sindrens.printeraddon.build;

import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.gui.StopPromptGui;

import javax.annotation.Nullable;

/**
 * With "Always send logs" off: after every auto build or Auto break run (finished, stopped by the player, an error, out
 * of items, a safety stop; not a disconnect, which picks up again), the player is asked whether its log may go to the
 * add-on's developer: Send log, Always send logs, Write what happened, or Not now (see StopPromptGui). Every log helps.
 * Asked as soon as no other screen is open (never over a shop, the chat or the death screen); a new run drops the
 * question. With it on (the default) the log goes by itself (see LogShare).
 */
public final class StopPrompt {
    /** Why the run ended, while the question waits for a free screen. */
    @Nullable
    private static String pendingWhy;
    /** "Auto build" or "Auto break". */
    private static String pendingWhat = "Auto build";

    private StopPrompt() {
    }

    static void ask(String why, String what) {
        pendingWhy = why;
        pendingWhat = what;
    }

    static void cancel() {
        pendingWhy = null;
    }

    /** Every client tick in a world: the question once nothing else is on the screen. */
    public static void tick(Minecraft mc) {
        // Not over another screen, and not while the vaults are being opened to put things away (a vault screen
        // would close the question)
        if (pendingWhy == null || mc.gui.screen() != null || mc.player == null || mc.level == null
                || AutoBuild.INSTANCE.isRunning() || AreaBreaker.INSTANCE.isRunning() || AutoBuild.INSTANCE.busyAfterStop()
                || no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning() || no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.isRunning()) {
            return;
        }
        String why = pendingWhy;
        pendingWhy = null;
        GuiBase.openGui(new StopPromptGui(why, pendingWhat));
    }
}
