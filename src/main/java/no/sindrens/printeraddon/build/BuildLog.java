package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The full log of one auto build, for finding out what went wrong: every step and message, every flight (from,
 * to, how long), and every 10 seconds where the player is and how fast blocks go in. One file per build in
 * .minecraft/logs/printer_addon/ (build-<date>.jsonl, see RunLog; the same as plain text too with "Text build log"),
 * the last 20 are kept. Both were written before: 19.5 and 24 MB an hour of the same lines.
 */
public final class BuildLog {
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int KEEP_FILES = 20;

    /** The plain text log, with "Text build log" only. */
    @Nullable
    private static BufferedWriter out;
    /** The build's log name (build-<date>.log; the .jsonl next to it is what's written, see RunLog). */
    @Nullable
    private static Path file;
    private static boolean opened;
    private static long lastFlush;

    private BuildLog() {
    }

    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve(PrinterAddon.MOD_ID);
    }

    @Nullable
    public static Path file() {
        return file;
    }

    public static boolean isOpen() {
        return opened;
    }

    public static void open() {
        close();
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            removeOld(dir);
            file = dir.resolve("build-" + LocalDateTime.now().format(FILE_NAME) + ".log");
            opened = true;
            if (no.sindrens.printeraddon.config.Configs.Generic.TEXT_LOG.getBooleanValue()) {
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                // What happened since the last build (joining again, getting back to the build) comes first
                for (String line : pending) {
                    out.write(line);
                    out.newLine();
                }
            }
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't open the build log", e);
            out = null;
        }
    }

    /**
     * The lines from between builds, for the top of the .jsonl once it's open (see RunLog): time, tick, kind, text.
     * Taken once.
     */
    static List<String[]> takePending() {
        List<String[]> lines = new java.util.ArrayList<>();
        for (String line : pending) {
            String[] parts = line.split(" ", 4);
            if (parts.length == 4 && parts[1].startsWith("t=")) {
                lines.add(new String[]{parts[0], parts[1].substring(2), parts[2], parts[3]});
            }
        }
        pending.clear();
        return lines;
    }

    public static void close() {
        if (!opened) {
            return;
        }
        opened = false;
        try {
            flushRepeats(out);
            repeatKey = null;
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** Lines from between builds, for the top of the next build log. */
    private static final java.util.Deque<String> pending = new java.util.ArrayDeque<>();

    /**
     * A line from between builds (joining again, getting back to the build): into the build log if one is open,
     * else kept for the next one (the last 100) and written to the game log at once.
     */
    public static void lineOrKeep(String kind, String text) {
        if (opened) {
            line(kind, text);
            return;
        }
        PrinterAddon.LOGGER.info("[Printer Addon] {} {}", kind, text);
        if (pending.size() >= 100) {
            pending.pollFirst();
        }
        pending.addLast(LocalDateTime.now().format(TIME) + " t=" + AutoBuild.clientTicks + " " + kind + " " + text);
    }

    /** The last line written, and how often it came again right after (written once, with "×N", see line). */
    @Nullable
    private static String repeatKey;
    private static int repeats;
    private static String repeatLastTime = "";
    private static long repeatLastTick;

    /**
     * One line: time, game tick, kind, text. The same line again and again in a row (kind and text alike) is written
     * once, followed by one line "×N until <time>" when something else comes (120 lines the same in a row before).
     */
    public static void line(String kind, String text) {
        if (!opened || debugOnly(kind, text)) {
            return;
        }
        BufferedWriter writer = out;
        try {
            String time = LocalDateTime.now().format(TIME);
            String key = kind + ' ' + text;
            if (key.equals(repeatKey)) {
                repeats++;
                repeatLastTime = time;
                repeatLastTick = AutoBuild.clientTicks;
                return;
            }
            flushRepeats(writer);
            repeatKey = key;
            if (writer != null) {
                writer.write(time);
                writer.write(" t=");
                writer.write(Long.toString(AutoBuild.clientTicks));
                writer.write(' ');
                writer.write(kind);
                writer.write(' ');
                writer.write(text);
                writer.newLine();
            }
            if (RunLog.jsonOpen()) {
                RunLog.mirror(time, AutoBuild.clientTicks, kind, text);
            } else if (pending.size() < 500) {
                // Before the .jsonl is open (the build's first lines: START, the settings): kept for its top
                pending.addLast(time + " t=" + AutoBuild.clientTicks + " " + kind + " " + text);
            }
            long now = System.currentTimeMillis();
            if (now - lastFlush > 1000) {
                lastFlush = now;
                if (writer != null) {
                    writer.flush();
                }
                RunLog.flushJson();
            }
        } catch (IOException e) {
            // The text log failed: the .jsonl goes on
            out = null;
        }
    }

    /**
     * Kinds written only with "Debug log" on: a line for every block tried and placed, every item switch, and the
     * status lines BEAT sums up every 10 s. An hour of building was 19.5 MB, 70 % of it TRY and PLACED lines; what
     * goes wrong (NOCHANGE, WRONG, GIVEUP, a hotbar slot the server changed) is always written.
     */
    private static final java.util.Set<String> DEBUG_ONLY = java.util.Set.of("TRY", "PLACED", "TURNS", "SNAP", "SCAN", "STEP", "DETAIL");

    private static boolean debugOnly(String kind, String text) {
        return (DEBUG_ONLY.contains(kind) || kind.equals("HOTBAR") && text.startsWith("switch "))
                && !no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.getBooleanValue();
    }

    private static void flushRepeats(@Nullable BufferedWriter writer) throws IOException {
        if (repeats > 0 && repeatKey != null) {
            String text = "×" + repeats + " more of the line above (until " + repeatLastTime + ")";
            if (writer != null) {
                writer.write(repeatLastTime + " t=" + repeatLastTick + " REPEAT " + text);
                writer.newLine();
            }
            RunLog.mirror(repeatLastTime, repeatLastTick, "REPEAT", text);
        }
        repeats = 0;
    }

    /** Every add-on setting, for the start of the log. */
    public static String settings() {
        StringBuilder sb = new StringBuilder();
        java.util.List<java.util.List<fi.dy.masa.malilib.config.IConfigBase>> all = java.util.List.of(
                no.sindrens.printeraddon.config.Configs.Generic.OPTIONS, no.sindrens.printeraddon.config.Configs.Breaking.OPTIONS,
                no.sindrens.printeraddon.config.Configs.Layers.OPTIONS, no.sindrens.printeraddon.config.Configs.Movement.OPTIONS,
                no.sindrens.printeraddon.config.Configs.Safety.OPTIONS, no.sindrens.printeraddon.config.Configs.Shop.OPTIONS,
                no.sindrens.printeraddon.config.Configs.Speed.OPTIONS);
        for (java.util.List<fi.dy.masa.malilib.config.IConfigBase> list : all) {
            for (fi.dy.masa.malilib.config.IConfigBase option : list) {
                if (option instanceof fi.dy.masa.malilib.config.IConfigValue value) {
                    sb.append(option.getName()).append('=').append(value.getStringValue()).append(", ");
                }
            }
        }
        return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "";
    }

    /** The whole inventory: "slot:count item", for the log. */
    public static String inventory(net.minecraft.client.player.LocalPlayer player) {
        StringBuilder sb = new StringBuilder("selected slot " + (player.getInventory().getSelectedSlot() + 1) + " | ");
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                sb.append(slot + 1).append(':').append(stack.getCount()).append(' ')
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append(", ");
            }
        }
        return sb.substring(0, sb.length() - 2);
    }

    private static void removeOld(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> logs = files.filter(p -> p.getFileName().toString().startsWith("build-"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (int i = 0; i < logs.size() - (KEEP_FILES - 1); i++) {
                Files.deleteIfExists(logs.get(i));
            }
        }
    }
}
