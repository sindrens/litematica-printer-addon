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
    /** 2: the fastest automatic interval went from 2 to 1. */
    private static final int CONFIG_VERSION = 7;
    /** The one-time note in chat that every run's log goes to the developer by itself was shown (see LogShare). */
    public static boolean logsNoticeShown;

    public static class Generic {
        public static final ConfigBoolean ADDON_ENABLED = new ConfigBoolean("addonEnabled", true,
                "Turns the whole add-on on or off. Off: Litematica Printer works exactly as without the add-on\n"
                        + "(no view turning, no build order, no breaking). Hotkey J+A, or the button at the top of the J+B menu.",
                "Add-on on (off: plain Litematica Printer)");
        public static final ConfigBoolean AUTO_BUILD = new ConfigBoolean("autoBuild", false,
                "Main switch. Turns on the printer and lets the add-on break wrong blocks and step through layers.",
                "Auto build");
        public static final ConfigOptionList SPEED_PRESET = new ConfigOptionList("speedPreset", SpeedPreset.NORMAL,
                "How fast everything runs. Safe / Normal / Fast, or Custom to use the values in the Speed tab.",
                "Speed preset");
        public static final ConfigBoolean APPLY_SPEED_TO_PRINTER = new ConfigBoolean("applySpeedToPrinter", true,
                "During auto build, set Litematica Printer's own settings: the interval (starting at yours, then the automatic\n"
                        + "speed), rotate and interact blocks on. The range stays yours. Your own values come back when it stops.",
                "Set printer settings");
        public static final ConfigBoolean SHOW_HUD = new ConfigBoolean("showHud", true,
                "During auto build, show a small box at the top left: layer, blocks left, time to go, speed, what's missing.",
                "Build status on screen");
        public static final ConfigBoolean RESUME_AFTER_REJOIN = new ConfigBoolean("resumeAfterRejoin", true,
                "If auto build was running when you logged out or lost the connection, start it again 10 seconds after\n"
                        + "rejoining the same server with the same schematic. It goes on from the first unfinished layer.",
                "Go on after a restart or disconnect");
        public static final ConfigBoolean RECONNECT = new ConfigBoolean("reconnect", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "If the server kicks you or restarts while auto build runs, join it again by itself: 10 s after the\n"
                        + "'Disconnected' screen, then 20 s, 30 s and every minute, for half an hour. Click 'Back to server list'\n"
                        + "to cancel. Never after a ban, nor when you leave yourself; it stops after 3 kicks in 10 minutes.\n"
                        + "Once back at the build, it goes on (see 'Go on after a restart or disconnect').",
                "Join again after a kick or restart");
        public static final ConfigString BACK_COMMANDS = new ConfigString("backToBuildCommands", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "server Skyblock",
                "Commands that take you back to the build after joining again, or when the server moved you away (to a\n"
                        + "hub): run one by one, 8 seconds apart, then again every minute until you're back at the build (for\n"
                        + "half an hour). Comma between them, no /. Empty: none. On mythian, /server Skyblock puts you back\n"
                        + "exactly where you logged out. Stop auto build first (J+Q) when you leave on purpose, or it brings you back.",
                "Commands back to the build");
        public static final ConfigInteger BUILD_RENDER_DISTANCE = new ConfigInteger("buildRenderDistance", 0, 0, 32,
                "Render distance (chunks) while auto build runs, for less lag on a slow computer. Your own setting comes\n"
                        + "back when it stops. 0 = don't change it.",
                "Render distance while building");
        public static final ConfigBoolean CHAT_MESSAGES = new ConfigBoolean("chatMessages", true,
                "Show status messages in chat (layer done, stuck, scan results...).",
                "Chat messages");

        public static final ConfigBoolean TURN_VIEW_FOR_PRINTER = new ConfigBoolean("turnViewForPrinter", true,
                "Give the server the printer's direction when it clicks, so observers, pistons, hoppers, stairs etc. face\n"
                        + "the right way (in 26.2 the server takes the direction you look in). Mythian build: the camera only faces\n"
                        + "that way during the click itself. Official build: the camera really turns to each block clicked.",
                "Right direction for printer");
        public static final ConfigBoolean SHOW_ADVANCED_TABS = new ConfigBoolean("showAdvancedTabs",
                !no.sindrens.printeraddon.Edition.OFFICIAL,
                "Show every tab of settings (Advanced) instead of only Controls, Build, Auto break, Basic settings and\n"
                        + "Commands. The 'Advanced' button at the end of the tabs switches it too.",
                "Show advanced tabs");
        public static final ConfigBoolean OPEN_INVENTORY_FOR_MOVES = new ConfigBoolean("openInventoryForItemMoves",
                no.sindrens.printeraddon.Edition.OFFICIAL,
                "Open the inventory to move items in it (hotbar refills, tools, stacks), and stand still meanwhile, as a\n"
                        + "player has to. It closes again by itself. Off: items are moved without it (faster, less like a player).",
                "Open inventory to move items");
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
        public static final ConfigBoolean SHARE_LOGS = new ConfigBoolean("shareLogsAutomatically", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "Off by default. On: every auto build and Auto break run's log goes to the add-on's developer (a Discord channel)\n"
                        + "when the run ends, however it ended, to find bugs and make it faster. With it the debug: versions, mods,\n"
                        + "settings, PC, the game log without chat. The log has the build's coordinates, your inventory and\n"
                        + "balance, the server and your name; never chat, chest or vault spots. Off: you're asked after each run.",
                "Always send logs");
        public static final ConfigString LOG_CHANNEL = new ConfigString("logChannel", "",
                "Where shared logs go: a Discord webhook URL. Empty: the add-on developer's channel.",
                "Log channel (Discord webhook)");
        public static final ConfigBoolean TEXT_LOG = new ConfigBoolean("textBuildLog", false,
                "Also write each build log as plain text (the same name, .log) next to the .jsonl file.\n"
                        + "The .jsonl has the same lines; send that one when something went wrong.",
                "Text build log");

        public static final ConfigBoolean DISCORD_SYNC = new ConfigBoolean("discordSync", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "Linked with Discord (J+L): send live status and alerts to your control room on the Printer Addon Discord,\n"
                        + "and take Pause / Resume / Stop from there. Never sends chat, coordinates, chests or vaults.",
                "Discord sync");
        public static final ConfigString DISCORD_API = new ConfigString("discordApi", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : no.sindrens.printeraddon.discord.DiscordLink.DEFAULT_API,
                "The Printer Addon bot's address. Leave it as it is unless the developer says otherwise.",
                "Discord bot address");

        public static final ConfigString STOP_AT = new ConfigString("stopAt", "",
                "Stop the build (and Auto break) at this time of day, like 07:00 (your computer's clock). Once, then it's\n"
                        + "cleared. Empty: no stop time. Can be set from Discord too (/schedule).",
                "Stop at (time of day)");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ADDON_ENABLED, AUTO_BUILD, STOP_AT, SHOW_HUD, RESUME_AFTER_REJOIN, RECONNECT, BACK_COMMANDS, BUILD_RENDER_DISTANCE, SPEED_PRESET, APPLY_SPEED_TO_PRINTER, TURN_VIEW_FOR_PRINTER, OPEN_INVENTORY_FOR_MOVES, SHOW_ADVANCED_TABS, CHAT_MESSAGES, DETAILED_CHAT, STATUS_FILE, DEBUG_LOG, TEXT_LOG, SHARE_LOGS, LOG_CHANNEL, DISCORD_SYNC, DISCORD_API);
    }

    public static class Breaking {
        public static final ConfigBoolean BREAK_WRONG_BLOCKS = new ConfigBoolean("breakWrongBlocks", true,
                "While auto build is on, break blocks that don't match the schematic so the printer can place the right one.",
                "Break wrong blocks");
        public static final ConfigBoolean BREAK_NOT_IN_SCHEMATIC = new ConfigBoolean("breakBlocksNotInSchematic", false,
                "Also break blocks that aren't part of the schematic, inside its area and the layers being built: blocks where\n"
                        + "the schematic has air (terrain, leftovers, a stray block), and blocks of yours where the schematic wants\n"
                        + "something else (dirt or farmland where the floor goes): broken, and the right block goes in.\n"
                        + "Containers and unbreakable blocks are never broken. Off: those are left alone (and listed as conflicts).",
                "Break blocks not in the schematic");
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
        public static final ConfigBoolean LOOK_AT_BLOCKS = new ConfigBoolean("lookAtBlocks", no.sindrens.printeraddon.Edition.OFFICIAL,
                "Tell the server you look at the block being broken (your camera stays free). For servers with strict anticheat.",
                "Look at blocks when breaking");
        public static final ConfigString ALWAYS_BREAK = new ConfigString("alwaysBreakBlocks", "cactus_flower",
                "Blocks broken wherever they are in the schematic's area while auto build is on, also where the schematic\n"
                        + "has air and in layers already built (when the player comes by). Block ids, comma separated, e.g.\n"
                        + "cactus_flower, or minecraft:cobweb. Empty: none.",
                "Always break these blocks");
        public static final ConfigBoolean SKIP_CONTAINERS = new ConfigBoolean("skipContainers", true,
                "Never break chests, barrels, hoppers, shulker boxes, furnaces etc., even if they are wrong.",
                "Never break containers");
        public static final ConfigBoolean PICK_UP_ITEMS = new ConfigBoolean("autoBreakPickUp", true,
                "Auto break: what it breaks drops, and is picked up when it flies by. On: when the inventory is full,\n"
                        + "the drops go into your vaults (/pv) or linked chests. Off: drops are ignored (breaking goes on\n"
                        + "with a full inventory, the rest stays on the ground).",
                "Auto break: put drops away");
        public static final ConfigString AUTO_BREAK_SKIP = new ConfigString("autoBreakSkip", "",
                "Auto break: block types it never breaks, by id (oak_log, chest...). Easiest set in the Auto break tab\n"
                        + "(J+B): click a block of the selected area to skip it, again to break it.",
                "Auto break: skip these blocks");
        public static final ConfigBoolean STAND_TO_BREAK = new ConfigBoolean("standToBreak", true,
                "While breaking a block, stop flying for a moment and stand on the block under your feet when there is\n"
                        + "one (the game breaks blocks 5 times slower in the air: a cactus takes 3 s flying, 0.6 s standing).\n"
                        + "Never next to a cactus, never on the block being broken; it flies on right after.",
                "Stand while breaking");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                BREAK_WRONG_BLOCKS, BREAK_NOT_IN_SCHEMATIC, BREAK_WRONG_STATE, BREAK_RANGE, MIN_TOOL_DURABILITY, BREAK_TIMEOUT_SECONDS,
                WRONG_FOR_TICKS, MAX_BREAKS_PER_BLOCK, LOOK_AT_BLOCKS, ALWAYS_BREAK, SKIP_CONTAINERS, STAND_TO_BREAK, PICK_UP_ITEMS,
                AUTO_BREAK_SKIP);
    }

    public static class Layers {
        public static final ConfigBoolean AUTO_LAYER = new ConfigBoolean("autoLayer", true,
                "While auto build is on, build one Y layer at a time from the bottom and go to the next layer when it's done.",
                "Auto layer");
        public static final ConfigInteger LAYER_CHECK_INTERVAL = new ConfigInteger("layerCheckIntervalTicks", 40, 40, 200,
                "How often (in ticks) to check if the current layer is finished (at least 40: a check reads the whole layer).",
                "Layer check interval (ticks)");
        public static final ConfigInteger STUCK_REPORT_SECONDS = new ConfigInteger("stuckReportSeconds", 30, 5, 600,
                "If the layer hasn't changed for this long, list in chat what is left and why.",
                "Report stuck layer after (seconds)");
        public static final ConfigInteger SKIP_STUCK_SECONDS = new ConfigInteger("skipStuckSeconds", 20, 5, 3600,
                "If blocks you have the items for still can't be placed after this long (e.g. two rails side by side\n"
                        + "that the game always connects), go on to the next layer and list them at the end to do by hand.\n"
                        + "Blocks you don't have the items for are waited for.",
                "Skip unplaceable blocks after (seconds)");
        public static final ConfigBoolean LAYERS_TOGETHER = new ConfigBoolean("buildLayersTogether", true,
                "Build the layer above together with the one below when what goes there stands on the blocks below (seeds and\n"
                        + "stems on farmland, redstone dust on observers and pistons): placed right behind the block under them, in the\n"
                        + "same lanes, instead of flying over the whole build again for them. Off: always one layer at a time.",
                "Build layers together");
        public static final ConfigBoolean EXTRA_BLOCKS_BLOCK_LAYER = new ConfigBoolean("extraBlocksBlockLayer", false,
                "Count blocks where the schematic has air (terrain, stray blocks) as unfinished.\n"
                        + "Off: only blocks the schematic needs count. Use the 'Clear extra blocks' key to remove them.",
                "Extra blocks keep layer open");

        public static final ConfigBoolean ADDON_PLACES = new ConfigBoolean("addonPlacesBlocks", false,
                "Let the add-on place what Litematica Printer can't: rails (with temporary rails for the right shape) and\n"
                        + "minecarts. Off: the printer places every block and the add-on only moves, breaks wrong blocks, gets the\n"
                        + "items and sets the printer's settings. Water (waterlogged blocks) is always put in with a bucket.",
                "Add-on places blocks itself");
        public static final ConfigBoolean USE_AIR_PLACE = new ConfigBoolean("useAirPlaceForFloatingBlocks", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "Blocks with nothing beside or under them to place them against (the string at the bottom of a cactus farm)\n"
                        + "are placed in the air with the printer's 'printInAir': for the whole layer when all its blocks come out\n"
                        + "right that way (string, sand, cactus, plain blocks), else one block at a time. Only while such blocks are\n"
                        + "left. If the server doesn't keep blocks placed in the air, it stops by itself and says so.",
                "Air place floating blocks");

        public static final ConfigBoolean CAP_BEFORE_STOP = new ConfigBoolean("capBeforeStop", true,
                "When you stop the build, the sand beside cactus left open on top goes in first (a few seconds; at most\n"
                        + "90): open cactus grow while it's stopped and have to be broken one by one after. Stop again to stop at once.",
                "Cover cactus before stopping");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_LAYER, LAYERS_TOGETHER, CAP_BEFORE_STOP, ADDON_PLACES, LAYER_CHECK_INTERVAL, STUCK_REPORT_SECONDS, SKIP_STUCK_SECONDS, EXTRA_BLOCKS_BLOCK_LAYER,
                USE_AIR_PLACE);
    }


    /** True when the add-on is switched on. */
    public static boolean enabled() {
        return Generic.ADDON_ENABLED.getBooleanValue();
    }

    public static class Movement {
        public static final ConfigOptionList MOVE_MODE = new ConfigOptionList("movementMode", MoveMode.FLY,
                "How auto build gets you to the next unfinished blocks: fly (fastest: always above the blocks being built),\n"
                        + "walk, walk and fly when needed, or not at all.",
                "Movement (fly is fastest)");
        public static final ConfigString FLY_COMMAND = new ConfigString("flyCommand", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "fly",
                "Command (without the slash) sent once if flying is needed but not allowed yet. Empty: never send one.",
                "Fly command");
        public static final ConfigBoolean KEEP_MOVING = new ConfigBoolean("keepMoving", true,
                "Flying: when only a few blocks are left to place where you are, already fly on to the next spot (the printer\n"
                        + "places the last ones on the way) instead of stopping until everything around is done.",
                "Keep moving");
        public static final ConfigBoolean LANES = new ConfigBoolean("flyInLanes", true,
                "Full layers (a farm's dirt, mud, stems, observers, redstone...) are flown over in straight lanes, the printer\n"
                        + "placing all the way: as fast as the printer keeps up, slowing down only so nothing is left behind.\n"
                        + "Layers of chains (hoppers into hoppers) and what's left at the end go spot by spot as before.",
                "Fly in lanes");
        public static final ConfigInteger HOVER_HEIGHT = new ConfigInteger("hoverHeightAbove", 1, 1, 4,
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
                MOVE_MODE, FLY_COMMAND, KEEP_MOVING, LANES, HOVER_HEIGHT, WORK_WAIT_SECONDS, MAX_WALK_DISTANCE);

        public static MoveMode mode() {
            return (MoveMode) MOVE_MODE.getOptionListValue();
        }
    }

    public static class Safety {
        public static final ConfigBoolean STOP_ON_DAMAGE = new ConfigBoolean("stopOnDamage", true,
                "Stop auto build when you take damage.",
                "Stop when you take damage");
        public static final ConfigBoolean STOP_NEAR_PLAYERS = new ConfigBoolean("stopNearPlayers", true,
                "Stop auto build when another player comes close.",
                "Stop when a player comes close");
        public static final ConfigInteger NEAR_PLAYER_DISTANCE = new ConfigInteger("nearPlayerDistance", 16, 2, 128,
                "Distance in blocks for 'Stop near other players'.",
                "Near player distance");
        public static final ConfigString TRUSTED_PLAYERS = new ConfigString("trustedPlayers", "",
                "Players who don't stop the build when they come close (friends, island members), comma separated: Pouched, Vanzu_",
                "Players who don't stop it");

        public static final ConfigBoolean AUTO_EAT = new ConfigBoolean("autoEat", true,
                "During auto build, eat when the food bar gets low (starving hurts, and damage stops auto build).\n"
                        + "Never eats rotten flesh, spider eyes, poisonous potatoes, pufferfish, chorus fruit, raw chicken or golden apples.",
                "Eat when hungry");
        public static final ConfigInteger EAT_BELOW = new ConfigInteger("eatBelow", 14, 1, 19,
                "Eat when the food bar is at this or lower (20 is full, every drumstick is 2).",
                "Eat at food level");

        public static final ConfigBoolean USE_FEED_COMMAND = new ConfigBoolean("useFeedCommand", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "Fill the food bar with the server's feed command instead of eating (if you're allowed to use it).\n"
                        + "If the command does nothing (no permission), food from the inventory is eaten instead.",
                "Eat with /feed");
        public static final ConfigString FEED_COMMAND = new ConfigString("feedCommand", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "feed",
                "The feed command, without the slash.",
                "Feed command");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                STOP_ON_DAMAGE, STOP_NEAR_PLAYERS, NEAR_PLAYER_DISTANCE, TRUSTED_PLAYERS, AUTO_EAT, EAT_BELOW, USE_FEED_COMMAND, FEED_COMMAND);
    }

    public static class Shop {
        public static final ConfigString SHOP_COMMAND = new ConfigString("shopCommand", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "shop",
                "Command that opens the shop, without the slash.",
                "Shop command");
        public static final ConfigBoolean FETCH_FROM_CHESTS = new ConfigBoolean("fetchFromChests", true,
                "Before buying, take missing items from the chests, barrels and shulker boxes you linked (J+B, Build tab:\n"
                        + "look at one and press 'Link chest'). No other chest is ever opened. They have to be loaded (in view distance).",
                "Use linked chests and vaults first");
        public static final ConfigString VAULTS = new ConfigString("playerVaultNumbers", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "1,2",
                "Your player vaults to use as storage, like chests: items are taken from them before buying (pumpkins to\n"
                        + "craft seeds from, for example) and leftovers are put in them. Numbers, e.g. 1-6 or 1,2,3. Empty = none.",
                "Your vaults (/pv numbers)");
        public static final ConfigBoolean PUT_AWAY_ON_STOP = new ConfigBoolean("putAwayOnStop", true,
                "When auto build stops (you stop it, or the build is done): the building materials, what the add-on crafts\n"
                        + "with and its hoes go into your vaults (and linked chests within reach). The next start takes what it needs\n"
                        + "again (or buys it). Never buckets, food, armor, weapons, nor anything renamed or enchanted. If it doesn't\n"
                        + "all fit, it says so: link a chest for the rest (J+B).",
                "Put items away when stopping");
        public static final ConfigString VAULT_COMMAND = new ConfigString("vaultCommand", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "pv",
                "Command that opens a player vault, without the slash (the vault number is added).",
                "Vault command");
        public static final ConfigBoolean AUTO_BUY = new ConfigBoolean("autoBuy", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "During auto build, buy what the current layer is missing from the shop (as much as fits in the inventory).\n"
                        + "Needs a shop scan of the server first (J+S). When auto build starts it asks how much it may spend.",
                "Buy what's missing in the shop");
        public static final ConfigBoolean SELL_LEFTOVERS = new ConfigBoolean("sellLeftovers", true,
                "When storage is full (or there is none) and the inventory needs room: sell in the shop what the build\n"
                        + "doesn't need any more and the shop buys back (shift + right click, 'sell all'). Only the schematic's\n"
                        + "own materials, never tools, renamed or enchanted items. What the shop doesn't buy stays in your\n"
                        + "inventory (dropped only when the build is stuck for room).",
                "Sell leftovers when storage is full");
        public static final ConfigBoolean STOCK_UP = new ConfigBoolean("stockUpVaults", true,
                "Buy in bulk into your vaults (and linked chests): when the shop has to be used, a whole inventory of one\n"
                        + "item is bought at a time and put away, up to what the build still needs and the vaults have room for;\n"
                        + "the inventory is then filled from the vaults. The next trips only open a vault (2 s instead of 14 s in\n"
                        + "the shop). The more vaults and chests it may use, the more it buys ahead.",
                "Buy in bulk into vaults");
        public static final ConfigInteger BUY_WHEN_LEFT = new ConfigInteger("buyWhenLeft", 8, 0, 64,
                "Only get more of an item (from linked chests or the shop) once this many or fewer are left in the inventory.\n"
                        + "How much is bought is shared between the items the layer needs, and a few slots always stay free.",
                "Buy when this many are left");
        public static final ConfigInteger STACKS_BELOW = new ConfigInteger("buyStacksBelow", 50000, 0, 100000000,
                "Items whose stack costs less than this are only bought by the whole stack (the shop's stacks screen), the\n"
                        + "amount rounded up: what's left over goes into storage for later. Setting an exact amount takes many\n"
                        + "clicks (102 mud). Pricier ones (blocks of diamond or iron) are bought exactly. 0 = always exactly.",
                "Whole stacks below (price per stack)");
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

        public static final ConfigBoolean PREFER_BLOCKS = new ConfigBoolean("craftFromBlocks", false,
                "The add-on always buys the cheapest way by the shop scan (seeds or pumpkins, ingots or blocks, dust or\n"
                        + "blocks). On: when the prices aren't known, or cost the same, blocks are bought and crafted back (fewer\n"
                        + "purchases). The blocks are always bought when the item itself can't be (not sold, or not delivered).",
                "Buy blocks and craft them back");
        public static final ConfigBoolean USE_CRAFT_COMMAND = new ConfigBoolean("useCraftCommand", !no.sindrens.printeraddon.Edition.OFFICIAL,
                "Open a crafting table with the server's craft command when a recipe needs one (rails, hoes), instead of\n"
                        + "placing one. If the command does nothing (no permission), a crafting table is used as before.",
                "Craft with /craft");
        public static final ConfigString CRAFT_COMMAND = new ConfigString("craftCommand", no.sindrens.printeraddon.Edition.OFFICIAL ? "" : "craft",
                "The command that opens a crafting table, without the slash.",
                "Craft command");

        public static final ConfigInteger KEEP_MONEY = new ConfigInteger("keepMoney", 0, 0, 2_000_000_000,
                "Never spend below this much money (read from the sidebar's balance): what's left above it is what the\n"
                        + "build may spend. 0: no limit. Can be set from Discord too (/schedule).",
                "Keep at least this much money");
        public static final ConfigBoolean LAYER_COST_ALERT = new ConfigBoolean("layerCostAlert", true,
                "Warn (in chat and Discord) when a layer cost much more than the layers before it: a shop price went up,\n"
                        + "or something is bought that shouldn't be.",
                "Warn when a layer costs more");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                KEEP_MONEY, LAYER_COST_ALERT, FETCH_FROM_CHESTS, VAULTS, PUT_AWAY_ON_STOP, VAULT_COMMAND, AUTO_BUY, STOCK_UP, SELL_LEFTOVERS, BUY_WHEN_LEFT, STACKS_BELOW, PREFER_BLOCKS, USE_CRAFT_COMMAND, CRAFT_COMMAND,
                SHOP_COMMAND, SHOP_SECTION, PROBE_BUY_SCREEN, MAX_PAGES, WAIT_TIMEOUT_TICKS);
    }

    /** The settings most players need to look at once (first tab of the settings); everything else is tuned already. */
    public static java.util.List<IConfigBase> important() {
        return java.util.List.of(
                Generic.ADDON_ENABLED, Generic.STOP_AT, Shop.KEEP_MONEY, Layers.CAP_BEFORE_STOP,
                Shop.VAULTS, Shop.FETCH_FROM_CHESTS, Shop.PUT_AWAY_ON_STOP, Shop.AUTO_BUY, Shop.SELL_LEFTOVERS, Shop.PREFER_BLOCKS,
                Shop.USE_CRAFT_COMMAND, Safety.USE_FEED_COMMAND, Safety.AUTO_EAT,
                Movement.MOVE_MODE,
                Safety.STOP_ON_DAMAGE, Safety.STOP_NEAR_PLAYERS, Safety.TRUSTED_PLAYERS,
                Generic.RESUME_AFTER_REJOIN, Generic.RECONNECT, Generic.BACK_COMMANDS, Generic.SHOW_HUD, Generic.CHAT_MESSAGES,
                Shop.SHOP_COMMAND, Shop.VAULT_COMMAND, Shop.CRAFT_COMMAND, Safety.FEED_COMMAND, Movement.FLY_COMMAND);
    }

    /** Every setting of every tab (for searching them all at once). */
    public static java.util.List<IConfigBase> all() {
        java.util.List<IConfigBase> all = new java.util.ArrayList<>();
        all.addAll(Generic.OPTIONS);
        all.addAll(Shop.OPTIONS);
        all.addAll(Movement.OPTIONS);
        all.addAll(Safety.OPTIONS);
        all.addAll(Layers.OPTIONS);
        all.addAll(Breaking.OPTIONS);
        all.addAll(Speed.OPTIONS);
        return all;
    }

    public static class Speed {
        public static final ConfigBoolean AUTO_SPEED = new ConfigBoolean("autoSpeed", true,
                "Find the fastest printer interval that still places right, per kind of block: start at the preset's interval,\n"
                        + "go faster after a run of correct blocks, one tick slower as soon as a block comes out wrong.",
                "Automatic speed");
        public static final ConfigInteger AUTO_SPEED_MIN_INTERVAL = new ConfigInteger("autoSpeedMinInterval", 2, 2, 40,
                "Automatic speed never goes below this printer interval. It only gets there while every block comes out\n"
                        + "right, and slows down again at the first wrong one. Never below 2 (at 1 the server kicked the player).\n"
                        + "The speed guard slows down by itself when the server is slow to answer.",
                "Automatic speed: fastest interval");
        public static final ConfigBoolean SPEED_GUARD = new ConfigBoolean("speedGuard", true,
                "Watches how fast the server answers the clicks. A click without an answer for 2 seconds: placing waits\n"
                        + "for it, then goes on at at most 8 blocks a second, more again step by step after 30 s each. Answers\n"
                        + "slow on average set a limit too. Every 10 s the build log gets a SERVER line with what it saw.",
                "Speed guard");
        public static final double VANILLA_WALK = 0.21;
        public static final double VANILLA_FLY = 0.55;

        public static final ConfigInteger MIN_TICKS_BETWEEN_PLACEMENTS = new ConfigInteger("minTicksBetweenPlacements",
                no.sindrens.printeraddon.Edition.OFFICIAL ? 4 : 0, 0, 20,
                "Ticks between two blocks placed (0: no wait, the printer's interval decides). 4 is as fast as holding\n"
                        + "right-click in vanilla (5 blocks a second). Below 4 is faster than a player: many servers' anticheats\n"
                        + "notice it and their rules may forbid it. Your choice, at your own risk.",
                "Ticks between placements");
        public static final ConfigInteger MAX_PLACEMENTS_PER_SECOND = new ConfigInteger("maxPlacementsPerSecond",
                no.sindrens.printeraddon.Edition.OFFICIAL ? 5 : 0, 0, 40,
                "For servers with a strict anticheat: the printer places at most this many blocks per second. 0 = no limit\n"
                        + "(the printer's interval decides). More than 5 is faster than a player holding right-click.",
                "Max placements per second");
        public static final ConfigBoolean WAIT_AFTER_SWITCH = new ConfigBoolean("waitAfterSwitch", true,
                "A click never in the same tick as the switch to its item: it goes a tick later. Some servers refuse such\n"
                        + "clicks (on play.mythian.net 7 % of the clicks right after a switch, against 0.5 % of the others).\n"
                        + "Off: a little faster on servers that don't.",
                "Wait a tick after switching items");
        public static final ConfigBoolean CLICK_SEEN_FACE = new ConfigBoolean("clickSeenFace", true,
                "Clicks go on a side of a block the player can see, on the block's real shape (a cactus is thinner than\n"
                        + "its block). Blocks that go in the same way from any side (string, sand) are clicked against another\n"
                        + "block beside them when the printer's choice faces away: servers refuse those clicks.",
                "Click a side you can see");
        public static final ConfigInteger LAG_PAUSE_TPS = new ConfigInteger("lagPauseTps", 12, 0, 19,
                "On a server: stop placing while the server runs below this many ticks per second (20 is normal),\n"
                        + "because clicks then get answered late and blocks come out wrong. 0 = never wait.",
                "Wait when server TPS below");
        public static final ConfigInteger AUTO_SPEED_STEP_BLOCKS = new ConfigInteger("autoSpeedStepBlocks", 10, 5, 1000,
                "Automatic speed goes one tick faster after this many correct blocks in a row.",
                "Automatic speed: blocks per step");
        public static final ConfigInteger PRINTER_INTERVAL = new ConfigInteger("customPrinterInterval", 12, 2, 40,
                "Custom preset: the printer's printing interval (lower is faster). Below 12 the printer often places blocks wrong.",
                "Custom: printer interval");
        public static final ConfigInteger BREAK_COOLDOWN = new ConfigInteger("customBreakCooldownTicks",
                no.sindrens.printeraddon.Edition.OFFICIAL ? 5 : 2, 0, 100,
                "Custom preset: ticks to wait after breaking a block before the next one. 5 is vanilla's own wait;\n"
                        + "below 5 is faster than a player (anticheats may notice it).",
                "Custom: break cooldown (ticks)");
        public static final ConfigInteger SHOP_CLICK_DELAY = new ConfigInteger("customShopClickDelayTicks", 6, 1, 100,
                "Custom preset: ticks to wait between clicks in the shop menu.",
                "Custom: shop click delay (ticks)");
        public static final ConfigDouble WALK_SPEED = new ConfigDouble("customWalkSpeed", 0.21, 0.05,
                0.4,
                "Custom preset: walking speed in blocks per tick (vanilla walking is about 0.21). Official build: above\n"
                        + "vanilla only in singleplayer or where the server allows it (MOTD tag [printer_addon:allow-fast-movement]).",
                "Custom: walk speed");
        public static final ConfigDouble FLY_SPEED = new ConfigDouble("customFlySpeed", 0.55, 0.1,
                2.0,
                "Custom preset: flying speed in blocks per tick (vanilla flying is about 0.55). Official build: above\n"
                        + "vanilla only in singleplayer or where the server allows it (MOTD tag [printer_addon:allow-fast-movement]).",
                "Custom: fly speed");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_SPEED, AUTO_SPEED_MIN_INTERVAL, SPEED_GUARD, AUTO_SPEED_STEP_BLOCKS, MIN_TICKS_BETWEEN_PLACEMENTS, MAX_PLACEMENTS_PER_SECOND, WAIT_AFTER_SWITCH, CLICK_SEEN_FACE, LAG_PAUSE_TPS, PRINTER_INTERVAL, BREAK_COOLDOWN, SHOP_CLICK_DELAY, WALK_SPEED, FLY_SPEED);

        public static double walkSpeed() {
            SpeedPreset preset = preset();
            double speed = preset == SpeedPreset.CUSTOM ? WALK_SPEED.getDoubleValue() : preset.walkSpeed;
            // Official build: faster than a player only where the server allowed it (or singleplayer)
            return no.sindrens.printeraddon.ServerPolicy.fastMovementAllowed() ? speed : Math.min(speed, VANILLA_WALK);
        }

        private static SpeedPreset lastPreset;
        private static boolean copying;

        /** See AddonInit: custom speed values and the preset kept in step. */
        public static void linkCustomToPreset() {
            lastPreset = preset();
            for (fi.dy.masa.malilib.config.IConfigBase custom : new fi.dy.masa.malilib.config.IConfigBase[]{
                    PRINTER_INTERVAL, BREAK_COOLDOWN, SHOP_CLICK_DELAY, WALK_SPEED, FLY_SPEED}) {
                if (custom instanceof fi.dy.masa.malilib.config.options.ConfigBase<?> base) {
                    base.setValueChangeCallback(c -> {
                        if (!copying && !Configs.loading && preset() != SpeedPreset.CUSTOM) {
                            Generic.SPEED_PRESET.setOptionListValue(SpeedPreset.CUSTOM);
                            lastPreset = SpeedPreset.CUSTOM;
                            no.sindrens.printeraddon.util.Chat.info("Speed preset set to Custom (you changed one of its speeds).");
                        }
                        warnIfRisky();
                    });
                }
            }
            MIN_TICKS_BETWEEN_PLACEMENTS.setValueChangeCallback(c -> warnIfRisky());
            MAX_PLACEMENTS_PER_SECOND.setValueChangeCallback(c -> warnIfRisky());
            Generic.SPEED_PRESET.setValueChangeCallback(c -> {
                SpeedPreset now = preset();
                if (now == SpeedPreset.CUSTOM && lastPreset != null && lastPreset != SpeedPreset.CUSTOM && !Configs.loading) {
                    copying = true;
                    try {
                        PRINTER_INTERVAL.setIntegerValue(lastPreset.printerInterval);
                        BREAK_COOLDOWN.setIntegerValue(lastPreset.breakCooldownTicks);
                        SHOP_CLICK_DELAY.setIntegerValue(lastPreset.shopClickDelayTicks);
                        WALK_SPEED.setDoubleValue(lastPreset.walkSpeed);
                        FLY_SPEED.setDoubleValue(lastPreset.flySpeed);
                    } finally {
                        copying = false;
                    }
                }
                lastPreset = now;
                warnIfRisky();
            });
        }

        private static String lastWarning = "";

        /** Settings faster than a vanilla player (official build): said once in chat when one is set. */
        public static java.util.List<String> risky() {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (!no.sindrens.printeraddon.Edition.OFFICIAL) {
                return out;
            }
            int spacing = MIN_TICKS_BETWEEN_PLACEMENTS.getIntegerValue();
            int perSecond = MAX_PLACEMENTS_PER_SECOND.getIntegerValue();
            if (spacing < 4 && (perSecond == 0 || perSecond > 5)) {
                out.add("placing faster than a player (ticks between placements " + spacing + ", vanilla 4)");
            }
            if (breakCooldownTicks() < 5) {
                out.add("breaking faster than a player (break wait " + breakCooldownTicks() + " ticks, vanilla 5)");
            }
            SpeedPreset preset = preset();
            double walk = preset == SpeedPreset.CUSTOM ? WALK_SPEED.getDoubleValue() : preset.walkSpeed;
            double fly = preset == SpeedPreset.CUSTOM ? FLY_SPEED.getDoubleValue() : preset.flySpeed;
            if (walk > VANILLA_WALK + 1.0e-6 || fly > VANILLA_FLY + 1.0e-6) {
                out.add("moving faster than vanilla (only used in singleplayer or where the server allows it)");
            }
            return out;
        }

        public static void warnIfRisky() {
            if (Configs.loading) {
                return;
            }
            java.util.List<String> risky = risky();
            String text = String.join("; ", risky);
            if (!risky.isEmpty() && !text.equals(lastWarning)) {
                no.sindrens.printeraddon.util.Chat.warn("Careful: " + text + ". Anticheats can notice this and many servers' "
                        + "rules forbid it: you can be banned. Your choice.");
            }
            lastWarning = text;
        }

        public static double flySpeed() {
            SpeedPreset preset = preset();
            double speed = preset == SpeedPreset.CUSTOM ? FLY_SPEED.getDoubleValue() : preset.flySpeed;
            return no.sindrens.printeraddon.ServerPolicy.fastMovementAllowed() ? speed : Math.min(speed, VANILLA_FLY);
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

    /** The settings were read once since the game started (see loadFromFile). */
    private static boolean loadedOnce;
    /** The settings are being read from the file (value-change callbacks do nothing meanwhile). */
    public static boolean loading;

    public static void loadFromFile() {
        // Read again later (the settings screen saves and reads them back when a setting is changed): a build that's
        // running goes on. Changing "auto eat" stopped a build: the file has auto build off, as it's saved off
        boolean buildOn = Generic.AUTO_BUILD.getBooleanValue();
        loading = true;
        try {
            readFile();
        } finally {
            loading = false;
        }
        // Never start building on its own after a restart; a reload while it builds keeps it building
        Generic.AUTO_BUILD.setBooleanValue(loadedOnce && buildOn);
        loadedOnce = true;
    }

    private static void readFile() {
        Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            JsonElement element = JsonUtils.parseJsonFile(configFile);

            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                int version = root.has("configVersion") ? root.get("configVersion").getAsInt() : 1;
                ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
                ConfigUtils.readConfigBase(root, "Breaking", Breaking.OPTIONS);
                ConfigUtils.readConfigBase(root, "Layers", Layers.OPTIONS);
                ConfigUtils.readConfigBase(root, "Movement", Movement.OPTIONS);
                ConfigUtils.readConfigBase(root, "Safety", Safety.OPTIONS);
                ConfigUtils.readConfigBase(root, "Shop", Shop.OPTIONS);
                ConfigUtils.readConfigBase(root, "Speed", Speed.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
                if (version < 2) {
                    // The fastest interval is 1 now (the player's server takes it): an older setting of 2 goes down
                    if (Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue() == 2) {
                        Speed.AUTO_SPEED_MIN_INTERVAL.setIntegerValue(1);
                    }
                }
                if (version < 3) {
                    // /server Skyblock alone puts the player back where they logged out; /is go after it took them
                    // to the island's spawn, away from the build
                    String back = Generic.BACK_COMMANDS.getStringValue().replaceAll("\\s+", " ").trim();
                    if (back.equalsIgnoreCase("server skyblock, is go") || back.equalsIgnoreCase("server skyblock,is go")) {
                        Generic.BACK_COMMANDS.setValueFromString("server Skyblock");
                    }
                }
                if (version < 4) {
                    // Two layers at once was off for a while until a stand-still it ran into was fixed (0.14.1)
                    Layers.LAYERS_TOGETHER.setBooleanValue(true);
                }
                if (version < 5) {
                    // At intervals 1 and 2 the player's server kicked the player (too many clicks)
                    Speed.AUTO_SPEED_MIN_INTERVAL.setIntegerValue(Math.max(3, Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue()));
                    Speed.PRINTER_INTERVAL.setIntegerValue(Math.max(3, Speed.PRINTER_INTERVAL.getIntegerValue()));
                    // Air place was off: the floating string at the bottom of a cactus farm was never placed
                    if (!no.sindrens.printeraddon.Edition.OFFICIAL) {
                        Layers.USE_AIR_PLACE.setBooleanValue(true);
                    }
                }
                if (version < 6) {
                    // 2 is the fastest again (the speed guard slows down when the server can't keep up)
                    if (Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue() == 3) {
                        Speed.AUTO_SPEED_MIN_INTERVAL.setIntegerValue(2);
                    }
                }
                if (version < 7 && !no.sindrens.printeraddon.Edition.OFFICIAL) {
                    // Every run's log goes to the developer by itself now, the default (the player is told once in chat,
                    // with how to turn it off): the logs show what goes wrong
                    Generic.SHARE_LOGS.setBooleanValue(true);
                }
                logsNoticeShown = root.has("logsNoticeShown") && root.get("logsNoticeShown").getAsBoolean();
            }
        }

    }

    public static void saveToFile() {
        ServerProfiles.capture();
        Path dir = FileUtils.getConfigDirectory();

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        JsonObject root = new JsonObject();
        root.addProperty("configVersion", CONFIG_VERSION);
        root.addProperty("logsNoticeShown", logsNoticeShown);
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
