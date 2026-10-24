package no.sindrens.printeraddon.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import no.sindrens.printeraddon.PrinterAddon;

import java.nio.file.Files;
import java.nio.file.Path;

public class Configs implements IConfigHandler {
    private static final String CONFIG_FILE_NAME = PrinterAddon.MOD_ID + ".json";

    public static class Generic {
        public static final ConfigBoolean ADDON_ENABLED = new ConfigBoolean("addonEnabled", true,
                "Turns the whole add-on on or off. Off: Litematica Printer works exactly as without the add-on\n"
                        + "(no view turning, no build order, no breaking). Hotkey J+A.",
                "Add-on enabled");
        public static final ConfigBoolean AUTO_BUILD = new ConfigBoolean("autoBuild", false,
                "Main switch. Turns on the printer and lets the add-on break wrong blocks and step through layers.",
                "Auto build");
        public static final ConfigOptionList SPEED_PRESET = new ConfigOptionList("speedPreset", SpeedPreset.NORMAL,
                "How fast everything runs. Safe / Normal / Fast, or Custom to use the values in the Speed tab.",
                "Speed preset");
        public static final ConfigBoolean APPLY_SPEED_TO_PRINTER = new ConfigBoolean("applySpeedToPrinter", true,
                "During auto build, set Litematica Printer's settings for reliable building: interval from the speed preset (12),\n"
                        + "range to your reach (4.5 in survival), rotate and interact blocks on. Your own values come back when it stops.",
                "Set printer settings");
        public static final ConfigBoolean SHOW_HUD = new ConfigBoolean("showHud", true,
                "During auto build, show a small box at the top left: layer, blocks left, time to go, speed, what's missing.",
                "Show build status on screen");
        public static final ConfigBoolean RESUME_AFTER_REJOIN = new ConfigBoolean("resumeAfterRejoin", true,
                "If auto build was running when you logged out or lost the connection, start it again 10 seconds after\n"
                        + "rejoining the same server with the same schematic. It goes on from the first unfinished layer.",
                "Resume after rejoining");
        public static final ConfigInteger BUILD_RENDER_DISTANCE = new ConfigInteger("buildRenderDistance", 0, 0, 32,
                "Render distance (chunks) while auto build runs, for less lag on a slow computer. Your own setting comes\n"
                        + "back when it stops. 0 = don't change it.",
                "Render distance while building");
        public static final ConfigBoolean CHAT_MESSAGES = new ConfigBoolean("chatMessages", true,
                "Show status messages in chat (layer done, stuck, scan results...).",
                "Chat messages");

