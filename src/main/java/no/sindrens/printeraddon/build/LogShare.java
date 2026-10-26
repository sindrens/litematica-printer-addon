package no.sindrens.printeraddon.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Sends build logs to the add-on's developer (a Discord channel, through its webhook), to find bugs and make it
 * faster. With "Always send logs" (on by default) every run's log goes by itself when the run ends, however it ended:
 * finished, stopped by the player or by itself, a disconnect, the game closed; with it off the player is asked after
 * each run (see StopPrompt). A report from J+B ("Report / share log", see ShareLogGui) goes with the player's own words.
 * <p>
 * None get lost: each waits in the outbox (logs/printer_addon/outbox, a file each) until Discord has it. A failed send
 * is tried again (30 s, 2 min, 10 min ... for up to a week) and after a restart, and the log of a run that never ended
 * (the game crashed or was killed) goes on the next start. One thread sends them one by one, the player's own first.
 * Each report's title starts with the add-on version that wrote the log, and it's listed with its kind and version
 * for the developer's tool (see ReportIndex). With the log go a short summary and the full debug (see DebugInfo):
 * debug.txt, the game's own log without chat (of the game the run was in), a crash report from the last day, and the
 * screenshot the player adds. Each one sent is noted in logs/printer_addon/sent.txt.
 */
public final class LogShare {
    /**
     * The developer's log channel, a Discord webhook URL ("Log channel" in the settings goes first). It's packed into
     * the jar from src/main/resources/printer_addon/log-channel.txt (reversed, Base64), which never goes into the
     * repository: a webhook URL in a public repository gets found and spammed. Without the file: nowhere to send logs.
     */
    @Nullable
    private static volatile String developerChannel;
    /**
     * Discord takes 10 MB a message: everything sent stays under this, the run's log gzipped (or only its last part)
     * when it doesn't fit next to the rest, and the screenshot left out before the log is cut.
     */
    private static final int MAX_BYTES = 9 * 1024 * 1024 + 512 * 1024;
    private static final int DEBUG_BYTES = 512 * 1024;
    /** A bigger log is read from its end only (an hour was 19.5 MB before the logs got smaller). */
    private static final int MAX_READ = 64 * 1024 * 1024;
    /** The most the report screen takes: the player's own words, and what they were building. */
    public static final int MAX_PROBLEM = 2000;
    public static final int MAX_BUILDING = 200;
    /** The waits after a failed send, try by try, the last again and again: 30 s, 2, 10 and 30 minutes, an hour. */
    private static final long[] RETRY_MS = {30_000, 120_000, 600_000, 1_800_000, 3_600_000};
    /** Given up after this many tries, or a week after it was made (the webhook gone, no way to Discord at all). */
    private static final int MAX_TRIES = 12;
    private static final long MAX_AGE_MS = 7L * 24 * 3_600_000;
    /** A start with nothing to build is sent at most once in this time (start pressed again and again). */
    private static final long NOT_STARTED_EVERY_MS = 10 * 60_000;
    /** Between two sends: Discord takes 30 messages a minute in a channel, and each report edits the index too. */
    private static final long BETWEEN_MS = 3_000;
    /** sent.txt: trimmed to the last KEEP_SENT lines once it's longer than this. */
    private static final int KEEP_SENT = 300;

    /** Why a run ended when the game was closed during it (see AutoBuild.gameClosing). */
    public static final String GAME_CLOSED = "the game was closed";
    /** A run that never ended (found on the next start): with a crash report since it started, else without. */
    private static final String CRASHED = "the game crashed during the run";
    private static final String NEVER_ENDED = "the run never ended: the game stopped without closing (killed, frozen, or a crash without a report)";

    /** This game, from its start: a report made in an earlier one reads that game's log (see DebugInfo.gameLog). */
    private static final String GAME = Long.toString(System.currentTimeMillis(), 36);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter SENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** The version in a log's name ("2026-10-26 12.00.00 auto build farm v0.17.2.jsonl"). */
    private static final Pattern LOG_VERSION = Pattern.compile(" v(\\S+)\\.jsonl$");
    /** The note in the outbox while a run goes on: left there, the run never ended. */
    private static final String RUNNING = "running.json";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** The reports waiting to go: the player's own first, then the oldest. Guarded by itself. */
    private static final List<Job> queue = new ArrayList<>();
    private static final Comparator<Job> ORDER = Comparator.comparing((Job job) -> job.automatic).thenComparingLong(job -> job.created);
    /** The thread sending them (null: none, it ends once the queue is empty). Guarded by queue. */
    @Nullable
    private static Thread worker;
    private static volatile boolean sending;
    /** The outbox read, once after the game starts; the note about sending logs looked at, once in a world. */
    private static boolean loaded;
    private static boolean noticeChecked;
    private static long lastNotStarted;

