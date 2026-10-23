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
        public static final ConfigBoolean AUTO_BUILD = new ConfigBoolean("autoBuild", false,
                "Main switch. Turns on the printer and lets the add-on break wrong blocks and step through layers.",
                "Auto build");
        public static final ConfigOptionList SPEED_PRESET = new ConfigOptionList("speedPreset", SpeedPreset.NORMAL,
                "How fast everything runs. Safe / Normal / Fast, or Custom to use the values in the Speed tab.",
                "Speed preset");
        public static final ConfigBoolean APPLY_SPEED_TO_PRINTER = new ConfigBoolean("applySpeedToPrinter", true,
                "Also set Litematica Printer's printing interval from the speed preset.",
                "Apply speed to printer");
        public static final ConfigBoolean CHAT_MESSAGES = new ConfigBoolean("chatMessages", true,
                "Show status messages in chat (layer done, stuck, scan results...).",
                "Chat messages");

        public static final ConfigBoolean TURN_VIEW_FOR_PRINTER = new ConfigBoolean("turnViewForPrinter", true,
                "Turn your view the way the printer places, so observers, pistons, stairs etc. always face the right way.\n"
                        + "Without it the server sometimes uses the direction you're looking instead (Minecraft 26.2).",
                "Turn view for printer");
        public static final ConfigBoolean DEBUG_LOG = new ConfigBoolean("debugLog", false,
                "Write every step (which block is broken, why it stopped...) to the game log (logs/latest.log).\n"
                        + "Useful when something doesn't work.",
                "Debug log");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_BUILD, SPEED_PRESET, APPLY_SPEED_TO_PRINTER, TURN_VIEW_FOR_PRINTER, CHAT_MESSAGES, DEBUG_LOG);
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
                "Turn the camera towards the block being broken. Needed on servers with strict anticheat.",
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
        public static final ConfigBoolean EXTRA_BLOCKS_BLOCK_LAYER = new ConfigBoolean("extraBlocksBlockLayer", false,
                "Count blocks where the schematic has air (terrain, stray blocks) as unfinished.\n"
                        + "Off: only blocks the schematic needs count. Use the 'Clear extra blocks' key to remove them.",
                "Extra blocks keep layer open");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_LAYER, LAYER_CHECK_INTERVAL, STUCK_REPORT_SECONDS, EXTRA_BLOCKS_BLOCK_LAYER);
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

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                STOP_ON_DAMAGE, STOP_NEAR_PLAYERS, NEAR_PLAYER_DISTANCE);
    }

    public static class Shop {
        public static final ConfigString SHOP_COMMAND = new ConfigString("shopCommand", "shop",
                "Command that opens the shop, without the slash.",
                "Shop command");
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
                SHOP_COMMAND, PROBE_BUY_SCREEN, MAX_PAGES, WAIT_TIMEOUT_TICKS);
    }

    public static class Speed {
        public static final ConfigInteger PRINTER_INTERVAL = new ConfigInteger("customPrinterInterval", 6, 1, 40,
                "Custom preset: the printer's printing interval (lower is faster).",
                "Custom: printer interval");
        public static final ConfigInteger BREAK_COOLDOWN = new ConfigInteger("customBreakCooldownTicks", 2, 0, 100,
                "Custom preset: ticks to wait after breaking a block before the next one.",
                "Custom: break cooldown (ticks)");
        public static final ConfigInteger SHOP_CLICK_DELAY = new ConfigInteger("customShopClickDelayTicks", 6, 1, 100,
                "Custom preset: ticks to wait between clicks in the shop menu.",
                "Custom: shop click delay (ticks)");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PRINTER_INTERVAL, BREAK_COOLDOWN, SHOP_CLICK_DELAY);

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
