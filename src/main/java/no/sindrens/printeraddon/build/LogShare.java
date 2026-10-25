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
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Sends a build log to the add-on's developer (a Discord channel, through its webhook), to find bugs and make it
 * faster: when the player clicks "Share last log" (J+B), or after every run with "Share logs automatically" on. Never
 * otherwise. With the file goes a short summary: the add-on, Minecraft, Litematica, printer and MaLiLib versions, the
 * server, the player, what ran, how far it got and how fast, why it ended and where the time went.
 */
public final class LogShare {
    /**
     * The developer's log channel, a Discord webhook URL ("Log channel" in the settings goes first). It's packed into
     * the jar from src/main/resources/printer_addon/log-channel.txt (reversed, Base64), which never goes into the
     * repository: a webhook URL in a public repository gets found and spammed. Without the file: nowhere to send logs.
     */
    @Nullable
    private static String developerChannel;
    /** Discord takes files up to 10 MB: a bigger log goes gzipped, and only its last part if that's still too big. */
    private static final int MAX_BYTES = 9 * 1024 * 1024;
    private static volatile boolean sending;
    private static boolean tipShown;

    private LogShare() {
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

    /** After each run: sent with "Share logs automatically", else a tip once a session about sharing it. */
    public static void afterRun(Minecraft mc) {
        try {
            if (Configs.Generic.SHARE_LOGS.getBooleanValue()) {
                share(mc, true);
            } else if (!tipShown && !channel().isEmpty()) {
                tipShown = true;
                Chat.info("Something off, or too slow? J+B -> 'Share last log' sends this run's log to the add-on's developer"
                        + " (or turn on 'Share logs automatically').");
            }
        } catch (RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't share the log", e);
        }
    }

    /** Sends the last run's log (or else the newest build log there is) with its summary. */
    public static void share(Minecraft mc, boolean automatic) {
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
        Path file = RunLog.lastFile();
        String summary = RunLog.lastSummary();
        if (file == null || !Files.isRegularFile(file)) {
            file = newestLog();
            summary = "";
        }
        if (file == null) {
            if (!automatic) {
                Chat.warn("No build log yet: it's written while auto build or Auto break runs.");
            }
            return;
        }
        // The summary from the game's state now (on the game's thread), the upload on its own thread
        String head = header(mc, automatic);
        sending = true;
        Path log = file;
        String text = summary;
        Thread thread = new Thread(() -> send(mc, url, log, head, text, automatic), "Printer Addon log share");
        thread.setDaemon(true);
        thread.start();
        if (!automatic) {
            Chat.info("Sending " + log.getFileName() + " to the add-on's developer...");
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
                "**Sent:** " + (automatic ? "automatically (Share logs automatically is on)" : "by the player (Share last log)"));
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("-");
    }

    private static void send(Minecraft mc, String url, Path log, String head, String summary, boolean automatic) {
        String result;
        try {
            byte[] bytes = Files.readAllBytes(log);
            String name = log.getFileName().toString();
            String type = "application/x-ndjson";
            String note = "";
            if (bytes.length > MAX_BYTES) {
                byte[] zipped = gzip(bytes);
                if (zipped.length <= MAX_BYTES) {
                    note = String.format(Locale.ROOT, " (%.1f MB, gzipped)", bytes.length / 1048576.0);
                    bytes = zipped;
                    name = name + ".gz";
                    type = "application/gzip";
                } else {
                    // Only the last part, from the start of a line
                    int from = bytes.length - MAX_BYTES;
                    while (from < bytes.length && bytes[from] != '\n') {
                        from++;
                    }
                    byte[] tail = new byte[bytes.length - from - 1 < 0 ? 0 : bytes.length - from - 1];
                    System.arraycopy(bytes, Math.min(bytes.length, from + 1), tail, 0, tail.length);
                    note = String.format(Locale.ROOT, " (the last %.1f of %.1f MB)", tail.length / 1048576.0, bytes.length / 1048576.0);
                    bytes = tail;
                    name = name.replace(".jsonl", "-end.jsonl");
                }
            }
            String description = head + (summary.isEmpty() ? "" : "\n\n" + summary) + "\n**File:** " + name + note;
            String payload = "{\"username\":\"Printer Addon logs\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
                    + quote("Log from " + (mc.getUser() == null ? "?" : mc.getUser().getName())) + ",\"description\":"
                    + quote(cut(description, 4000)) + ",\"color\":3447003}]}";
            String boundary = "PrinterAddon" + Long.toHexString(System.nanoTime());
            ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length + 8192);
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + payload + "\r\n");
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
                    + name.replace("\"", "") + "\"\r\nContent-Type: " + type + "\r\n\r\n");
            body.write(bytes);
            write(body, "\r\n--" + boundary + "--\r\n");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            result = status / 100 == 2 ? null : "the channel answered " + status + (status == 429 ? " (too many logs at once, try again in a minute)"
                    : status == 404 || status == 401 ? " (the webhook doesn't exist any more)" : status == 413 ? " (the file is too big)" : "");
        } catch (IOException | RuntimeException e) {
            result = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "interrupted";
        }
        String failed = result;
        sending = false;
        mc.execute(() -> {
            if (failed == null) {
                Chat.info("Log " + log.getFileName() + " sent to the add-on's developer" + (automatic ? " (Share logs automatically is on)" : "") + ". Thanks!");
                BuildLog.lineOrKeep("SHARE", "sent " + log.getFileName());
            } else {
                Chat.warn("Couldn't send the log: " + failed);
                BuildLog.lineOrKeep("SHARE", "couldn't send " + log.getFileName() + ": " + failed);
            }
        });
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
