package no.sindrens.printeraddon.build;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Sends a build log to the add-on's developer (a Discord channel, through its webhook), to find bugs and make it
 * faster: when the player sends a report (J+B, "Report / share log": what went wrong, what they were building, see
 * ShareLogGui), or after every run with "Share logs automatically" on. Never otherwise. With the log go a short summary
 * (versions, server, player, what ran, how far it got and how fast, why it ended, where the time went) and the full
 * debug (see DebugInfo): debug.txt, the game's own log without chat, a crash report from the last day, and the
 * screenshot the player adds.
 */
public final class LogShare {
    /**
     * The developer's log channel, a Discord webhook URL ("Log channel" in the settings goes first). It's packed into
     * the jar from src/main/resources/printer_addon/log-channel.txt (reversed, Base64), which never goes into the
     * repository: a webhook URL in a public repository gets found and spammed. Without the file: nowhere to send logs.
     */
    @Nullable
    private static String developerChannel;
    /**
     * Discord takes 10 MB a message: everything sent stays under this, the run's log gzipped (or only its last part)
     * when it doesn't fit next to the rest, and the screenshot left out before the log is cut.
     */
    private static final int MAX_BYTES = 9 * 1024 * 1024 + 512 * 1024;
    private static final int DEBUG_BYTES = 512 * 1024;
    /** The most the report screen takes: the player's own words, and what they were building. */
    public static final int MAX_PROBLEM = 2000;
    public static final int MAX_BUILDING = 200;
    private static volatile boolean sending;
    private static boolean tipShown;

    private LogShare() {
    }

    /** What the player says with the log (see ShareLogGui): their words, what they clicked, what they were building. */
    public record Report(String problem, List<String> picked, String building, @Nullable Path screenshot) {
        boolean saysSomething() {
            return !problem.isBlank() || !picked.isEmpty();
        }
    }

    /** A file of the message. */
    private record Part(String name, String type, byte[] bytes) {
    }

    /** Where logs go, or "" when nowhere. */
    static String channel() {
        String own = Configs.Generic.LOG_CHANNEL.getStringValue().trim();
        return own.isEmpty() ? developerChannel() : own;
    }

    private static String developerChannel() {
        if (developerChannel == null) {
            developerChannel = "";
            try (java.io.InputStream in = LogShare.class.getResourceAsStream("/printer_addon/log-channel.txt")) {
                if (in != null) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) {
                        developerChannel = new StringBuilder(new String(java.util.Base64.getDecoder().decode(text), StandardCharsets.UTF_8))
                                .reverse().toString();
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't read the log channel", e);
            }
        }
        return developerChannel;
    }

    public static boolean isSending() {
        return sending;
    }

    /** After each run: sent with "Share logs automatically", else a tip once a session about reporting it. */
    public static void afterRun(Minecraft mc) {
        try {
            if (Configs.Generic.SHARE_LOGS.getBooleanValue()) {
                share(mc, true, null);
            } else if (!tipShown && !channel().isEmpty()) {
                tipShown = true;
                Chat.info("Something off, or too slow? J+B -> 'Report / share log': say what went wrong, and this run's log goes"
                        + " to the add-on's developer (or turn on 'Share logs automatically').");
            }
        } catch (RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't share the log", e);
        }
    }

