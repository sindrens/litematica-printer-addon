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

        // Speed settings: changing one of the custom speeds switches the preset to Custom (else it did nothing while a
        // preset was picked); picking Custom starts from the preset that was picked before
        no.sindrens.printeraddon.config.Configs.Speed.linkCustomToPreset();

        // What the server allows, and the server commands kept per server
        ServerPolicy.register();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) ->
                mc.execute(() -> {
                    no.sindrens.printeraddon.config.ServerProfiles.onJoin(mc);
                    no.sindrens.printeraddon.compat.Compat.checkOnce();
                }));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) ->
                no.sindrens.printeraddon.config.ServerProfiles.onLeave());

        // Closing the game during a run ends it there, so its log is whole (sent on the next start, see LogShare)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            try {
                no.sindrens.printeraddon.build.AutoBuild.INSTANCE.gameClosing(mc);
            } catch (RuntimeException | LinkageError e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't end the run as the game closed", e);
            }
        });

        PrinterAddon.LOGGER.info("{} initialized", PrinterAddon.MOD_NAME);
    }
}
