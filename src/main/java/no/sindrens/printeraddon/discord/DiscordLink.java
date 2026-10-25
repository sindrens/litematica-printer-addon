package no.sindrens.printeraddon.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.build.AreaBreaker;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The link between the game and the Printer Addon Discord: live status, alerts, Pause / Resume / Stop from Discord,
 * messages from the developer and the update check. Nothing private is sent: no chat, no coordinates, no chest or vault
 * locations. The token from linking is kept in config/printer_addon/discord.json (never in the shared settings).
 */
public final class DiscordLink {
    private DiscordLink() {
    }

    private static final int STATUS_EVERY_TICKS = 100;   // 5 s
    private static final int POLL_EVERY_TICKS = 60;      // 3 s
    private static final int SHOPPING_LIST_AFTER_TICKS = 600; // 30 s into a run, once the needs are known

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("printer_addon").resolve("discord.json");

    private static String token = "";
    private static String discordName = "";
    private static boolean loaded;
    private static long ticks;
    private static boolean wasActive;
    private static long activeSince = -1;
    private static boolean shoppingSent;
    private static boolean busyStatus;
    private static boolean busyPoll;
    private static boolean toldUpdate;
    private static boolean toldOffline;
    private static final ArrayDeque<String> INBOX = new ArrayDeque<>();

    // ------------------------------------------------------------------ settings and token

    public static boolean enabled() {
        return Configs.Generic.DISCORD_SYNC.getBooleanValue() && !api().isEmpty();
    }

    /** The Printer Addon bot (through the relay on the developer's droplet). */
    public static final String DEFAULT_API = "http://139.59.156.198:8787/api/v1";
    /** 0.16.0 had this as its default: saved settings with it get the real address. */
    private static final String OLD_DEFAULT = "http://localhost:8787/api/v1";

