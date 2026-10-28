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

    private static final int STATUS_EVERY_TICKS = 100;   // 5 s while building
    private static final int IDLE_STATUS_EVERY_TICKS = 600; // 30 s when nothing runs
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
    /** Alerts that couldn't be sent (no internet, bot offline): sent once it answers again. */
    private static final ArrayDeque<JsonObject> UNSENT = new ArrayDeque<>();
    /** The player wants a time-lapse: the schematic is sent when a build starts. */
    private static boolean wantSchematic;

    // ------------------------------------------------------------------ settings and token

    public static boolean enabled() {
        return Configs.Generic.DISCORD_SYNC.getBooleanValue() && !api().isEmpty();
    }

    /** The Printer Addon bot (through the relay on the developer's droplet). */
    public static final String DEFAULT_API = "http://139.59.156.198/api/v1";
    /** Older defaults (0.16.0: this Mac; up to 0.17.3: port 8787, blocked on some networks): saved settings with them get the real address. */
    private static final java.util.Set<String> OLD_DEFAULTS = java.util.Set.of("http://localhost:8787/api/v1",
            "http://139.59.156.198:8787/api/v1");

    private static String api() {
        String url = Configs.Generic.DISCORD_API.getStringValue().trim();
        if (OLD_DEFAULTS.contains(url)) {
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
        Chat.warn("The Discord link isn't valid any more (linked from another game, or unlinked). Type /link on Discord and press J+L here to link again.");
        Chat.announce("Discord link lost", "type /link on Discord, then J+L", false);
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
            if (wantSchematic && AutoBuild.INSTANCE.isRunning()) {
                uploadSchematic();
            }
        }
        wasActive = active;
        if (active && !shoppingSent && activeSince >= 0 && ticks - activeSince > SHOPPING_LIST_AFTER_TICKS) {
            shoppingSent = true;
            sendShoppingList();
        }
        if (ticks % (active ? STATUS_EVERY_TICKS : IDLE_STATUS_EVERY_TICKS) == 0 && !busyStatus) {
            sendStatus(mc);
        }
        if (ticks % POLL_EVERY_TICKS == 0 && !busyPoll) {
            poll(mc);
        }
        if (ticks % 1200 == 40 && mc.player != null) {
            sendPrices(mc);
        }
    }

    /** When the shop prices were last sent (the scan file's time): sent again after a new scan. */
    private static long pricesSentFor;

    /** The shop's prices (from J+S), so the schematic library on Discord can say what a build costs on this server. */
    private static void sendPrices(Minecraft mc) {
        long scanned = no.sindrens.printeraddon.build.CostEstimate.scanTime(mc);
        if (scanned == 0 || scanned == pricesSentFor || mc.getCurrentServer() == null) {
            return;
        }
        java.util.Map<String, Double> prices = no.sindrens.printeraddon.build.CostEstimate.unitPrices(mc);
        if (prices.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("server", mc.getCurrentServer().ip.toLowerCase(Locale.ROOT));
        JsonObject p = new JsonObject();
        prices.forEach(p::addProperty);
        o.add("prices", p);
        call("POST", "/prices", o, true, r -> pricesSentFor = scanned, null);
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
            s.addProperty("detail", "auto break: " + AreaBreaker.INSTANCE.where());
            double done = AreaBreaker.INSTANCE.progressPercent();
            if (done >= 0) {
                s.addProperty("progress", Math.round(done * 10) / 10.0);
            }
            long eta = AreaBreaker.INSTANCE.etaSeconds();
            if (eta >= 0) {
                s.addProperty("eta_s", eta);
            }
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
            s.addProperty("layer_text", build.layers.describeLayers());
            if (!no.sindrens.printeraddon.build.BuildProfile.last().isEmpty()) {
                s.addProperty("kind", no.sindrens.printeraddon.build.BuildProfile.last());
            }
            if (perMinute > 0) {
                s.addProperty("per_min", Math.round(perMinute));
            }
            long millis = build.stats.runMillis();
            if (millis > 5 * 60_000 && build.spentTotal() > 0) {
                s.addProperty("spent_per_hour", Math.round(build.spentTotal() * 3_600_000.0 / millis));
            }
            if (build.isCapping()) {
                s.addProperty("detail", "covering open cactus, then stopping");
            }
        } else if (build.isRemotelyPaused()) {
            state = "paused";
            s.addProperty("detail", build.pausedText());
            s.addProperty("build", AutoBuild.placementNames());
        } else {
            state = "idle";
        }
        s.addProperty("state", state);
        String schedule = no.sindrens.printeraddon.build.Schedule.describe();
        if (!schedule.isEmpty()) {
            s.addProperty("schedule", schedule);
        }
        s.addProperty("version", version());
        if (mc.getCurrentServer() != null) {
            s.addProperty("server", mc.getCurrentServer().ip);
        }
        busyStatus = true;
        call("POST", "/status", s, true, r -> {
            busyStatus = false;
            toldOffline = false;
            flushUnsent();
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
            wantSchematic = o.has("upload_schematic") && o.get("upload_schematic").getAsBoolean();
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
            default -> {
                if (action.startsWith("schedule:")) {
                    String now = no.sindrens.printeraddon.build.Schedule.apply(action.substring(9));
                    Chat.info("Schedule from Discord: " + now + ".");
                    event("custom", "Schedule set: " + now + ".");
                } else {
                    PrinterAddon.LOGGER.info("Unknown Discord command {}", action);
                }
            }
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
        sendEvent(o);
    }

    private static void sendEvent(JsonObject o) {
        call("POST", "/event", o, true, null, e -> {
            UNSENT.addLast(o);
            while (UNSENT.size() > 20) {
                UNSENT.removeFirst();
            }
        });
    }

    private static void flushUnsent() {
        int n = UNSENT.size();
        for (int i = 0; i < n; i++) {
            sendEvent(UNSENT.removeFirst());
        }
    }

    /** End of a run (from RunLog#end): totals for stats and achievements, and the alert. */
    public static void runEnded(String why, int placed, double seconds, double spent, String what) {
        // Not for a disconnect ("kicked" says it) nor when the game closes (it isn't a problem)
        if (!enabled() || !isLinked() || why != null && (why.startsWith("disconnected") || why.equals(no.sindrens.printeraddon.build.LogShare.GAME_CLOSED))) {
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
        o.addProperty("message", result.equals("problem") ? clean(no.sindrens.printeraddon.build.Advice.withAdvice(why))
                : lower.contains("discord") ? clean(why) : "");
        o.add("run", run);
        sendEvent(o);
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

    // ------------------------------------------------------------------ time-lapse and schematic library

    /** The schematic being built goes to the bot, which draws the time-lapse GIF when it's finished. */
    private static void uploadSchematic() {
        for (var placement : fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            Path file = placement.isEnabled() ? placement.getSchematicFile() : null;
            if (file == null || !file.toString().endsWith(".litematic") || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (Files.size(file) > 16L * 1024 * 1024) {
                    return;
                }
                byte[] bytes = Files.readAllBytes(file);
                HttpRequest req = HttpRequest.newBuilder(URI.create(api() + "/build-schematic")).timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                PrinterAddon.LOGGER.info("Couldn't send the schematic for the time-lapse: {}", e.toString());
            }
            return;
        }
    }

    public static void schematics(String search) {
        if (!enabled()) {
            Chat.warn("Discord sync is off.");
            return;
        }
        String q = java.net.URLEncoder.encode(search == null ? "" : search, StandardCharsets.UTF_8);
        Minecraft mc0 = Minecraft.getInstance();
        String server = mc0.getCurrentServer() == null ? "" : java.net.URLEncoder.encode(mc0.getCurrentServer().ip.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
        call("GET", "/schematics?q=" + q + "&server=" + server, null, false, r -> {
            JsonObject o = json(r);
            JsonArray list = o.has("schematics") ? o.getAsJsonArray("schematics") : new JsonArray();
            if (list.isEmpty()) {
                Chat.info("No schematics found. Share one on Discord: /schematic share in #schematics.");
                return;
            }
            Chat.info("§lSchematic library§r" + (search == null || search.isBlank() ? "" : " · " + search)
                    + " (download: /printeraddon schematic get <id>)");
            int n = 0;
            for (JsonElement el : list) {
                if (n++ >= 10) {
                    break;
                }
                JsonObject s = el.getAsJsonObject();
                JsonArray size = s.getAsJsonArray("size");
                String stars = s.has("stars") && s.get("stars").getAsInt() > 0 ? " §e★" + s.get("stars").getAsInt() : "";
                String cost = s.has("cost") && !s.get("cost").isJsonNull() ? " §a~$" + String.format(Locale.ROOT, "%,d", s.get("cost").getAsLong()) : "";
                Chat.info("§b" + s.get("id").getAsString() + "§r " + s.get("name").getAsString() + stars + " §7" + size.get(0).getAsInt() + "×"
                        + size.get(1).getAsInt() + "×" + size.get(2).getAsInt() + ", " + s.get("blocks").getAsInt() + " blocks, by "
                        + s.get("by").getAsString() + cost);
            }
        }, e -> Chat.warn("Couldn't reach the Discord bot: " + shortError(e)));
    }

    public static void downloadSchematic(String id) {
        if (!enabled()) {
            Chat.warn("Discord sync is off.");
            return;
        }
        String clean = id.replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.ROOT);
        HttpRequest req = HttpRequest.newBuilder(URI.create(api() + "/schematics/" + clean + "/file")).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "PrinterAddon/" + version()).GET().build();
        Chat.info("Downloading schematic " + clean + "...");
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((r, e) -> Minecraft.getInstance().execute(() -> {
            if (e != null || r.statusCode() != 200) {
                Chat.warn(e != null ? "Couldn't reach the Discord bot: " + shortError(e) : "No schematic with that id.");
                return;
            }
            String name = r.headers().firstValue("Content-Disposition").map(h -> h.replaceAll(".*filename=\"?([^\"]+)\"?.*", "$1"))
                    .orElse(clean + ".litematic").replaceAll("[^A-Za-z0-9 _.-]", "_");
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("schematics").resolve("PrinterAddon");
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve(name), r.body());
                Chat.info("§aSaved§r schematics/PrinterAddon/" + name + ". To build it: Litematica (M) → Load schematics → PrinterAddon → "
                        + name + " → Load, place it where you want it, then J+B → START.");
                Chat.announce("Schematic downloaded", name, true);
            } catch (Exception ex) {
                Chat.warn("Couldn't save it: " + ex.getMessage());
            }
        }));
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