        public static final ConfigBoolean TURN_VIEW_FOR_PRINTER = new ConfigBoolean("turnViewForPrinter", true,
                "Give the server the printer's direction when it clicks, so observers, pistons, hoppers, stairs etc. face\n"
                        + "the right way (in 26.2 the server takes the direction you look in). Your camera stays free: it only\n"
                        + "faces that way during the click itself, within one tick.",
                "Right direction for printer");
        public static final ConfigBoolean DETAILED_CHAT = new ConfigBoolean("detailedChat", false,
                "Say in chat every step auto build takes: walking, flying, breaking, air placing...",
                "Detailed chat");
        public static final ConfigBoolean STATUS_FILE = new ConfigBoolean("statusFile", false,
                "Write what auto build is doing to config/printer_addon/status.json every 2 seconds\n"
                        + "(next block, blocks left, what is missing), for a live view outside the game.",
                "Write status file");
        public static final ConfigBoolean DEBUG_LOG = new ConfigBoolean("debugLog", false,
                "Write every step (which block is broken, why it stopped...) to the game log (logs/latest.log).\n"
                        + "Useful when something doesn't work.",
                "Debug log");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ADDON_ENABLED, AUTO_BUILD, SHOW_HUD, RESUME_AFTER_REJOIN, BUILD_RENDER_DISTANCE, SPEED_PRESET, APPLY_SPEED_TO_PRINTER, TURN_VIEW_FOR_PRINTER, CHAT_MESSAGES, DETAILED_CHAT, STATUS_FILE, DEBUG_LOG);
    }

    public static class Breaking {
        public static final ConfigBoolean BREAK_WRONG_BLOCKS = new ConfigBoolean("breakWrongBlocks", true,
                "While auto build is on, break blocks that don't match the schematic so the printer can place the right one.",
                "Break wrong blocks");
        public static final ConfigBoolean BREAK_WRONG_STATE = new ConfigBoolean("breakWrongState", true,
                "Also break the right block when it faces the wrong way or has the wrong half/type (stairs, observers, logs, slabs).\n"
                        + "Things the printer can fix by clicking (repeater delay, comparator mode, doors, levers) are left to the printer.",
                "Break wrong direction/state");
        public static final ConfigDouble BREAK_RANGE = new ConfigDouble("breakRange", 4.5, 1.0, 6.0,
                "How far away blocks can be broken. Also limited by the server's reach.",
                "Break range");
        public static final ConfigInteger MIN_TOOL_DURABILITY = new ConfigInteger("minToolDurability", 10, 0, 2000,
                "Never use a tool that has this much durability left or less, so tools never break.",
                "Protect tools: min durability");
        public static final ConfigInteger BREAK_TIMEOUT_SECONDS = new ConfigInteger("breakTimeoutSeconds", 15, 1, 300,
                "Give up on a block if it isn't broken after this many seconds (e.g. the server cancels it).",
                "Break timeout (seconds)");
        public static final ConfigInteger WRONG_FOR_TICKS = new ConfigInteger("wrongForTicks", 10, 0, 200,
                "A block must stay wrong this long (20 ticks = 1 second) before it's broken.\n"
                        + "Right after placing, the game briefly shows its own guess of the block until the server confirms it;\n"
                        + "raise this on servers with high ping.",
                "Wait before breaking (ticks)");
        public static final ConfigInteger MAX_BREAKS_PER_BLOCK = new ConfigInteger("maxBreaksPerBlock", 3, 1, 50,
                "Stop breaking a spot after this many tries if it keeps coming back wrong, and say so in chat.",
                "Max breaks per spot");
        public static final ConfigBoolean LOOK_AT_BLOCKS = new ConfigBoolean("lookAtBlocks", false,
                "Tell the server you look at the block being broken (your camera stays free). For servers with strict anticheat.",
                "Look at blocks when breaking");
        public static final ConfigBoolean SKIP_CONTAINERS = new ConfigBoolean("skipContainers", true,
                "Never break chests, barrels, hoppers, shulker boxes, furnaces etc., even if they are wrong.",
                "Never break containers");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                BREAK_WRONG_BLOCKS, BREAK_WRONG_STATE, BREAK_RANGE, MIN_TOOL_DURABILITY, BREAK_TIMEOUT_SECONDS,
                WRONG_FOR_TICKS, MAX_BREAKS_PER_BLOCK, LOOK_AT_BLOCKS, SKIP_CONTAINERS);
    }

    public static class Layers {
        public static final ConfigBoolean AUTO_LAYER = new ConfigBoolean("autoLayer", true,
                "While auto build is on, build one Y layer at a time from the bottom and go to the next layer when it's done.",
                "Auto layer");
        public static final ConfigInteger LAYER_CHECK_INTERVAL = new ConfigInteger("layerCheckIntervalTicks", 10, 1, 200,
                "How often (in ticks) to check if the current layer is finished.",
                "Layer check interval (ticks)");
        public static final ConfigInteger STUCK_REPORT_SECONDS = new ConfigInteger("stuckReportSeconds", 30, 5, 600,
                "If the layer hasn't changed for this long, list in chat what is left and why.",
                "Report stuck layer after (seconds)");
        public static final ConfigOptionList QUICK_PLACE = new ConfigOptionList("quickPlace", QuickPlaceMode.WHEN_PRINTER_CANT,
                "Blocks the add-on places itself with one click (one per printer interval), working out which way to look and\n"
                        + "which side to click so they come out exactly as in the schematic.\n"
                        + "Only what the printer can't place (default): e.g. the first row of hoppers with nothing in front of them.\n"
                        + "All: everything it can predict, much faster (the printer needs 2-4 intervals per block).\n"
                        + "Never: printer only.",
                "Quick place");
        public static final ConfigInteger SKIP_STUCK_SECONDS = new ConfigInteger("skipStuckSeconds", 20, 5, 3600,
                "If blocks you have the items for still can't be placed after this long (e.g. two rails side by side\n"
                        + "that the game always connects), go on to the next layer and list them at the end to do by hand.\n"
                        + "Blocks you don't have the items for are waited for.",
                "Skip unplaceable blocks after (seconds)");
        public static final ConfigBoolean EXTRA_BLOCKS_BLOCK_LAYER = new ConfigBoolean("extraBlocksBlockLayer", false,
                "Count blocks where the schematic has air (terrain, stray blocks) as unfinished.\n"
                        + "Off: only blocks the schematic needs count. Use the 'Clear extra blocks' key to remove them.",
                "Extra blocks keep layer open");

        public static final ConfigBoolean PLANT_WITH_TILLING = new ConfigBoolean("plantWithTilling", true,
                "When a layer has farmland, build it together with the layer above, so seeds and stems are planted\n"
                        + "right after tilling. Farmland without a crop and without water dries back to dirt.",
                "Plant right after tilling");

        public static final ConfigBoolean USE_AIR_PLACE = new ConfigBoolean("useAirPlace", true,
                "When every missing block within reach floats (nothing next to it to place it against), turn on the\n"
                        + "printer's 'printInAir' for exactly one block, then off again. Blocks it puts in the wrong spot are broken.",
                "Use printer's air place");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_LAYER, QUICK_PLACE, LAYER_CHECK_INTERVAL, STUCK_REPORT_SECONDS, SKIP_STUCK_SECONDS, EXTRA_BLOCKS_BLOCK_LAYER,
                PLANT_WITH_TILLING,
                USE_AIR_PLACE);
    }

    public static QuickPlaceMode quickPlace() {
        return (QuickPlaceMode) Layers.QUICK_PLACE.getOptionListValue();
    }

    /** True when the add-on is switched on. */
    public static boolean enabled() {
        return Generic.ADDON_ENABLED.getBooleanValue();
    }

    public static class Movement {
        public static final ConfigOptionList MOVE_MODE = new ConfigOptionList("moveMode", MoveMode.FLY,
                "How auto build gets you to the next unfinished blocks: fly (fastest: always above the blocks being built),\n"
                        + "walk, walk and fly when needed, or not at all.",
                "Movement");
        public static final ConfigString FLY_COMMAND = new ConfigString("flyCommand", "fly",
                "Command (without the slash) sent once if flying is needed but not allowed yet. Empty: never send one.",
                "Fly command");
        public static final ConfigInteger HOVER_HEIGHT = new ConfigInteger("hoverHeight", 1, 1, 4,
                "When flying, hover this many blocks above the layer being built (1 = right above it, the most reach).",
                "Hover height");
        public static final ConfigInteger WORK_WAIT_SECONDS = new ConfigInteger("workWaitSeconds", 4, 1, 60,
                "If nothing within reach gets done for this long (missing item, printer can't place it...),\n"
                        + "move on and come back to those blocks later.",
                "Wait for blocks in reach (seconds)");
        public static final ConfigInteger MAX_WALK_DISTANCE = new ConfigInteger("maxWalkDistance", 48, 8, 128,
                "Farthest the pathfinder looks for a walking route. Farther away: fly (if allowed).",
                "Max walk distance");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                MOVE_MODE, FLY_COMMAND, HOVER_HEIGHT, WORK_WAIT_SECONDS, MAX_WALK_DISTANCE);

        public static MoveMode mode() {
            return (MoveMode) MOVE_MODE.getOptionListValue();
        }
    }

    public static class Safety {
        public static final ConfigBoolean STOP_ON_DAMAGE = new ConfigBoolean("stopOnDamage", true,
                "Stop auto build when you take damage.",
                "Stop on damage");
        public static final ConfigBoolean STOP_NEAR_PLAYERS = new ConfigBoolean("stopNearPlayers", true,
                "Stop auto build when another player comes close.",
                "Stop near other players");
        public static final ConfigInteger NEAR_PLAYER_DISTANCE = new ConfigInteger("nearPlayerDistance", 16, 2, 128,
                "Distance in blocks for 'Stop near other players'.",
                "Near player distance");

        public static final ConfigBoolean AUTO_EAT = new ConfigBoolean("autoEat", true,
                "During auto build, eat when the food bar gets low (starving hurts, and damage stops auto build).\n"
                        + "Never eats rotten flesh, spider eyes, poisonous potatoes, pufferfish, chorus fruit, raw chicken or golden apples.",
                "Eat when hungry");
        public static final ConfigInteger EAT_BELOW = new ConfigInteger("eatBelow", 14, 1, 19,
                "Eat when the food bar is at this or lower (20 is full, every drumstick is 2).",
                "Eat at food level");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                STOP_ON_DAMAGE, STOP_NEAR_PLAYERS, NEAR_PLAYER_DISTANCE, AUTO_EAT, EAT_BELOW);
    }

    public static class Shop {
        public static final ConfigString SHOP_COMMAND = new ConfigString("shopCommand", "shop",
                "Command that opens the shop, without the slash.",
                "Shop command");
        public static final ConfigBoolean FETCH_FROM_CHESTS = new ConfigBoolean("fetchFromChests", true,
                "Before buying, take missing items from the chests, barrels and shulker boxes you linked (J+B, Build tab:\n"
                        + "look at one and press 'Link chest'). No other chest is ever opened. They have to be loaded (in view distance).",
                "Take from linked chests first");
        public static final ConfigBoolean AUTO_BUY = new ConfigBoolean("autoBuy", true,
                "During auto build, buy what the current layer is missing from the shop (as much as fits in the inventory).\n"
                        + "Needs a shop scan of the server first (J+S). No spending limit.",
                "Buy missing items");
        public static final ConfigString SHOP_SECTION = new ConfigString("shopSection", "minecraft:paper",
                "Which button in the /shop main menu leads to the money shop. Only that one is opened.\n"
                        + "An item id (minecraft:paper) or part of the button's name. Empty = open every button.",
                "Money shop button");
        public static final ConfigBoolean PROBE_BUY_SCREEN = new ConfigBoolean("probeBuyScreen", true,
                "During a scan, left-click the cheapest item once to read the buy screen (amount buttons, confirm button),\n"
                        + "then close it without confirming. If the shop buys instantly on click, this buys 1 of that item.",
                "Read the buy screen during scan");
        public static final ConfigInteger MAX_PAGES = new ConfigInteger("maxPagesPerCategory", 50, 1, 500,
                "Stop scanning a category after this many pages.",
                "Max pages per category");
        public static final ConfigInteger WAIT_TIMEOUT_TICKS = new ConfigInteger("shopWaitTimeoutTicks", 80, 10, 600,
                "How long to wait for the server to open or change a shop menu before giving up (20 ticks = 1 second).",
                "Shop menu timeout (ticks)");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                FETCH_FROM_CHESTS, AUTO_BUY, SHOP_COMMAND, SHOP_SECTION, PROBE_BUY_SCREEN, MAX_PAGES, WAIT_TIMEOUT_TICKS);
    }

    public static class Speed {
        public static final ConfigBoolean AUTO_SPEED = new ConfigBoolean("autoSpeed", true,
                "Find the fastest printer interval that still places right: start at the preset's interval, go one tick\n"
                        + "faster after a run of correct blocks, two ticks slower as soon as a block comes out wrong.",
                "Automatic speed");
        public static final ConfigInteger AUTO_SPEED_MIN_INTERVAL = new ConfigInteger("autoSpeedMinInterval", 1, 1, 40,
                "Automatic speed never goes below this printer interval. It only gets there while every block comes out\n"
                        + "right, and slows down again at the first wrong one. At 2 it places at most about 5 blocks a second\n"
                        + "(every block takes at least two printer actions); at 1 about twice that. Raise it if a server complains.",
                "Automatic speed: fastest interval");
        public static final ConfigInteger LAG_PAUSE_TPS = new ConfigInteger("lagPauseTps", 12, 0, 19,
                "On a server: stop placing while the server runs below this many ticks per second (20 is normal),\n"
                        + "because clicks then get answered late and blocks come out wrong. 0 = never wait.",
                "Wait when server TPS below");
        public static final ConfigInteger AUTO_SPEED_STEP_BLOCKS = new ConfigInteger("autoSpeedStepBlocks", 10, 5, 1000,
                "Automatic speed goes one tick faster after this many correct blocks in a row.",
                "Automatic speed: blocks per step");
        public static final ConfigInteger PRINTER_INTERVAL = new ConfigInteger("customPrinterInterval", 12, 1, 40,
                "Custom preset: the printer's printing interval (lower is faster). Below 12 the printer often places blocks wrong.",
                "Custom: printer interval");
        public static final ConfigInteger BREAK_COOLDOWN = new ConfigInteger("customBreakCooldownTicks", 2, 0, 100,
                "Custom preset: ticks to wait after breaking a block before the next one.",
                "Custom: break cooldown (ticks)");
        public static final ConfigInteger SHOP_CLICK_DELAY = new ConfigInteger("customShopClickDelayTicks", 6, 1, 100,
                "Custom preset: ticks to wait between clicks in the shop menu.",
                "Custom: shop click delay (ticks)");
        public static final ConfigDouble WALK_SPEED = new ConfigDouble("customWalkSpeed", 0.21, 0.05, 0.4,
                "Custom preset: walking speed in blocks per tick (vanilla walking is about 0.21).",
                "Custom: walk speed");
        public static final ConfigDouble FLY_SPEED = new ConfigDouble("customFlySpeed", 0.55, 0.1, 2.0,
                "Custom preset: flying speed in blocks per tick (vanilla flying is about 0.55).",
                "Custom: fly speed");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_SPEED, AUTO_SPEED_MIN_INTERVAL, AUTO_SPEED_STEP_BLOCKS, LAG_PAUSE_TPS, PRINTER_INTERVAL, BREAK_COOLDOWN, SHOP_CLICK_DELAY, WALK_SPEED, FLY_SPEED);

        public static double walkSpeed() {
            SpeedPreset preset = preset();
            return preset == SpeedPreset.CUSTOM ? WALK_SPEED.getDoubleValue() : preset.walkSpeed;
        }

        public static double flySpeed() {
            SpeedPreset preset = preset();
            return preset == SpeedPreset.CUSTOM ? FLY_SPEED.getDoubleValue() : preset.flySpeed;
        }

        public static SpeedPreset preset() {
            return (SpeedPreset) Generic.SPEED_PRESET.getOptionListValue();
        }

        public static int printerInterval() {
            SpeedPreset preset = preset();
            return preset == SpeedPreset.CUSTOM ? PRINTER_INTERVAL.getIntegerValue() : preset.printerInterval;
        }

        public static int breakCooldownTicks() {
            SpeedPreset preset = preset();
            return preset == SpeedPreset.CUSTOM ? BREAK_COOLDOWN.getIntegerValue() : preset.breakCooldownTicks;
        }

        public static int shopClickDelayTicks() {
            SpeedPreset preset = preset();
            return preset == SpeedPreset.CUSTOM ? SHOP_CLICK_DELAY.getIntegerValue() : preset.shopClickDelayTicks;
        }
    }

    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            JsonElement element = JsonUtils.parseJsonFile(configFile);

            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
                ConfigUtils.readConfigBase(root, "Breaking", Breaking.OPTIONS);
                ConfigUtils.readConfigBase(root, "Layers", Layers.OPTIONS);
                ConfigUtils.readConfigBase(root, "Movement", Movement.OPTIONS);
                ConfigUtils.readConfigBase(root, "Safety", Safety.OPTIONS);
                ConfigUtils.readConfigBase(root, "Shop", Shop.OPTIONS);
                ConfigUtils.readConfigBase(root, "Speed", Speed.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            }
        }

        // Never start building on its own after a restart
        Generic.AUTO_BUILD.setBooleanValue(false);
    }

    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectory();

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Breaking", Breaking.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Layers", Layers.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Movement", Movement.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Safety", Safety.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Shop", Shop.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Speed", Speed.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
        JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
    }

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }
}
