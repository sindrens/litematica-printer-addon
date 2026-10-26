package no.sindrens.printeraddon.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The developer's list of the reports in the log channel. A webhook can post, read and edit its own messages by id,
 * but can't list them: so each report goes into one of the index messages as "<id> <kind> <version>", which the
 * developer's tool reads (tools/reports.py) to sort the reports by kind and by the add-on version that wrote them. The
 * root message, its id in printer_addon/report-index.txt, names the index messages in its "slots:" line; the newest is
 * written first, and when it's full a new one is posted and added there. Two players writing the same message at once
 * can overwrite each other: each write is read back and tried again from a fresh read.
 */
final class ReportIndex {
    private static final Pattern ID = Pattern.compile("\\b\\d{17,20}\\b");
    /** Discord takes 2000 characters a message: a fuller index message is left for the next. */
    private static final int FULL = 1950;
    private static final int MESSAGE_MAX = 2000;

    private ReportIndex() {
    }

    /** The root message's id, or null when the jar has none (built without the developer's channel). */
    @Nullable
    private static String rootId() {
        try (InputStream in = ReportIndex.class.getResourceAsStream("/printer_addon/report-index.txt")) {
            if (in == null) {
                return null;
            }
            Matcher m = ID.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return m.find() ? m.group() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Lists the report {@code messageId} ({@code kind}: P problem, S stopped by itself, F finished, U stopped by the
     * player, L log, D disconnected, C the game closed or crashed; {@code version}: the add-on's that wrote the log).
     * On the sending thread.
     */
    static void add(HttpClient client, String webhook, String messageId, char kind, String version) {
        String root = rootId();
        if (root == null) {
            return;
        }
        String base = webhook.split("\\?")[0];
        String entry = messageId + " " + kind + " " + version.replaceAll("\\s+", "_");
        try {
            // The root's "slots:" line names the index messages (its other lines are the developer's). The newest has
            // room most likely: tried first, and when it's full a new one is made, unless the root has no room for it;
            // then the older ones are tried
            List<String> slots = slots(content(client, base, root));
            if (slots.isEmpty()) {
                PrinterAddon.LOGGER.warn("[Printer Addon] The report index has no slots line: report {} isn't listed", messageId);
                return;
            }
            String newest = slots.get(slots.size() - 1);
            Boolean listed = tryIn(client, base, newest, messageId, entry);
            if (listed == null || listed) {
                return;
            }
            if (newSlot(client, base, root, slots.size() + 1, entry)) {
                return;
            }
            for (int i = slots.size() - 2; i >= 0; i--) {
                listed = tryIn(client, base, slots.get(i), messageId, entry);
                if (listed == null || listed) {
                    return;
                }
            }
            PrinterAddon.LOGGER.warn("[Printer Addon] The report index is full or busy: report {} isn't listed", messageId);
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't list report {}: {}", messageId, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The entry into one index message, read back: true when it's in, false when it's full or kept changing, null when
     * the report was listed already. Every try reads the message again (an edit is never sent again as it was: the
     * text it was made from could be old by then).
     */
    @Nullable
    private static Boolean tryIn(HttpClient client, String base, String slot, String messageId, String entry)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            String text = content(client, base, slot);
            if (text.contains(messageId)) {
                return attempt == 0 ? null : Boolean.TRUE;
            }
            if (text.length() + entry.length() + 1 > FULL) {
                return false;
            }
            edit(client, base, slot, text + "\n" + entry);
            pause();
        }
        return content(client, base, slot).contains(messageId);
    }

    /** The ids in the root's "slots:" line. */
    private static List<String> slots(String root) {
        List<String> slots = new ArrayList<>();
        for (String line : root.split("\n")) {
            if (line.startsWith("slots:")) {
                Matcher m = ID.matcher(line);
                while (m.find()) {
                    slots.add(m.group());
                }
            }
        }
        return slots;
    }

    /** The root's text with one more index message in its slots line, or null without the line or room for it. */
    @Nullable
    private static String withSlot(String root, String slot) {
        String[] lines = root.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("slots:")) {
                lines[i] = lines[i] + " " + slot;
                String updated = String.join("\n", lines);
                return updated.length() <= MESSAGE_MAX ? updated : null;
            }
        }
        return null;
    }

    /**
     * Posts index message number {@code number} with {@code entry} in it and adds it to the root's slots line; false
     * (nothing posted) when the root has no room for it.
     */
    private static boolean newSlot(HttpClient client, String base, String root, int number, String entry) throws IOException, InterruptedException {
        if (withSlot(content(client, base, root), "0".repeat(20)) == null) {
            return false;
        }
        HttpResponse<String> posted = send(client, HttpRequest.newBuilder(URI.create(base + "?wait=true"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body("📇 Report index · part " + number + "\n" + entry))));
        String slot = JsonParser.parseString(posted.body()).getAsJsonObject().get("id").getAsString();
        for (int attempt = 0; attempt < 4; attempt++) {
            String text = content(client, base, root);
            if (slots(text).contains(slot)) {
                return true;
            }
            String updated = withSlot(text, slot);
            if (updated == null) {
                break;
            }
            edit(client, base, root, updated);
            pause();
        }
        if (slots(content(client, base, root)).contains(slot)) {
            return true;
        }
        // Not named in the root after all: taken away again, not left lying there
        try {
            send(client, HttpRequest.newBuilder(URI.create(base + "/messages/" + slot)).DELETE());
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't take back an index message: {}", e.toString());
        }
        return false;
    }

    /** A random wait after a write: two players writing at once don't keep hitting each other. */
    private static void pause() throws InterruptedException {
        Thread.sleep(400 + ThreadLocalRandom.current().nextInt(900));
    }

    private static String content(HttpClient client, String base, String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(URI.create(base + "/messages/" + id)).GET());
        JsonObject message = JsonParser.parseString(response.body()).getAsJsonObject();
        return message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
    }

    /** An edit, sent once: after a 429 the caller reads the message again and makes it anew. */
    private static void edit(HttpClient client, String base, String id, String text) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/messages/" + id))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body(text))).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            Thread.sleep(retryAfter(response));
        } else if (response.statusCode() / 100 != 2) {
            throw new IOException("Discord answered " + response.statusCode());
        }
    }

    /** A message's JSON: its text, with no one pinged. */
    private static String body(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new com.google.gson.JsonArray());
        body.add("allowed_mentions", mentions);
        return body.toString();
    }

    /** A read, a new message or a delete, waiting out Discord's rate limit once (at most 5 s). */
    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws IOException, InterruptedException {
        request.timeout(Duration.ofSeconds(20));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            Thread.sleep(retryAfter(response));
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Discord answered " + response.statusCode());
        }
        return response;
    }

    /** How long Discord says to wait after a 429, at most 5 s. */
    private static long retryAfter(HttpResponse<String> response) {
        double wait = 1;
        try {
            wait = JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
        } catch (RuntimeException ignored) {
            // a second then
        }
        return (long) (Math.min(5, wait) * 1000) + 100;
    }
}