    private static final int RED = 0xE74C3C;
    private static final int ORANGE = 0xF59E0B;
    private static final int GREEN = 0x22C55E;
    private static final int BLUE = 0x3498DB;
    private static final int LIGHT_BLUE = 0x60A5FA;
    private static final int PURPLE = 0x8B5CF6;
    private static final int DARK_RED = 0x991B1B;

    private LogShare() {
    }

    /** What the player says with the log (see ShareLogGui): their words, what they clicked, what they were building. */
    public record Report(String problem, List<String> picked, String building, @Nullable Path screenshot) {
        boolean saysSomething() {
            return !problem.isBlank() || !picked.isEmpty();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("problem", problem);
            JsonArray list = new JsonArray();
            picked.forEach(list::add);
            o.add("picked", list);
            o.addProperty("building", building);
            if (screenshot != null) {
                o.addProperty("screenshot", screenshot.toString());
            }
            return o;
        }

        static Report fromJson(JsonObject o) {
            List<String> picked = new ArrayList<>();
            o.getAsJsonArray("picked").forEach(e -> picked.add(e.getAsString()));
            String shot = text(o, "screenshot");
            return new Report(o.get("problem").getAsString(), picked, o.get("building").getAsString(), shot == null ? null : Path.of(shot));
        }
    }

    /** A file of the message. */
    private record Part(String name, String type, byte[] bytes) {
    }

    /** What a send came to: the message's id, or why not, whether trying again may help and how long to wait first. */
    private record Result(@Nullable String messageId, @Nullable String error, boolean again, long waitMs) {
        static Result sent(String id) {
            return new Result(id, null, false, 0);
        }

        static Result failed(String error, boolean again, long waitMs) {
            return new Result(null, error, again, waitMs);
        }
    }

    /**
     * One report waiting to go, with everything gathered on the game's thread when it was made (the game's state then).
     * Saved in the outbox as JSON, so it goes after a restart too.
     */
    private static final class Job {
        final String id;
        final long created;
        /** The game it was made in, and a time that game was still running: its game log is the one written then. */
        final String game;
        final long seen;
        /** P problem, S stopped by itself, F finished, U stopped by the player, L log, D disconnected, C game closed. */
        final char kind;
        /** The add-on version that wrote the log (a log from before an update says so). */
        final String version;
        final String player;
        /** What ran ("Auto build: farm, Y -56 to 223"), "-" when nothing did. */
        final String what;
        final boolean automatic;
        @Nullable
        final String ended;
        final String head;
        final String summary;
        final String debug;
        @Nullable
        final String log;
        @Nullable
        final String crash;
        @Nullable
        final Report report;
        int tries;
        long nextTry;
        String lastError = "";

        Job(String id, long created, String game, long seen, char kind, String version, String player, String what, boolean automatic,
            @Nullable String ended, String head, String summary, String debug, @Nullable String log, @Nullable String crash,
            @Nullable Report report) {
            this.id = id;
            this.created = created;
            this.game = game;
            this.seen = seen;
            this.kind = kind;
            this.version = version;
            this.player = player;
            this.what = what;
            this.automatic = automatic;
            this.ended = ended;
            this.head = head;
            this.summary = summary;
            this.debug = debug;
            this.log = log;
            this.crash = crash;
            this.report = report;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("created", created);
            o.addProperty("game", game);
            o.addProperty("seen", seen);
            o.addProperty("kind", String.valueOf(kind));
            o.addProperty("version", version);
            o.addProperty("player", player);
            o.addProperty("what", what);
            o.addProperty("automatic", automatic);
            o.addProperty("ended", ended);
            o.addProperty("head", head);
            o.addProperty("summary", summary);
            o.addProperty("debug", debug);
            o.addProperty("log", log);
            o.addProperty("crash", crash);
            if (report != null) {
                o.add("report", report.toJson());
            }
            o.addProperty("tries", tries);
            o.addProperty("nextTry", nextTry);
            o.addProperty("lastError", lastError);
            return o;
        }

        static Job fromJson(JsonObject o) {
            Job job = new Job(o.get("id").getAsString(), o.get("created").getAsLong(), o.get("game").getAsString(), o.get("seen").getAsLong(),
                    o.get("kind").getAsString().charAt(0), o.get("version").getAsString(), o.get("player").getAsString(), o.get("what").getAsString(),
                    o.get("automatic").getAsBoolean(), text(o, "ended"), o.get("head").getAsString(), o.get("summary").getAsString(),
                    o.get("debug").getAsString(), text(o, "log"), text(o, "crash"),
                    o.has("report") && o.get("report").isJsonObject() ? Report.fromJson(o.getAsJsonObject("report")) : null);
            job.tries = o.get("tries").getAsInt();
            job.nextTry = o.get("nextTry").getAsLong();
            job.lastError = o.get("lastError").getAsString();
            return job;
        }

        String logName() {
            return log == null ? "a report without a log" : Path.of(log).getFileName().toString();
        }
    }

    @Nullable
    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Where logs go, or "" when nowhere. */
    static String channel() {
        String own = Configs.Generic.LOG_CHANNEL.getStringValue().trim();
        return own.isEmpty() ? developerChannel() : own;
    }

