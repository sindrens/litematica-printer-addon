package no.sindrens.printeraddon.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

/** Default keys use J as a prefix (hold J, press the second key). J isn't used by vanilla. */
public class Hotkeys {
    public static final ConfigHotkey OPEN_SETTINGS = new ConfigHotkey("openSettings", "J,C",
            "Open the Printer Addon settings.", "Open settings");
    public static final ConfigHotkey TOGGLE_AUTO_BUILD = new ConfigHotkey("toggleAutoBuild", "J,B",
            "Start / stop auto build.", "Start / stop auto build");
    public static final ConfigHotkey FIX_WRONG_NOW = new ConfigHotkey("fixWrongNow", "J,F",
            "Break all wrong blocks within reach now (in the current layer range), even when auto build is off.",
            "Fix wrong blocks around me");
    public static final ConfigHotkey CLEAR_EXTRA_BLOCKS = new ConfigHotkey("clearExtraBlocks", "J,X",
            "Break blocks within reach where the schematic has air (terrain, stray blocks), in the current layer range.",
            "Clear extra blocks around me");
    public static final ConfigHotkey NEXT_LAYER = new ConfigHotkey("nextLayer", "J,PAGE_UP",
            "Go one layer up.", "Next layer");
    public static final ConfigHotkey PREVIOUS_LAYER = new ConfigHotkey("previousLayer", "J,PAGE_DOWN",
            "Go one layer down.", "Previous layer");
    public static final ConfigHotkey SCAN_SHOP = new ConfigHotkey("scanShop", "J,S",
            "Scan /shop: reads every category and page and saves where each item is and what it costs.",
            "Scan shop");
    /** Works in menus too, so a shop scan can be stopped while the shop is open. */
    public static final ConfigHotkey STOP_ALL = new ConfigHotkey("stopAll", "J,Q",
            KeybindSettings.create(KeybindSettings.Context.ANY, KeyAction.PRESS, false, true, false, true),
            "Stop everything (auto build, breaking, shop scan). Also works while a menu is open.", "Stop everything");

    public static final ImmutableList<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_SETTINGS, TOGGLE_AUTO_BUILD, FIX_WRONG_NOW, CLEAR_EXTRA_BLOCKS, NEXT_LAYER, PREVIOUS_LAYER,
            SCAN_SHOP, STOP_ALL);
}
