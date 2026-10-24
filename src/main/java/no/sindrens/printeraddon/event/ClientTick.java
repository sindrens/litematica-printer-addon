package no.sindrens.printeraddon.event;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.shop.ShopBuyer;
import no.sindrens.printeraddon.shop.ShopScanner;

public class ClientTick implements IClientTickHandler {
    @Override
    public void onClientTick(Minecraft mc) {
        AutoBuild.clientTicks++;
        if (mc.player == null || mc.level == null) {
            ShopScanner.INSTANCE.cancel(null);
            ShopBuyer.INSTANCE.cancel(null);
            // Lost the connection or logged out: stop, but keep the resume file
            AutoBuild.INSTANCE.stopForDisconnect(mc);
            no.sindrens.printeraddon.build.Resume.onDisconnect();
            no.sindrens.printeraddon.build.ServerLag.reset();
            return;
        }

        if (ShopScanner.INSTANCE.isRunning()) {
            ShopScanner.INSTANCE.tick(mc);
            return;
        }

        // A purchase started outside auto build (auto build ticks its own)
        if (ShopBuyer.INSTANCE.isRunning() && !AutoBuild.INSTANCE.isRunning()) {
            ShopBuyer.INSTANCE.tick(mc);
            return;
        }

        no.sindrens.printeraddon.build.CostEstimate.tick(mc);
        if (AutoBuild.INSTANCE.isRunning()) {
            no.sindrens.printeraddon.build.Resume.whileRunning(mc);
        } else {
            no.sindrens.printeraddon.build.Resume.whileIdle(mc);
        }
        AutoBuild.INSTANCE.tick(mc);
    }
}
