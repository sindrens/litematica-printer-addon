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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The developer's list of the reports in the log channel. A webhook can post, read and edit its own messages by id,
 * but can't list them: so each report's id (and kind) goes into one of a few index messages, which the developer's
 * tool reads (tools/reports.py). The root message, its id in printer_addon/report-index.txt, names the index messages
 * in its "slots:" line.
 * Two players writing the same index message at once can overwrite each other: each write is read back and tried again.
 */
final class ReportIndex {
    private static final Pattern ID = Pattern.compile("\\b\\d{17,20}\\b");
    /** Discord takes 2000 characters a message: a fuller index message is left for the next. */
    private static final int FULL = 1950;

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
     * player, L log). On the sending thread.
     */
    static void add(HttpClient client, String webhook, String messageId, char kind) {
        String root = rootId();
        if (root == null) {
            return;
        }
        String base = webhook.split("\\?")[0];
        try {
            // The root's "slots:" line names the index messages (its other lines are the developer's)
            List<String> slots = new ArrayList<>();
            for (String line : content(client, base, root).split("\n")) {
                if (line.startsWith("slots:")) {
                    Matcher m = ID.matcher(line);
                    while (m.find()) {
                        slots.add(m.group());
                    }
                }
            }
            Collections.shuffle(slots);
            for (String slot : slots) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    String text = content(client, base, slot);
                    if (text.contains(messageId)) {
                        return;
                    }
                    if (text.length() + messageId.length() + 3 > FULL) {
                        break;
                    }
                    edit(client, base, slot, text + "\n" + messageId + " " + kind);
                    Thread.sleep(400 + ThreadLocalRandom.current().nextInt(900));
                    if (content(client, base, slot).contains(messageId)) {
                        return;
                    }
                }
            }
            PrinterAddon.LOGGER.warn("[Printer Addon] The report index is full or busy: report {} isn't listed", messageId);
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Couldn't list report {}: {}", messageId, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String content(HttpClient client, String base, String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(URI.create(base + "/messages/" + id)).GET());
        JsonObject message = JsonParser.parseString(response.body()).getAsJsonObject();
        return message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
    }

    private static void edit(HttpClient client, String base, String id, String text) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new com.google.gson.JsonArray());
        body.add("allowed_mentions", mentions);
        send(client, HttpRequest.newBuilder(URI.create(base + "/messages/" + id))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    /** One request, waiting out Discord's rate limit once (at most 5 s). */
    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws IOException, InterruptedException {
        request.timeout(Duration.ofSeconds(20));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            double wait = 1;
            try {
                wait = JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
            } catch (RuntimeException ignored) {
                // a second then
            }
            Thread.sleep((long) (Math.min(5, wait) * 1000) + 100);
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Discord answered " + response.statusCode());
        }
        return response;
    }
}
