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
            if (key == Hotkeys.LINK_DISCORD.getKeybind()) {
                Minecraft m = Minecraft.getInstance();
                if (m.player != null) {
                    no.sindrens.printeraddon.util.Chat.info(no.sindrens.printeraddon.discord.DiscordLink.isLinked()
                            ? "Linked with Discord as " + no.sindrens.printeraddon.discord.DiscordLink.linkedTo() + ". To link again: type the new code."
                            : "Type the code from §b/link§r on Discord (looks like PRINT-AB12) and press Enter.");
                    fi.dy.masa.malilib.gui.GuiBase.openGui(new net.minecraft.client.gui.screens.ChatScreen("/printeraddon link PRINT-", false));
                }
                return true;
            }
            if (key == Hotkeys.BUILD_MENU.getKeybind()) {
                GuiBase.openGui(ConfigGui.buildMenu());
                return true;
            }

            if (mc.player == null) {
                return false;
            }

            AutoBuild build = AutoBuild.INSTANCE;

            if (key == Hotkeys.TOGGLE_ADDON.getKeybind()) {
                build.setAddonEnabled(mc, !no.sindrens.printeraddon.config.Configs.enabled());
                return true;
            }
            // Add-on off: only the settings, the menu and the switch itself
            if (!no.sindrens.printeraddon.config.Configs.enabled()) {
                return false;
            }
            if (key == Hotkeys.BUILD_REPORT.getKeybind()) {
                no.sindrens.printeraddon.build.BuildReport.show(mc);
            } else if (key == Hotkeys.SHOW_COST.getKeybind()) {
                no.sindrens.printeraddon.build.CostEstimate.show(mc);
            } else if (key == Hotkeys.TOGGLE_AUTO_BUILD.getKeybind()) {
                build.toggle(mc);
            } else if (key == Hotkeys.START_AUTO_BUILD.getKeybind()) {
                build.startOnly(mc);
            } else if (key == Hotkeys.STOP_AUTO_BUILD.getKeybind()) {
                build.stopOnly(mc);
            } else if (key == Hotkeys.STOP_AFTER_LAYER.getKeybind()) {
                build.toggleStopAfterLayer(mc);
            } else if (key == Hotkeys.PAUSE_AUTO_BUILD.getKeybind()) {
                build.togglePause(mc);
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
                build.cancelPutAway();
                // Also no going back to the build later
                no.sindrens.printeraddon.build.Resume.cancel();
                no.sindrens.printeraddon.build.Reconnect.cancel();
            } else {
                return false;
            }

            return true;
        }
    }
}