    /**
     * Sends the run's log (the one going on now, else the last one, else the newest there is) with the player's report
     * (null: none, as after a run) and the full debug. A report goes without a log too (nothing ran yet).
     */
    public static void share(Minecraft mc, boolean automatic, @Nullable Report report) {
        String url = channel();
        if (url.isEmpty()) {
            Chat.warn("No log channel set (Log channel in the settings): nowhere to send the log to.");
            return;
        }
        if (!url.startsWith("https://")) {
            Chat.warn("The log channel isn't a web address (https://...): set a Discord webhook URL as Log channel.");
            return;
        }
        if (sending) {
            if (!automatic) {
                Chat.info("A log is being sent already.");
            }
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
        // The game's state now, on the game's thread; reading the files and the upload on their own thread
        String head = header(mc, automatic);
        String debug;
        try {
            debug = DebugInfo.collect(mc, report, head, summary, file);
        } catch (RuntimeException | LinkageError e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't gather the debug", e);
            debug = "The debug couldn't be gathered: " + e;
        }
        DebugInfo.Recent crash = DebugInfo.recentCrashReport(mc);
        sending = true;
        Path log = file;
        String text = summary;
        String details = debug;
        Thread thread = new Thread(() -> send(mc, url, log, head, text, details, crash == null ? null : crash.file(), report, automatic),
                "Printer Addon log share");
        thread.setDaemon(true);
        thread.start();
        if (!automatic) {
            Chat.info(report != null && report.saysSomething() ? "Sending your report to the add-on's developer..."
                    : "Sending " + (log == null ? "the debug" : log.getFileName()) + " to the add-on's developer...");
        }
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
    private static String header(Minecraft mc, boolean automatic) {
        String player = mc.getUser() == null ? "?" : mc.getUser().getName();
        String state = AutoBuild.INSTANCE.isRunning() ? "auto build running (" + AutoBuild.INSTANCE.layers.status() + ")"
                : AreaBreaker.INSTANCE.isRunning() ? "Auto break running (" + AreaBreaker.INSTANCE.status() + ")"
                : "nothing running";
        return String.join("\n",
                "**Versions:** Printer Addon " + modVersion(PrinterAddon.MOD_ID) + " | Minecraft " + modVersion("minecraft")
                        + " | Litematica " + modVersion("litematica") + " | Printer " + modVersion("litematica_printer")
                        + " | MaLiLib " + modVersion("malilib") + " | Fabric " + modVersion("fabricloader"),
                "**Player:** " + player + " on " + no.sindrens.printeraddon.util.ServerName.current(mc),
                "**Now:** " + state,
                "**Settings:** printer interval " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.getIntegerValue()
                        + ", range " + me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue()
                        + ", speed preset " + Configs.Generic.SPEED_PRESET.getOptionListValue().getStringValue()
                        + ", ping " + ServerLag.pingTicks(mc) * 50 + " ms",
                "**Sent:** " + (automatic ? "automatically (Share logs automatically is on)" : "by the player (Report / share log)"));
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("-");
    }

    private static void send(Minecraft mc, String url, @Nullable Path log, String head, String summary, String debug,
                             @Nullable Path crash, @Nullable Report report, boolean automatic) {
        String result;
        try {
            List<String> notes = new ArrayList<>();
            byte[] details = cut(debug.getBytes(StandardCharsets.UTF_8), DEBUG_BYTES);
            byte[] game = DebugInfo.gameLog(mc);
            byte[] crashText = crash == null ? new byte[0] : DebugInfo.crashReport(crash);
            long used = details.length + game.length + crashText.length;
            byte[] shot = null;
            if (report != null && report.screenshot() != null) {
                try {
                    shot = Files.readAllBytes(report.screenshot());
                } catch (IOException e) {
                    notes.add("the screenshot couldn't be read");
                }
            }
            List<Part> parts = new ArrayList<>();
            if (log != null) {
                byte[] bytes = Files.readAllBytes(log);
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

            String player = mc.getUser() == null ? "?" : mc.getUser().getName();
            boolean problem = report != null && report.saysSomething();
            StringBuilder description = new StringBuilder();
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
                if (!description.isEmpty()) {
                    description.append('\n');
                }
            }
            description.append(head);
            if (!summary.isEmpty()) {
                description.append("\n\n").append(summary);
            }
            StringBuilder files = new StringBuilder();
            for (Part part : parts) {
                files.append(files.isEmpty() ? "" : ", ").append(part.name());
            }
            description.append("\n**Files:** ").append(files).append(notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
            String title = problem ? "Problem from " + player + (report.picked().isEmpty() ? "" : ": " + String.join(", ", report.picked()))
                    : "Log from " + player;
            String payload = "{\"username\":\"Printer Addon logs\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
                    + quote(cut(title, 250)) + ",\"description\":" + quote(cut(description.toString(), 4000))
                    + ",\"color\":" + (problem ? 15158332 : 3447003)
                    + (shot != null ? ",\"image\":{\"url\":\"attachment://screenshot.png\"}" : "") + "}]}";

            String boundary = "PrinterAddon" + Long.toHexString(System.nanoTime());
            int size = payload.length() * 3 + 4096;
            for (Part part : parts) {
                size += part.bytes().length + 512;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream(size);
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + payload + "\r\n");
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i + "]\"; filename=\""
                        + part.name().replace("\"", "") + "\"\r\nContent-Type: " + part.type() + "\r\n\r\n");
                body.write(part.bytes());
                write(body, "\r\n");
            }
            write(body, "--" + boundary + "--\r\n");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            result = status / 100 == 2 ? null : "the channel answered " + status + (status == 429 ? " (too many logs at once, try again in a minute)"
                    : status == 404 || status == 401 ? " (the webhook doesn't exist any more)" : status == 413 ? " (the files are too big)" : "");
        } catch (IOException | RuntimeException e) {
            result = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "interrupted";
        }
        String failed = result;
        String what = report != null && report.saysSomething() ? "Your report" : log == null ? "The debug" : "Log " + log.getFileName();
        sending = false;
        mc.execute(() -> {
            if (failed == null) {
                Chat.info(what + " went to the add-on's developer" + (automatic ? " (Share logs automatically is on)" : "") + ". Thanks!");
                BuildLog.lineOrKeep("SHARE", "sent " + (log == null ? "a report without a log" : log.getFileName()));
                if (report != null) {
                    no.sindrens.printeraddon.gui.ShareLogGui.clearDraft();
                }
            } else {
                Chat.warn("Couldn't send it: " + failed + (report != null ? ". What you wrote is kept: J+B -> 'Report / share log' to try again." : ""));
                BuildLog.lineOrKeep("SHARE", "couldn't send " + (log == null ? "a report" : log.getFileName()) + ": " + failed);
            }
        });
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

    /** A JSON string. */
    private static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
