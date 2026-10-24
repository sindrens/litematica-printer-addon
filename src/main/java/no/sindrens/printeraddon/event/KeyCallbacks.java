package no.sindrens.printeraddon.event;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.config.Hotkeys;
import no.sindrens.printeraddon.gui.ConfigGui;
import no.sindrens.printeraddon.shop.ShopScanner;

public class KeyCallbacks {
    public static void init() {
        Callback callback = new Callback();
        for (var hotkey : Hotkeys.HOTKEY_LIST) {
            hotkey.getKeybind().setCallback(callback);
        }
    }

    private static class Callback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            Minecraft mc = Minecraft.getInstance();

            if (key == Hotkeys.OPEN_SETTINGS.getKeybind()) {
                GuiBase.openGui(new ConfigGui());
                return true;
            }

            if (mc.player == null) {
                return false;
            }

            AutoBuild build = AutoBuild.INSTANCE;

            if (key == Hotkeys.TOGGLE_ADDON.getKeybind()) {
                boolean on = !no.sindrens.printeraddon.config.Configs.Generic.ADDON_ENABLED.getBooleanValue();
                no.sindrens.printeraddon.config.Configs.Generic.ADDON_ENABLED.setBooleanValue(on);
                if (!on) {
                    build.stopEverything(mc);
                }
                no.sindrens.printeraddon.util.Chat.info("Printer Addon " + (on ? "\u00a7aon" : "\u00a7coff\u00a7r (the printer works on its own)") + ".");
            } else if (key == Hotkeys.SHOW_COST.getKeybind()) {
                no.sindrens.printeraddon.build.CostEstimate.show(mc);
            } else if (key == Hotkeys.TOGGLE_AUTO_BUILD.getKeybind()) {
                build.toggle(mc);
            } else if (key == Hotkeys.FIX_WRONG_NOW.getKeybind()) {
                build.fixWrongNow();
            } else if (key == Hotkeys.CLEAR_EXTRA_BLOCKS.getKeybind()) {
                build.clearExtraNow();
            } else if (key == Hotkeys.NEXT_LAYER.getKeybind()) {
                build.layers.moveLayer(1);
            } else if (key == Hotkeys.PREVIOUS_LAYER.getKeybind()) {
                build.layers.moveLayer(-1);
            } else if (key == Hotkeys.SCAN_SHOP.getKeybind()) {
                build.stopEverything(mc);
                ShopScanner.INSTANCE.start(mc);
            } else if (key == Hotkeys.STOP_ALL.getKeybind()) {
                ShopScanner.INSTANCE.cancel("stopped");
                no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.cancel("stopped");
                build.stopEverything(mc);
            } else {
                return false;
            }

            return true;
        }
    }
}
