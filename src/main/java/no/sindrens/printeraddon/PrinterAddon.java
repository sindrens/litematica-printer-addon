package no.sindrens.printeraddon;

import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PrinterAddon implements ClientModInitializer {
    public static final String MOD_ID = "printer_addon";
    public static final String MOD_NAME = "Printer Addon";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(new AddonInit());
    }
}
