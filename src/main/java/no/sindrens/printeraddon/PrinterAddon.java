package no.sindrens.printeraddon;

import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PrinterAddon implements ClientModInitializer {
    public static final String MOD_ID = "printer_addon";
    public static final String MOD_NAME = "Printer Addon";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** Fine detail: only in the log (with "Debug log"), never in chat. */
    public static void trace(String message, Object... args) {
        boolean log = no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.getBooleanValue();
        // The build log has these only with "Debug log" too (see BuildLog#debugOnly)
        if (!log) {
            return;
        }
        String text = org.apache.logging.log4j.message.ParameterizedMessage.format(message, args);
        no.sindrens.printeraddon.build.BuildLog.line("DETAIL", text);
        if (log) {
            LOGGER.info("[debug] {}", text);
        }
    }

    /** A step of auto build: logged with "Debug log", said in chat with "Detailed chat". */
    public static void debug(String message, Object... args) {
        boolean log = no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.getBooleanValue();
        boolean chat = no.sindrens.printeraddon.config.Configs.Generic.DETAILED_CHAT.getBooleanValue();
        if (!log && !chat) {
            return;
        }
        String text = org.apache.logging.log4j.message.ParameterizedMessage.format(message, args);
        no.sindrens.printeraddon.build.BuildLog.line("STEP", text);
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
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> no.sindrens.printeraddon.discord.LinkCommands.register(dispatcher));
    }
}
