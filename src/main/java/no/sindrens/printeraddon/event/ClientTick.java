package no.sindrens.printeraddon.event;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.shop.ShopScanner;

public class ClientTick implements IClientTickHandler {
    @Override
    public void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            ShopScanner.INSTANCE.cancel(null);
            AutoBuild.INSTANCE.stopEverything(mc);
            return;
        }

        if (ShopScanner.INSTANCE.isRunning()) {
            ShopScanner.INSTANCE.tick(mc);
            return;
        }

        AutoBuild.INSTANCE.tick(mc);
    }
}
