package no.sindrens.printeraddon;

import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PrinterAddon implements ClientModInitializer {
    public static final String MOD_ID = "printer_addon";
    public static final String MOD_NAME = "Printer Addon";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** A step of auto build: logged with "Debug log", said in chat with "Detailed chat". */
    public static void debug(String message, Object... args) {
        boolean log = no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.getBooleanValue();
        boolean chat = no.sindrens.printeraddon.config.Configs.Generic.DETAILED_CHAT.getBooleanValue();
        if (!log && !chat) {
            return;
        }
        String text = org.apache.logging.log4j.message.ParameterizedMessage.format(message, args);
        if (log) {
            LOGGER.info("[debug] {}", text);
        }
        if (chat) {
            no.sindrens.printeraddon.util.Chat.detail(text);
        }
    }

    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(new AddonInit());
    }
}