    private static synchronized String developerChannel() {
        if (developerChannel == null) {
            String found = "";
            try (java.io.InputStream in = LogShare.class.getResourceAsStream("/printer_addon/log-channel.txt")) {
                if (in != null) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) {
                        found = new StringBuilder(new String(java.util.Base64.getDecoder().decode(text), StandardCharsets.UTF_8))
                                .reverse().toString();
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't read the log channel", e);
            }
            developerChannel = found;
        }
        return developerChannel;
    }

    private static Path outbox() {
        return BuildLog.folder().resolve("outbox");
    }

    // ---- runs: started, ended, the game closing, and the next start

    /**
     * A run started (see RunLog): noted in the outbox until it ends, so its log still goes when the game crashes or is
     * killed during it (found on the next start).
     */
    static void runStarted(@Nullable Path log, String version) {
        if (log == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("log", log.toString());
        o.addProperty("version", version);
        o.addProperty("started", System.currentTimeMillis());
        write(outbox().resolve(RUNNING), o.toString());
    }

    /**
     * After each run: with "Always send logs" its log goes at once, whatever ended it (a start with nothing to build at
     * most every 10 minutes); else the player is asked, when {@code ask} (not after a disconnect, the game closing, a
     * start with nothing to build, or a stop from Discord), see StopPrompt.
     */
    public static void afterRun(Minecraft mc, String why, boolean ask) {
        try {
            Files.deleteIfExists(outbox().resolve(RUNNING));
        } catch (IOException | RuntimeException ignored) {
            // left there: sent once more on the next start, as a run that never ended
        }
        try {
            if (channel().isEmpty()) {
                return;
            }
            if (Configs.Generic.SHARE_LOGS.getBooleanValue()) {
                if ("could-not-start".equals(why)) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotStarted < NOT_STARTED_EVERY_MS) {
                        return;
                    }
                    lastNotStarted = now;
                }
                share(mc, true, null, why);
            } else if (ask) {
                StopPrompt.ask(why, RunLog.describeRun().startsWith("Auto break") ? "Auto break" : "Auto build");
            }
        } catch (RuntimeException | LinkageError e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't share the log", e);
        }
    }

    /**
     * Every client tick, also out of a world: once after the game starts, the outbox is read (what didn't go last time,
     * and the log of a run that never ended); once in a world, the player is told that logs go by themselves.
     */
    public static void tick(Minecraft mc) {
        if (!loaded) {
            loaded = true;
            try {
                loadOutbox(mc);
            } catch (RuntimeException | LinkageError e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't read the log outbox", e);
            }
        }
        if (!noticeChecked && mc.player != null) {
            noticeChecked = true;
            if (Configs.Generic.SHARE_LOGS.getBooleanValue() && !Configs.logsNoticeShown && !channel().isEmpty()) {
                Configs.logsNoticeShown = true;
                Configs.saveToFile();
                Chat.warn("Printer Addon sends each auto build and Auto break run's log to its developer by itself, to find bugs and"
                        + " make it faster (the log and debug: no chat, no chest or vault spots). Off: J+B -> Generic -> 'Always send logs'.");
            }
        }
    }

    /** The jobs that didn't go before the game closed, and the log of a run that never ended. */
    private static void loadOutbox(Minecraft mc) {
        Path dir = outbox();
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Job> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.toList();
        } catch (IOException e) {
            return;
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                if (name.endsWith(".tmp")) {
                    Files.deleteIfExists(file);
                } else if (name.endsWith(".json") && !name.equals(RUNNING)) {
                    found.add(Job.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()));
                }
            } catch (IOException | RuntimeException e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] A report in the outbox couldn't be read ({}), it's dropped: {}", name, e.toString());
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // it's tried again on the next start
                }
            }
        }
        Path running = dir.resolve(RUNNING);
        if (Files.isRegularFile(running)) {
            try {
                Job job = neverEnded(mc, JsonParser.parseString(Files.readString(running, StandardCharsets.UTF_8)).getAsJsonObject());
                if (job != null) {
                    save(job);
                    found.add(job);
                }
            } catch (IOException | RuntimeException e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't read the note of the last run: {}", e.toString());
            }
            try {
                Files.deleteIfExists(running);
            } catch (IOException ignored) {
                // read again on the next start
            }
        }
        if (!found.isEmpty()) {
            PrinterAddon.LOGGER.info("[Printer Addon] {} report(s) in the outbox go to the developer now", found.size());
            synchronized (queue) {
                queue.addAll(found);
                queue.sort(ORDER);
                startWorker();
            }
        }
    }

    /**
     * The run the note is about never ended: the game crashed, froze or was killed during it. Its log goes now, with a
     * crash report made since it started; null when logs don't go by themselves.
     */
    @Nullable
    private static Job neverEnded(Minecraft mc, JsonObject note) {
        if (!Configs.Generic.SHARE_LOGS.getBooleanValue() || channel().isEmpty()) {
            return null;
        }
        Path log = Path.of(note.get("log").getAsString());
        boolean there = Files.isRegularFile(log);
        long started = note.get("started").getAsLong();
        DebugInfo.Recent crash = DebugInfo.recentCrashReport(mc);
        boolean crashed = crash != null && crash.file().toFile().lastModified() >= started;
        String ended = crashed ? CRASHED + ": " + DebugInfo.crashDescription(crash.file()) : NEVER_ENDED;
        String version = note.get("version").getAsString();
        String name = log.getFileName().toString();
        String summary = String.join("\n",
                "**Ran:** " + name.replaceFirst("^\\S+ \\S+ ", "").replaceFirst(" v\\S+\\.jsonl$", "")
                        + " (started " + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(started), java.time.ZoneId.systemDefault())
                        .format(SENT_TIME) + ")",
                "**Ended:** " + ended,
                "**Log:** " + (there ? "up to the moment it stopped (no RUN-END)" : "gone"));
        String head = header(mc, true, version);
        long now = System.currentTimeMillis();
        return new Job(newId('C'), now, "", there ? log.toFile().lastModified() : started, 'C', version, playerName(mc), "-", true, ended,
                head, summary, gather(mc, null, head, summary, there ? log : null), there ? log.toString() : null,
                crashed ? crash.file().toString() : null, null);
    }

    // ---- making a report

    /**
     * Sends the run's log (the one going on now, else the last one, else the newest there is) with the player's report
     * (null: none, as after a run) and the full debug. A report goes without a log too (nothing ran yet).
     * {@code ended}: why the run ended, when it's sent after a run. It waits in the outbox until it's sent.
     */
    public static void share(Minecraft mc, boolean automatic, @Nullable Report report, @Nullable String ended) {
        String url = channel();
        if (url.isEmpty()) {
            if (!automatic) {
                Chat.warn("No log channel set (Log channel in the settings): nowhere to send the log to.");
            }
            return;
        }
        if (!url.startsWith("https://")) {
            Chat.warn("The log channel isn't a web address (https://...): set a Discord webhook URL as Log channel.");
            return;
        }
        Path file = RunLog.runningFile();
        String summary = "";
        if (file == null) {
            file = RunLog.lastFile();
            summary = RunLog.lastSummary();
            if (file == null || !Files.isRegularFile(file)) {
                file = newestLog();
                summary = "";
            }
        }
        if (file == null && report == null) {
            if (!automatic) {
                Chat.warn("No build log yet: it's written while auto build or Auto break runs.");
            }
            return;
        }
        char kind = kindOf(report, ended);
        String version = file == null ? modVersion(PrinterAddon.MOD_ID) : logVersion(file);
        // The game's state now, on the game's thread; the files are read and sent on the sending thread
        String head = header(mc, automatic, version);
        DebugInfo.Recent crash = DebugInfo.recentCrashReport(mc);
        long now = System.currentTimeMillis();
        Job job = new Job(newId(kind), now, GAME, now, kind, version, playerName(mc), RunLog.describeRun(), automatic, ended, head, summary,
                gather(mc, report, head, summary, file), file == null ? null : file.toString(), crash == null ? null : crash.file().toString(), report);
        boolean after = add(job);
        if (report != null) {
            // It's in the outbox now, sent also after a restart: the next report starts empty
            no.sindrens.printeraddon.gui.ShareLogGui.clearDraft();
        }
        if (!automatic) {
            String what = report != null && report.saysSomething() ? "your report" : file == null ? "the debug" : file.getFileName().toString();
            Chat.info("Sending " + what + " to the add-on's developer" + (after ? " (after the ones waiting)" : "") + "...");
        }
    }

    private static String gather(Minecraft mc, @Nullable Report report, String head, String summary, @Nullable Path log) {
        try {
            return DebugInfo.collect(mc, report, head, summary, log);
        } catch (RuntimeException | LinkageError e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't gather the debug", e);
            return "The debug couldn't be gathered: " + e;
        }
    }

    /** A run that ended because everything was done ("finished" for auto build, "done" for Auto break). */
    public static boolean isFinished(String why) {
        return "finished".equals(why) || "done".equals(why);
    }

    /** A run the player stopped ("stopped-by-you" for auto build, "stopped" for Auto break), also from Discord. */
    public static boolean byPlayer(String why) {
        return "stopped-by-you".equals(why) || "stopped".equals(why) || "stopped from Discord".equals(why) || "paused from Discord".equals(why);
    }

    /** The connection ended during the run ("disconnected: <why>" for auto build, which picks up again once back). */
    private static boolean isDisconnect(String why) {
        return why.startsWith("disconnected") || "the connection ended".equals(why) || "left the world".equals(why);
    }

    /** The game was closed, crashed or killed during the run. */
    private static boolean gameEnded(String why) {
        return why.contains(GAME_CLOSED) || why.startsWith(CRASHED) || why.equals(NEVER_ENDED);
    }

    private static char kindOf(@Nullable Report report, @Nullable String ended) {
        if (report != null && report.saysSomething()) {
            return 'P';
        }
        if (ended == null) {
            return 'L';
        }
        return isFinished(ended) ? 'F' : byPlayer(ended) ? 'U' : gameEnded(ended) ? 'C' : isDisconnect(ended) ? 'D' : 'S';
    }

    private static String kindName(char kind) {
        return switch (kind) {
            case 'P' -> "Problem";
            case 'S' -> "Stopped by itself";
            case 'F' -> "Finished";
            case 'U' -> "Stopped by the player";
            case 'D' -> "Disconnected";
            case 'C' -> "Game closed or crashed";
            default -> "Log";
        };
    }

    private static String newId(char kind) {
        return LocalDateTime.now().format(ID_TIME) + "-" + kind + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0x100000));
    }

    /** The add-on version that wrote a log, from its name; this one's when the name doesn't say. */
    private static String logVersion(Path log) {
        Matcher m = LOG_VERSION.matcher(log.getFileName().toString());
        return m.find() ? m.group(1) : modVersion(PrinterAddon.MOD_ID);
    }

    private static String playerName(Minecraft mc) {
        return mc.getUser() == null ? "?" : mc.getUser().getName();
    }

    /** The newest build log (.jsonl), or null. */
    @Nullable
    private static Path newestLog() {
        try (Stream<Path> files = Files.list(BuildLog.folder())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Versions, server, player, and what's going on right now. */
    private static String header(Minecraft mc, boolean automatic, String logVersion) {
        String state = AutoBuild.INSTANCE.isRunning() ? "auto build running (" + AutoBuild.INSTANCE.layers.status() + ")"
                : AreaBreaker.INSTANCE.isRunning() ? "Auto break running (" + AreaBreaker.INSTANCE.status() + ")"
                : "nothing running";
        String own = modVersion(PrinterAddon.MOD_ID);
        return String.join("\n",
                "**Versions:** Printer Addon " + own + (logVersion.equals(own) ? "" : " (the log is from " + logVersion + ")")
                        + " | Minecraft " + modVersion("minecraft") + " | Litematica " + modVersion("litematica")
                        + " | Printer " + modVersion("litematica_printer") + " | MaLiLib " + modVersion("malilib") + " | Fabric " + modVersion("fabricloader"),
                "**Player:** " + playerName(mc) + " on " + no.sindrens.printeraddon.util.ServerName.current(mc),
                "**Now:** " + state,
                "**Settings:** printer interval " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.getIntegerValue()
                        + ", range " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue()
                        + ", speed preset " + Configs.Generic.SPEED_PRESET.getOptionListValue().getStringValue()
                        + ", ping " + ServerLag.pingTicks(mc) * 50 + " ms",
                "**Sent:** " + (automatic ? "automatically (Always send logs is on)" : "by the player"));
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("-");
    }

    // ---- the outbox and the sending thread

    /** Into the outbox and the queue; true when others go first. */
    private static boolean add(Job job) {
        save(job);
        synchronized (queue) {
            boolean after = sending || !queue.isEmpty();
            queue.add(job);
            queue.sort(ORDER);
            startWorker();
            return after;
        }
    }

    /** The sending thread, unless it runs (then it's woken: there's a new one). Holding queue. */
    private static void startWorker() {
        if (worker != null) {
            queue.notifyAll();
            return;
        }
        Thread thread = new Thread(LogShare::work, "Printer Addon log share");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    /** The build logs waiting in the outbox: not deleted for being old (see BuildLog). */
    static Set<String> waitingLogs() {
        Set<String> names = new HashSet<>();
        synchronized (queue) {
            for (Job job : queue) {
                if (job.log != null) {
                    names.add(Path.of(job.log).getFileName().toString());
                }
            }
        }
        return names;
    }

    /** For the debug: how many wait, since when, and the last error. */
    static String outboxStatus() {
        synchronized (queue) {
            if (queue.isEmpty()) {
                return sending ? "one being sent" : "empty";
            }
            Job oldest = queue.stream().min(Comparator.comparingLong(job -> job.created)).orElseThrow();
            String errors = queue.stream().filter(job -> !job.lastError.isEmpty()).map(job -> job.lastError).findFirst().orElse("");
            return queue.size() + " waiting, the oldest made " + (System.currentTimeMillis() - oldest.created) / 60_000 + " min ago"
                    + (errors.isEmpty() ? "" : ", last error: " + errors);
        }
    }

    private static void save(Job job) {
        write(outbox().resolve(job.id + ".json"), job.toJson().toString());
    }

    private static void delete(Job job) {
        try {
            Files.deleteIfExists(outbox().resolve(job.id + ".json"));
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't take a sent report out of the outbox: {}", e.toString());
        }
    }

    /** A whole file or none: written next to it, then moved over it. */
    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't write {}: {}", file.getFileName(), e.toString());
        }
    }

    /** The sending thread: the first job that's due, one at a time, until none are left. */
    private static void work() {
        try {
            sendAll();
        } finally {
            // Ended by an error: the next report starts a new thread (the waiting ones stay in the queue)
            synchronized (queue) {
                if (worker == Thread.currentThread()) {
                    worker = null;
                }
            }
        }
    }

    private static void sendAll() {
        Minecraft mc = Minecraft.getInstance();
        while (true) {
            Job job = null;
            synchronized (queue) {
                long now = System.currentTimeMillis();
                long wake = Long.MAX_VALUE;
                for (Job waiting : queue) {
                    if (waiting.nextTry <= now) {
                        job = waiting;
                        break;
                    }
                    wake = Math.min(wake, waiting.nextTry);
                }
                if (job == null) {
                    if (queue.isEmpty()) {
                        worker = null;
                        return;
                    }
                    try {
                        queue.wait(Math.max(1_000, Math.min(wake - now, 60_000)));
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                sending = true;
            }
            Result result;
            try {
                result = send(mc.gameDirectory.toPath(), job);
            } catch (RuntimeException | LinkageError e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't send a log", e);
                result = Result.failed(e.getClass().getSimpleName(), false, 0);
            } finally {
                sending = false;
            }
            try {
                finish(mc, job, result);
            } catch (RuntimeException e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] After sending a log", e);
            }
            try {
                Thread.sleep(BETWEEN_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** After a send: out of the outbox when it went (or can't ever go), else tried again later. */
    private static void finish(Minecraft mc, Job job, Result result) {
        long now = System.currentTimeMillis();
        boolean done;
        long wait = 0;
        synchronized (queue) {
            if (result.error() == null) {
                done = true;
            } else {
                job.tries++;
                job.lastError = result.error();
                done = !result.again() || job.tries >= MAX_TRIES || now - job.created > MAX_AGE_MS;
                if (!done) {
                    wait = Math.max(result.waitMs(), RETRY_MS[Math.min(job.tries, RETRY_MS.length) - 1]);
                    job.nextTry = now + wait;
                }
            }
            if (done) {
                queue.remove(job);
            }
        }
        if (done) {
            delete(job);
        } else {
            save(job);
        }
        if (result.error() == null) {
            noteSent(job, result.messageId());
        }
        String name = job.logName();
        String what = job.report != null && job.report.saysSomething() ? "Your report" : job.log == null ? "The debug" : "The log";
        long seconds = wait / 1000;
        mc.execute(() -> {
            if (result.error() == null) {
                if (!job.automatic) {
                    Chat.info(what + " went to the add-on's developer. Thanks!");
                }
                BuildLog.lineOrKeep("SHARE", "sent " + name + " as report " + result.messageId() + " (" + kindName(job.kind) + ", v" + job.version
                        + (job.tries > 0 ? ", try " + (job.tries + 1) : "") + ")");
            } else if (done) {
                if (!job.automatic) {
                    Chat.warn("Couldn't send it: " + result.error() + ".");
                }
                BuildLog.lineOrKeep("SHARE", "gave up on " + name + " after " + job.tries + (job.tries == 1 ? " try: " : " tries: ") + result.error());
            } else {
                if (!job.automatic && job.tries == 1) {
                    Chat.warn("Couldn't send it now (" + result.error() + "): it goes by itself as soon as it can, also after a restart.");
                }
                BuildLog.lineOrKeep("SHARE", "couldn't send " + name + " (try " + job.tries + "): " + result.error() + ", again in "
                        + (seconds < 120 ? seconds + " s" : seconds / 60 + " min"));
            }
        });
    }

    /** sent.txt: when, what, which version and the report's id, the newest last (the last 300). */
    private static void noteSent(Job job, @Nullable String messageId) {
        Path file = BuildLog.folder().resolve("sent.txt");
        String line = LocalDateTime.now().format(SENT_TIME) + " " + job.kind + " v" + job.version + " report " + messageId + " " + job.logName()
                + (job.automatic ? "" : " (by the player)") + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() > KEEP_SENT + 100) {
                Files.write(file, lines.subList(lines.size() - KEEP_SENT, lines.size()), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't note the sent report: {}", e.toString());
        }
    }

    // ---- the message

    /**
     * One report to Discord: its files, the embed (version, kind, the player's words, the summary), the index.
     * {@code gameDir}: the game's folder (its log and crash reports).
     */
    private static Result send(Path gameDir, Job job) {
        String url = channel();
        if (url.isEmpty() || !url.startsWith("https://")) {
            return Result.failed("no log channel", false, 0);
        }
        try {
            List<String> notes = new ArrayList<>();
            byte[] details = cut(job.debug.getBytes(StandardCharsets.UTF_8), DEBUG_BYTES);
            byte[] game = DebugInfo.gameLog(gameDir, GAME.equals(job.game), job.seen);
            byte[] crashText = job.crash == null || !Files.isRegularFile(Path.of(job.crash)) ? new byte[0] : DebugInfo.crashReport(Path.of(job.crash));
            long used = details.length + game.length + crashText.length;
            byte[] shot = null;
            Report report = job.report;
            if (report != null && report.screenshot() != null) {
                try {
                    shot = Files.readAllBytes(report.screenshot());
                } catch (IOException e) {
                    notes.add("the screenshot couldn't be read");
                }
            }
            List<Part> parts = new ArrayList<>();
            Path log = job.log == null ? null : Path.of(job.log);
            if (log != null && !Files.isRegularFile(log)) {
                notes.add("the log file is gone");
                log = null;
            }
            if (log != null) {
                byte[] bytes = readLog(log, notes);
                String name = log.getFileName().toString();
                // As it is, else gzipped; the log goes first: without the screenshot before it's cut to its last part
                Part logPart = null;
                byte[] zipped = null;
                while (logPart == null) {
                    long room = MAX_BYTES - used - (shot == null ? 0 : shot.length);
                    if (bytes.length <= room) {
                        logPart = new Part(name, "application/x-ndjson", bytes);
                        break;
                    }
                    if (zipped == null) {
                        zipped = gzip(bytes);
                    }
                    if (zipped.length <= room) {
                        logPart = new Part(name + ".gz", "application/gzip", zipped);
                        notes.add(String.format(Locale.ROOT, "the log %.1f MB, gzipped", bytes.length / 1048576.0));
                    } else if (shot != null) {
                        shot = null;
                        notes.add("the screenshot left out, no room");
                    } else {
                        byte[] tail = tail(bytes, (int) Math.max(0, MAX_BYTES - used));
                        logPart = new Part(name.replace(".jsonl", "-end.jsonl"), "application/x-ndjson", tail);
                        notes.add(String.format(Locale.ROOT, "only the last %.1f of the log's %.1f MB", tail.length / 1048576.0, bytes.length / 1048576.0));
                    }
                }
                parts.add(logPart);
                used += logPart.bytes().length;
            }
            if (shot != null && used + shot.length > MAX_BYTES) {
                shot = null;
                notes.add("the screenshot left out, too big");
            }
            parts.add(new Part("debug.txt", "text/plain; charset=utf-8", details));
            if (game.length > 0) {
                parts.add(new Part("game-log.txt", "text/plain; charset=utf-8", game));
            }
            if (crashText.length > 0) {
                parts.add(new Part("crash-report.txt", "text/plain; charset=utf-8", crashText));
            }
            if (shot != null) {
                parts.add(new Part("screenshot.png", "image/png", shot));
            }

            StringBuilder description = new StringBuilder();
            String ended = job.ended;
            if (ended != null) {
                description.append(isFinished(ended) ? "**Finished:** everything was done" : byPlayer(ended)
                        ? "**Stopped by the player**" + (ended.contains("Discord") ? " (" + ended + ")" : "")
                        : "**Ended:** " + cut(ended, 500)).append('\n');
            }
            if (report != null) {
                if (!report.picked().isEmpty()) {
                    description.append("**Problem:** ").append(String.join(", ", report.picked())).append('\n');
                }
                if (!report.problem().isBlank()) {
                    // As a quote, line by line
                    description.append("> ").append(cut(report.problem().trim(), 1500).replace("\n", "\n> ")).append('\n');
                }
                if (!report.building().isBlank()) {
                    description.append("**Building:** ").append(cut(report.building().trim(), MAX_BUILDING)).append('\n');
                }
            }
            if (!description.isEmpty()) {
                description.append('\n');
            }
            description.append(job.head);
            long waited = (System.currentTimeMillis() - job.created) / 60_000;
            if (waited >= 2 || job.tries > 0) {
                description.append("\n**Waited:** ").append(waited < 120 ? waited + " min" : waited / 60 + " h").append(" in the outbox")
                        .append(GAME.equals(job.game) ? "" : ", sent after a restart")
                        .append(job.tries > 0 ? " (try " + (job.tries + 1) + ", last: " + job.lastError + ")" : "");
            }
            if (!job.summary.isEmpty()) {
                description.append("\n\n").append(job.summary);
            }
            StringBuilder files = new StringBuilder();
            for (Part part : parts) {
                files.append(files.isEmpty() ? "" : ", ").append(part.name());
            }
            description.append("\n**Files:** ").append(files).append(notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");

            // The version first, for sorting by it in Discord and in the developer's index
            String title = "v" + job.version + " · " + switch (job.kind) {
                case 'P' -> "Problem from " + job.player + (report == null || report.picked().isEmpty() ? "" : ": " + String.join(", ", report.picked()));
                case 'S' -> "Stopped: " + cut(ended == null ? "?" : ended, 120) + " · " + job.player;
                case 'F' -> "Finished · " + job.player;
                case 'U' -> "Stopped by the player · " + job.player;
                case 'D' -> "Disconnected · " + job.player;
                case 'C' -> (ended != null && ended.startsWith(CRASHED) ? "Crashed: " + cut(ended.substring(CRASHED.length()).replaceFirst("^: ", ""), 100)
                        : ended != null && ended.contains(GAME_CLOSED) ? "Game closed during the run" : "Game stopped during the run") + " · " + job.player;
                default -> "Log from " + job.player;
            };
            int color = switch (job.kind) {
                case 'P' -> RED;
                case 'S' -> ORANGE;
                case 'F' -> GREEN;
                case 'U' -> BLUE;
                case 'D' -> PURPLE;
                case 'C' -> DARK_RED;
                default -> LIGHT_BLUE;
            };
            JsonObject embed = new JsonObject();
            embed.addProperty("title", cut(title, 250));
            embed.addProperty("description", cut(description.toString(), 4000));
            embed.addProperty("color", color);
            if (shot != null) {
                JsonObject image = new JsonObject();
                image.addProperty("url", "attachment://screenshot.png");
                embed.add("image", image);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("username", "Printer Addon Reports");
            // A small line above the report, for Discord's search (it doesn't search inside embeds)
            payload.addProperty("content", cut("-# v" + job.version + " · " + kindName(job.kind) + " · "
                    + (job.what.equals("-") ? "" : job.what + " · ") + job.player, 300));
            JsonObject mentions = new JsonObject();
            mentions.add("parse", new JsonArray());
            payload.add("allowed_mentions", mentions);
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            String boundary = "PrinterAddon" + Long.toHexString(System.nanoTime());
            String json = payload.toString();
            int size = json.length() * 3 + 4096;
            for (Part part : parts) {
                size += part.bytes().length + 512;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream(size);
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + json + "\r\n");
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i + "]\"; filename=\""
                        + part.name().replace("\"", "") + "\"\r\nContent-Type: " + part.type() + "\r\n\r\n");
                body.write(part.bytes());
                write(body, "\r\n");
            }
            write(body, "--" + boundary + "--\r\n");
            // wait=true: Discord answers with the message, whose id goes into the developer's index
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + (url.contains("?") ? "&" : "?") + "wait=true"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("User-Agent", "PrinterAddon/" + modVersion(PrinterAddon.MOD_ID))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                String id = "?";
                try {
                    id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
                    if (url.equals(developerChannel())) {
                        ReportIndex.add(CLIENT, url, id, job.kind, job.version);
                    }
                } catch (RuntimeException e) {
                    PrinterAddon.LOGGER.warn("[Printer Addon] The report went, but couldn't be listed: {}", e.toString());
                }
                return Result.sent(id);
            }
            if (status == 429) {
                return Result.failed("too many logs at once", true, retryAfter(response));
            }
            if (status >= 500) {
                return Result.failed("the channel answered " + status, true, 0);
            }
            PrinterAddon.LOGGER.warn("[Printer Addon] The log channel refused a report: {} {}", status, cut(response.body(), 500));
            return Result.failed("the channel answered " + status + (status == 404 || status == 401 ? " (the webhook doesn't exist any more)"
                    : status == 413 ? " (the files are too big)" : ""), false, 0);
        } catch (IOException e) {
            return Result.failed(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), true, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("interrupted", true, 0);
        }
    }

    /** A log, or its last MAX_READ bytes (from the start of a line) when it's bigger: never all of a huge one in memory. */
    private static byte[] readLog(Path log, List<String> notes) throws IOException {
        long size = Files.size(log);
        if (size <= MAX_READ) {
            return Files.readAllBytes(log);
        }
        byte[] bytes = new byte[MAX_READ];
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(log, StandardOpenOption.READ)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer, size - MAX_READ + buffer.position()) >= 0) {
                // until full
            }
        }
        notes.add(String.format(Locale.ROOT, "the log %.0f MB, its last %d MB read", size / 1048576.0, MAX_READ / 1048576));
        return tail(bytes, MAX_READ - 1);
    }

    /** Discord's wait after a 429, in ms (its retry_after is in seconds). */
    private static long retryAfter(HttpResponse<String> response) {
        try {
            return (long) (JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble() * 1000) + 500;
        } catch (RuntimeException e) {
            return 5_000;
        }
    }

    /** The last part of a log, at most max bytes, from the start of a line. */
    private static byte[] tail(byte[] bytes, int max) {
        int from = Math.max(0, bytes.length - max);
        if (from > 0) {
            while (from < bytes.length && bytes[from - 1] != '\n') {
                from++;
            }
        }
        byte[] tail = new byte[bytes.length - from];
        System.arraycopy(bytes, from, tail, 0, tail.length);
        return tail;
    }

    /** At most max bytes (debug.txt is never near it). */
    private static byte[] cut(byte[] bytes, int max) {
        if (bytes.length <= max) {
            return bytes;
        }
        byte[] cut = new byte[max];
        System.arraycopy(bytes, 0, cut, 0, max);
        return cut;
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 8 + 1024);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(bytes);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
