package no.sindrens.printeraddon.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import no.sindrens.printeraddon.Edition;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.Chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The server commands (fly, shop, vaults, craft, feed, back to the build) and auto reconnect are kept per server: what
 * you allow on one server isn't used on another. Official build: on a server you haven't set up, they are all off, and
 * you're told once where to turn on the ones you may use there. Saved in config/printer_addon/servers.json.
 */
public final class ServerProfiles {
    private static final Path FILE = FileUtils.getConfigDirectory().resolve("printer_addon").resolve("servers.json");

    private static JsonObject root;
    /** The server joined (its address), or "" when not on one. */
    private static String current = "";
    private static boolean applying;

    private ServerProfiles() {
    }

    /** The settings kept per server. */
    public static List<IConfigValue> settings() {
        return List.of(Configs.Movement.FLY_COMMAND, Configs.Shop.SHOP_COMMAND, Configs.Shop.AUTO_BUY, Configs.Shop.VAULT_COMMAND,
                Configs.Shop.VAULTS, Configs.Shop.USE_CRAFT_COMMAND, Configs.Shop.CRAFT_COMMAND, Configs.Safety.USE_FEED_COMMAND,
                Configs.Safety.FEED_COMMAND, Configs.Generic.BACK_COMMANDS, Configs.Generic.RECONNECT);
    }

    public static String current() {
        return current;
    }

    /** Joined a world: that server's commands. */
    public static void onJoin(Minecraft mc) {
        load();
        ServerData data = mc.getCurrentServer();
        String key = mc.hasSingleplayerServer() || data == null ? "singleplayer" : data.ip.trim().toLowerCase(Locale.ROOT);
        current = key;
        JsonObject servers = servers();
        boolean firstEver = !root.has("guideShown");
        if (servers.has(key)) {
            apply(servers.getAsJsonObject(key));
        } else if (Edition.OFFICIAL && !key.equals("singleplayer")) {
            // A new server: nothing of another server's is sent here
            applying = true;
            Configs.loading = true;
            try {
                for (IConfigValue setting : settings()) {
                    setting.resetToDefault();
                }
            } finally {
                Configs.loading = false;
                applying = false;
            }
            capture();
            if (!firstEver) {
                Chat.info("New server (" + key + "): Printer Addon sends no server commands here. Set the ones you may use "
                        + "(fly, shop, vaults, craft, feed) in the settings (J+C), Commands; they're kept for this server.");
            }
        } else {
            capture();
        }
        if (firstEver && Edition.OFFICIAL) {
            root.addProperty("guideShown", true);
            save();
            guide();
        }
    }

    public static void onLeave() {
        current = "";
    }

    /** The first start: what to do, in a few lines. */
    private static void guide() {
        Chat.info("Printer Addon: welcome! 1) Load a schematic with Litematica and place it. 2) Press J+B for the build menu "
                + "and start auto build (J+G starts and stops, J+Z pauses, J+N stops after this layer). 3) Settings: J+C.");
        Chat.info("It moves, looks and clicks like a player and sends no server commands until you set them (Commands tab). "
                + "Faster settings are your choice, with a warning. Logs are only sent when you choose to.");
        Chat.warn("Many servers forbid printers and automation: check the server's rules first. You can be banned.");
    }

    private static void apply(JsonObject profile) {
        applying = true;
        Configs.loading = true;
        try {
            for (IConfigValue setting : settings()) {
                if (profile.has(setting.getName())) {
                    setting.setValueFromString(profile.get(setting.getName()).getAsString());
                } else if (Edition.OFFICIAL) {
                    setting.resetToDefault();
                }
            }
        } finally {
            Configs.loading = false;
            applying = false;
        }
    }

    /** The settings as they are now, kept for the server joined (when the settings are saved). */
    public static void capture() {
        if (applying || current.isEmpty()) {
            return;
        }
        load();
        JsonObject profile = new JsonObject();
        for (IConfigValue setting : settings()) {
            profile.addProperty(setting.getName(), setting.getStringValue());
        }
        servers().add(current, profile);
        save();
    }

    private static JsonObject servers() {
        if (!root.has("servers") || !root.get("servers").isJsonObject()) {
            root.add("servers", new JsonObject());
        }
        return root.getAsJsonObject("servers");
    }

    private static void load() {
        if (root != null) {
            return;
        }
        root = new JsonObject();
        try {
            if (Files.exists(FILE)) {
                root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't read {}: {}", FILE, e.toString());
            root = new JsonObject();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't save {}: {}", FILE, e.toString());
        }
    }
}