    private static String api() {
        String url = Configs.Generic.DISCORD_API.getStringValue().trim();
        if (url.equals(OLD_DEFAULT)) {
            url = DEFAULT_API;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static boolean isLinked() {
        load();
        return !token.isEmpty();
    }

    public static String linkedTo() {
        load();
        return discordName;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                token = o.has("token") ? o.get("token").getAsString() : "";
                discordName = o.has("discord") ? o.get("discord").getAsString() : "";
            }
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't read {}: {}", FILE, e.toString());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("token", token);
            o.addProperty("discord", discordName);
            Files.writeString(FILE, o.toString());
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't save {}: {}", FILE, e.toString());
        }
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(PrinterAddon.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    // ------------------------------------------------------------------ http

    private static void call(String method, String path, JsonObject body, boolean auth, Consumer<HttpResponse<String>> then,
                             Consumer<Throwable> failed) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api() + path)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "PrinterAddon/" + version());
        if (auth) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request;
        try {
            request = b.build();
        } catch (IllegalArgumentException e) {
            if (failed != null) {
                Minecraft.getInstance().execute(() -> failed.accept(e));
            }
            return;
        }
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((r, e) ->
                Minecraft.getInstance().execute(() -> {
                    if (e != null) {
                        if (failed != null) {
                            failed.accept(e);
                        }
                        return;
                    }
                    if (r.statusCode() == 401 && auth) {
                        lostLink();
                        return;
                    }
                    if (then != null) {
                        then.accept(r);
                    }
                }));
    }

    private static JsonObject json(HttpResponse<String> r) {
        try {
            return JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void lostLink() {
        if (token.isEmpty()) {
            return;
        }
        token = "";
        discordName = "";
        save();
        Chat.warn("The Discord link isn't valid any more. Type /link on Discord and link again (J+L).");
    }

    // ------------------------------------------------------------------ linking

    public static void link(String code) {
        if (!enabled()) {
            Chat.warn("Discord sync is off (settings: Discord sync), or no Discord API address is set.");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String player = mc.player != null ? mc.player.getGameProfile().name() : mc.getUser().getName();
        JsonObject body = new JsonObject();
        body.addProperty("code", code.trim().toUpperCase(Locale.ROOT));
        body.addProperty("player", player);
        Chat.info("Linking with Discord...");
        call("POST", "/link", body, false, r -> {
            JsonObject o = json(r);
            if (r.statusCode() == 200 && o.has("token")) {
                load();
                token = o.get("token").getAsString();
                discordName = o.has("discord") ? o.get("discord").getAsString() : "";
                save();
                Chat.info("§aLinked with Discord§r as " + discordName + ". Your control room is ready on Discord.");
                Chat.announce("Linked with Discord", "your control room is ready", true);
                ticks = STATUS_EVERY_TICKS - 5; // status right away
            } else {
                Chat.warn(o.has("error") ? o.get("error").getAsString() : "Linking failed (" + r.statusCode() + ").");
            }
        }, e -> Chat.warn("Couldn't reach the Discord bot: " + shortError(e)));
    }

    public static void unlink() {
        if (!isLinked()) {
            Chat.info("Not linked with Discord.");
            return;
        }
        call("POST", "/unlink", new JsonObject(), true, null, null);
        token = "";
        discordName = "";
        save();
        Chat.info("Unlinked from Discord.");
    }

    private static String shortError(Throwable e) {
        Throwable c = e.getCause() != null ? e.getCause() : e;
        String s = c.getClass().getSimpleName();
        return s.contains("Connect") ? "the bot is offline or the address is wrong" : s;
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(Minecraft mc) {
        if (!enabled() || !isLinked()) {
            return;
        }
        ticks++;
        boolean active = AutoBuild.INSTANCE.isRunning() || AreaBreaker.INSTANCE.isRunning();
        if (active && !wasActive) {
            activeSince = ticks;
            shoppingSent = false;
            event("run_start", AreaBreaker.INSTANCE.isRunning() ? "Auto break" : AutoBuild.placementNames());
        }
        wasActive = active;
        if (active && !shoppingSent && activeSince >= 0 && ticks - activeSince > SHOPPING_LIST_AFTER_TICKS) {
            shoppingSent = true;
            sendShoppingList();
        }
        if (ticks % STATUS_EVERY_TICKS == 0 && !busyStatus) {
            sendStatus(mc);
        }
        if (ticks % POLL_EVERY_TICKS == 0 && !busyPoll) {
            poll(mc);
        }
    }

    private static void sendStatus(Minecraft mc) {
        AutoBuild build = AutoBuild.INSTANCE;
        JsonObject s = new JsonObject();
        String state;
        if (mc.player == null) {
            state = build.isRemotelyPaused() ? "paused" : "idle";
            s.addProperty("detail", "not in a world");
        } else if (AreaBreaker.INSTANCE.isRunning()) {
            state = "breaking";
            s.addProperty("detail", "auto break");
        } else if (build.isRunning()) {
            state = "building";
            s.addProperty("detail", build.doing());
            s.addProperty("build", AutoBuild.placementNames());
            int[] range = build.layers.yRange();
            if (range != null && range[1] >= range[0]) {
                int layers = range[1] - range[0] + 1;
                int layer = Math.max(1, Math.min(layers, build.layers.getLayer() - range[0] + 1));
                s.addProperty("layer", layer);
                s.addProperty("layers", layers);
                s.addProperty("progress", Math.round((layer - 1) * 1000.0 / layers) / 10.0);
            }
            double perMinute = build.stats.averageRate();
            if (perMinute > 0 && build.wholeKnown()) {
                s.addProperty("eta_s", (long) (build.blocksLeft() / perMinute * 60));
            }
            s.addProperty("blocks_placed", build.stats.totalPlaced());
            s.addProperty("money_spent", Math.round(build.spentTotal()));
        } else if (build.isRemotelyPaused()) {
            state = "paused";
            s.addProperty("detail", "paused from Discord");
            s.addProperty("build", AutoBuild.placementNames());
        } else {
            state = "idle";
        }
        s.addProperty("state", state);
        s.addProperty("version", version());
        if (mc.getCurrentServer() != null) {
            s.addProperty("server", mc.getCurrentServer().ip);
        }
        busyStatus = true;
        call("POST", "/status", s, true, r -> {
            busyStatus = false;
            toldOffline = false;
        }, e -> {
            busyStatus = false;
            if (!toldOffline) {
                toldOffline = true;
                PrinterAddon.LOGGER.info("Discord bot not reachable: {}", shortError(e));
            }
        });
    }

    private static void poll(Minecraft mc) {
        busyPoll = true;
        call("GET", "/poll", null, true, r -> {
            busyPoll = false;
            if (r.statusCode() != 200) {
                return;
            }
            JsonObject o = json(r);
            if (o.has("commands")) {
                for (JsonElement c : o.getAsJsonArray("commands")) {
                    runCommand(mc, c.getAsJsonObject().get("action").getAsString());
                }
            }
            if (o.has("messages")) {
                for (JsonElement m : o.getAsJsonArray("messages")) {
                    showMessage(m.getAsJsonObject());
                }
            }
            if (o.has("latest") && !o.get("latest").isJsonNull()) {
                checkUpdate(o.get("latest").getAsString());
            }
        }, e -> busyPoll = false);
    }

    private static void runCommand(Minecraft mc, String action) {
        AutoBuild build = AutoBuild.INSTANCE;
        switch (action) {
            case "pause" -> {
                if (build.isRunning()) {
                    build.pauseRemote(mc);
                    Chat.info("Paused from Discord.");
                } else {
                    Chat.info("Discord asked to pause, but auto build isn't running.");
                }
            }
            case "resume" -> {
                if (build.isRunning()) {
                    return;
                }
                if (mc.player == null) {
                    Chat.warn("Discord asked to resume, but you're not in a world.");
                    return;
                }
                Chat.info("Resumed from Discord.");
                build.resumeRemote(mc);
            }
            case "stop" -> {
                if (AreaBreaker.INSTANCE.isRunning()) {
                    AreaBreaker.INSTANCE.stop(mc, "stopped from Discord");
                }
                if (build.isRunning() || build.isRemotelyPaused()) {
                    build.stopRemote(mc);
                    Chat.info("Stopped from Discord.");
                }
            }
            default -> PrinterAddon.LOGGER.info("Unknown Discord command {}", action);
        }
        ticks = STATUS_EVERY_TICKS - 10; // new status soon
    }

    private static void showMessage(JsonObject m) {
        String text = m.has("text") ? m.get("text").getAsString() : "";
        String about = m.has("about") && !m.get("about").isJsonNull() ? m.get("about").getAsString() : "";
        String from = m.has("from") && !m.get("from").isJsonNull() ? m.get("from").getAsString() : "Discord";
        String line = "§d[" + from + (about.isEmpty() ? "" : " · " + about) + "]§r " + text;
        INBOX.addFirst(line);
        while (INBOX.size() > 20) {
            INBOX.removeLast();
        }
        Chat.warn(line);
        Chat.announce("Message from " + from, about.isEmpty() ? "see chat" : about, true);
    }

    public static List<String> inbox() {
        return new ArrayList<>(INBOX);
    }

    private static void checkUpdate(String latest) {
        if (toldUpdate || compare(latest, version()) <= 0) {
            return;
        }
        toldUpdate = true;
        Chat.warn("Printer Addon §a" + latest + "§e is out (you have " + version() + "). Download it in #downloads on Discord.");
    }

    /** Compares "0.15.10" and "0.15.9" by numbers. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ events

    /** Sends an alert: run_start, stuck, kicked, rejoined, no_money, no_items, vault_empty, error, custom. */
    public static void event(String type, String message) {
        if (!enabled() || !isLinked()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("message", message == null ? "" : clean(message));
        call("POST", "/event", o, true, null, null);
    }

    /** End of a run (from RunLog#end): totals for stats and achievements, and the alert. */
    public static void runEnded(String why, int placed, double seconds, double spent, String what) {
        if (!enabled() || !isLinked() || "disconnected".equals(why)) {
            return;
        }
        String result;
        String kind = null;
        String lower = why == null ? "" : why.toLowerCase(Locale.ROOT);
        if ("finished".equals(why) || "done".equals(why)) {
            result = "finished";
        } else if (lower.equals("stopped-by-you") || lower.contains("paused from discord") || lower.contains("stopped from discord")
                || lower.equals("could-not-start")) {
            result = "stopped";
        } else {
            result = "problem";
            kind = lower.startsWith("missing") ? "no_items"
                    : lower.contains("spending limit") || lower.contains("money") ? "no_money"
                    : lower.contains("vault") ? "vault_empty"
                    : lower.contains("nothing could") || lower.contains("stuck") || lower.contains("safety") ? "stuck"
                    : "error";
        }
        JsonObject run = new JsonObject();
        run.addProperty("result", result);
        if (kind != null) {
            run.addProperty("kind", kind);
        }
        run.addProperty("build", clean(what == null ? "" : what.replaceFirst("^auto build ", "")));
        run.addProperty("blocks", placed);
        run.addProperty("seconds", Math.round(seconds));
        run.addProperty("money", Math.round(spent));
        JsonObject o = new JsonObject();
        o.addProperty("type", "run_end");
        o.addProperty("message", result.equals("problem") || lower.contains("discord") ? clean(why) : "");
        o.add("run", run);
        call("POST", "/event", o, true, null, null);
        ticks = STATUS_EVERY_TICKS - 10;
    }

    private static void sendShoppingList() {
        Map<net.minecraft.world.item.Item, Integer> needs = AutoBuild.INSTANCE.missingWhole;
        if (needs.isEmpty()) {
            return;
        }
        JsonArray items = new JsonArray();
        needs.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40).forEach(e -> {
            JsonObject it = new JsonObject();
            it.addProperty("name", new net.minecraft.world.item.ItemStack(e.getKey()).getHoverName().getString());
            it.addProperty("count", e.getValue());
            items.add(it);
        });
        JsonObject o = new JsonObject();
        o.addProperty("build", AutoBuild.placementNames());
        o.add("items", items);
        call("POST", "/shopping-list", o, true, null, null);
    }

    /** No colour codes, and nothing that looks like coordinates. */
    private static String clean(String s) {
        return s.replaceAll("§.", "").replaceAll("-?\\d+[, ]+-?\\d+[, ]+-?\\d+", "(position)");
    }

    // ------------------------------------------------------------------ suggestions in game

    public static void suggestions() {
        if (!enabled()) {
            Chat.warn("Discord sync is off.");
            return;
        }
        call("GET", "/suggestions", null, false, r -> {
            JsonObject o = json(r);
            if (!o.has("suggestions") || o.getAsJsonArray("suggestions").isEmpty()) {
                Chat.info("No open suggestions. Add one with /suggest on Discord.");
                return;
            }
            Chat.info("§lTop suggestions§r (vote: /printeraddon vote <number> up|down)");
            int n = 0;
            for (JsonElement el : o.getAsJsonArray("suggestions")) {
                if (n++ >= 8) {
                    break;
                }
                JsonObject s = el.getAsJsonObject();
                Chat.info("§b#" + s.get("number").getAsInt() + "§r " + s.get("title").getAsString() + "  §a✔" + s.get("up").getAsInt()
                        + " §c✘" + s.get("down").getAsInt() + (s.get("status").getAsString().equals("Planned") ? " §e(planned)" : ""));
            }
        }, e -> Chat.warn("Couldn't reach the Discord bot: " + shortError(e)));
    }

    public static void vote(int number, String vote) {
        if (!isLinked()) {
            Chat.warn("Link with Discord first: /link on Discord, then J+L.");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("number", number);
        o.addProperty("vote", vote);
        call("POST", "/vote", o, true, r -> {
            JsonObject j = json(r);
            Chat.info(r.statusCode() == 200 ? "Voted " + vote + " on suggestion #" + number + "."
                    : j.has("error") ? j.get("error").getAsString() : "Vote failed.");
        }, e -> Chat.warn("Couldn't reach the Discord bot: " + shortError(e)));
    }

    public static void status() {
        if (!isLinked()) {
            Chat.info("Not linked. Type §b/link§r on the Printer Addon Discord, then press §bJ+L§r here and enter the code.");
            return;
        }
        Chat.info("Linked with Discord as §b" + discordName + "§r. Sync is " + (enabled() ? "§aon" : "§coff") + "§r.");
        call("GET", "/health", null, false, r -> Chat.info("Bot: " + (r.statusCode() == 200 ? "§aonline" : "§cproblem " + r.statusCode())),
                e -> Chat.warn("Bot: offline (" + shortError(e) + ")"));
    }
}
