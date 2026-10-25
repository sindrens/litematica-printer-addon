package no.sindrens.printeraddon.event;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.shop.ShopBuyer;
import no.sindrens.printeraddon.shop.ShopScanner;

public class ClientTick implements IClientTickHandler {
    @Override
    public void onClientTick(Minecraft mc) {
        // An error in the add-on stops auto build and goes into the build log; the game doesn't crash for it
        try {
            tick(mc);
        } catch (RuntimeException | LinkageError e) {
            AutoBuild.INSTANCE.error("the tick", e);
        }
    }

    private void tick(Minecraft mc) {
        AutoBuild.clientTicks++;
        if (mc.player == null || mc.level == null) {
            ShopScanner.INSTANCE.cancel(null);
            ShopBuyer.INSTANCE.cancel(null);
            // Why it ended, and joining again planned (also when the game's own hook didn't tell)
            AutoBuild.INSTANCE.outOfWorld(mc);
            // Lost the connection or logged out: stop, but keep the resume file
            AutoBuild.INSTANCE.stopForDisconnect(mc);
            no.sindrens.printeraddon.build.Resume.onDisconnect();
            no.sindrens.printeraddon.build.ServerLag.reset();
            // Kicked or the server restarting: joining again when it's time
            no.sindrens.printeraddon.build.Reconnect.whileOut(mc);
            return;
        }
        AutoBuild.INSTANCE.inWorld(mc);
        no.sindrens.printeraddon.build.Reconnect.whileIn(mc);

        if (ShopScanner.INSTANCE.isRunning()) {
            ShopScanner.INSTANCE.tick(mc);
            return;
        }

        // A purchase started outside auto build (auto build ticks its own)
        if (ShopBuyer.INSTANCE.isRunning() && !AutoBuild.INSTANCE.isRunning()) {
            ShopBuyer.INSTANCE.tick(mc);
            return;
        }

        // Add-on off: nothing of it runs (Litematica and its printer work on their own)
        if (!no.sindrens.printeraddon.config.Configs.enabled()) {
            return;
        }
        no.sindrens.printeraddon.build.CostEstimate.tick(mc);
        // The printer never faster than interval 3, also when used by hand: at 1 and 2 the server kicks the player
        if (me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.getIntegerValue()
                < no.sindrens.printeraddon.build.SpeedTuner.MIN_INTERVAL) {
            me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.setIntegerValue(
                    no.sindrens.printeraddon.build.SpeedTuner.MIN_INTERVAL);
        }
        if (AutoBuild.INSTANCE.isRunning()) {
            no.sindrens.printeraddon.build.Resume.whileRunning(mc);
        } else {
            no.sindrens.printeraddon.build.Resume.whileIdle(mc);
        }
        AutoBuild.INSTANCE.tick(mc);
    }
}
