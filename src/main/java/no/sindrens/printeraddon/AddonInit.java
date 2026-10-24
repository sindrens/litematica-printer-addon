package no.sindrens.printeraddon;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.event.ClientTick;
import no.sindrens.printeraddon.event.InputHandler;
import no.sindrens.printeraddon.event.KeyCallbacks;
import no.sindrens.printeraddon.gui.ConfigGui;

public class AddonInit implements IInitializationHandler {
    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(PrinterAddon.MOD_ID, new Configs());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(PrinterAddon.MOD_ID, PrinterAddon.MOD_NAME, ConfigGui::new));

        InputEventHandler.getKeybindManager().registerKeybindProvider(new InputHandler());
        TickHandler.getInstance().registerClientTickHandler(new ClientTick());
        KeyCallbacks.init();
        no.sindrens.printeraddon.gui.BuildHud.register();
        // Switched off in the settings list: everything stops, like with J+A
        no.sindrens.printeraddon.config.Configs.Generic.ADDON_ENABLED.setValueChangeCallback(config -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            no.sindrens.printeraddon.build.AutoBuild build = no.sindrens.printeraddon.build.AutoBuild.INSTANCE;
            if (!config.getBooleanValue() && mc.player != null) {
                if (build.isRunning()) {
                    // Off during auto build: like J+A, the building items are put away first, then it's off
                    config.setBooleanValue(true);
                    build.setAddonEnabled(mc, false);
                } else {
                    build.cancelPutAway();
                    build.stopEverything(mc);
                }
            }
        });

        PrinterAddon.LOGGER.info("{} initialized", PrinterAddon.MOD_NAME);
    }
}
