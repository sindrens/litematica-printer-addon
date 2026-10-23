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

        PrinterAddon.LOGGER.info("{} initialized", PrinterAddon.MOD_NAME);
    }
}
