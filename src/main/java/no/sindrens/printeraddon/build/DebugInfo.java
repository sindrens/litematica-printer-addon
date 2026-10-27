package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.IConfigStringList;
import fi.dy.masa.malilib.config.IStringRepresentable;
import fi.dy.masa.malilib.util.FileUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.Hotkeys;
import no.sindrens.printeraddon.util.ServerName;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The full debug that goes with a shared log (see LogShare): what the run's log doesn't say. debug.txt has what the
 * player wrote and clicked, where they are and what's running, the PC, every mod's version, every setting of the
 * add-on, Litematica Printer and Litematica, the placements, the recent runs, the learned speeds and the reports sent
 * before. Next to it go the game's own log (latest.log, or that of the game the run was in: errors from any mod) and a
 * crash report from the last day. Nothing private goes: no chat
 * lines, no log channel, no chest or vault spots, and the user folder (it has the player's name in it) is written ~.
 */
public final class DebugInfo {
    /** A file made a short while ago (a screenshot, a crash report). */
    public record Recent(Path file, long minutesAgo) {
        public String ago() {
            return minutesAgo < 1 ? "just now" : minutesAgo < 120 ? minutesAgo + " min ago" : minutesAgo / 60 + " h ago";
        }
    }

    /** A screenshot from the last 15 minutes is offered with a report: most likely it shows the problem. */
    private static final long SCREENSHOT_MINUTES = 15;
    /** A crash report from the last day goes with every report. */
    private static final long CRASH_MINUTES = 24 * 60;
    /** latest.log: its start (the mods loading, their warnings) and its end, at most this much. */
    static final int GAME_LOG_BYTES = 1536 * 1024;
    private static final int GAME_LOG_HEAD = 256 * 1024;
    /** A latest.log bigger than this: only its start and its last part are read. */
    private static final int GAME_LOG_READ = 8 * 1024 * 1024;
    private static final int CRASH_BYTES = 512 * 1024;

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
    private static final Pattern TOKEN = Pattern.compile("(?i)(--accessToken|accessToken[=:]|--session|session[_-]?id[=:])\\s*\\S+");
    private static final Pattern WEBHOOK = Pattern.compile("https://(?:[\\w-]+\\.)?discord(?:app)?\\.com/api/webhooks/\\S+");

    private DebugInfo() {
    }

    /** One part of debug.txt: an error in it is written there instead, the rest goes on. */
    private interface Section {
        String text() throws Exception;
    }

    /** The newest screenshot (F2) from the last 15 minutes, or null. */
    @Nullable
    public static Recent recentScreenshot(Minecraft mc) {
        return newest(mc.gameDirectory.toPath().resolve("screenshots"), ".png", SCREENSHOT_MINUTES);
    }

    /** The newest crash report from the last day, or null. */
    @Nullable
    public static Recent recentCrashReport(Minecraft mc) {
        Recent recent = newest(mc.gameDirectory.toPath().resolve("crash-reports"), ".txt", CRASH_MINUTES);
        // "Client shutdown from post-main" is the game taking long to close, not a crash: the same one went with 16
        // reports in a row
        return recent == null || closingNotCrash(recent.file()) ? null : recent;
    }

    private static boolean closingNotCrash(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            String head = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
            return head.contains("Client shutdown from post-main") || head.contains("Watchdog (Client shutdown");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * What the player was building, for the report screen to start from: "Pumpkin_farm, 32 x 280 x 32 blocks (Y -56 to
     * 223)", or the last run when it was Auto break or nothing is placed now.
     */
    public static String describeBuild() {
        String run = RunLog.describeRun();
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        if (run.startsWith("Auto break") || areas.isEmpty()) {
            return run.equals("-") ? "" : run;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (SchematicHelper.Area area : areas) {
            minX = Math.min(minX, area.minX());
            minY = Math.min(minY, area.minY());
            minZ = Math.min(minZ, area.minZ());
            maxX = Math.max(maxX, area.maxX());
            maxY = Math.max(maxY, area.maxY());
            maxZ = Math.max(maxZ, area.maxZ());
        }
        return String.format(Locale.ROOT, "%s, %d x %d x %d blocks (Y %d to %d)", AutoBuild.placementNames(),
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, minY, maxY);
    }

    /** debug.txt, from the game's state now: on the game's thread. */
    static String collect(Minecraft mc, @Nullable LogShare.Report report, String head, String summary, @Nullable Path log) {
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("Printer Addon debug, ").append(LocalDateTime.now().withNano(0)).append('\n');
        if (report != null) {
            title(out, "What the player says");
            out.append("Problem: ").append(report.picked().isEmpty() ? "-" : String.join(", ", report.picked())).append('\n')
                    .append("Building: ").append(report.building().isBlank() ? "-" : report.building().trim()).append('\n')
                    .append("In their words:\n").append(report.problem().isBlank() ? "-" : report.problem().trim()).append('\n');
        }
        title(out, "In short");
        out.append(head.replace("**", "")).append('\n');
        if (!summary.isEmpty()) {
            out.append(summary.replace("**", "")).append('\n');
        }
        out.append("Run log: ").append(log == null ? "none yet" : log.getFileName()).append('\n');
        section(out, "Now", () -> now(mc));
        section(out, "PC and game", () -> system(mc));
        section(out, "Mods", DebugInfo::mods);
        section(out, "Printer Addon settings (* = changed)", () -> {
            StringBuilder s = new StringBuilder();
            settings(s, "Generic", Configs.Generic.OPTIONS);
            settings(s, "Shop", Configs.Shop.OPTIONS);
            settings(s, "Movement", Configs.Movement.OPTIONS);
            settings(s, "Safety", Configs.Safety.OPTIONS);
            settings(s, "Layers", Configs.Layers.OPTIONS);
            settings(s, "Breaking", Configs.Breaking.OPTIONS);
            settings(s, "Speed", Configs.Speed.OPTIONS);
            settings(s, "Hotkeys", Hotkeys.HOTKEY_LIST);
            return s.toString();
        });
        section(out, "Litematica Printer settings (* = changed)", () -> {
            StringBuilder s = new StringBuilder();
            settings(s, "Printer", me.aleksilassila.litematica.printer.config.Configs.getConfigList());
            return s.toString();
        });
        section(out, "Litematica settings (* = changed)", () -> {
            StringBuilder s = new StringBuilder();
            settings(s, "Generic", fi.dy.masa.litematica.config.Configs.Generic.OPTIONS);
            return s.toString();
        });
        section(out, "Schematic placements", DebugInfo::placements);
        Path dir = FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID);
        section(out, "Recent runs (runs.txt)", () -> lines(dir.resolve("runs.txt"), 40, true, 0));
        section(out, "Build report (report.txt, from the last 2 hours)", () -> lines(dir.resolve("report.txt"), 150, false, 120));
        section(out, "Learned speeds (speed.txt)", () -> lines(dir.resolve("speed.txt"), 60, false, 0));
        section(out, "Learned speeds per block (speed-blocks.txt)", () -> lines(dir.resolve("speed-blocks.txt"), 80, false, 0));
        section(out, "Reports to the developer (sent.txt, the outbox)", () -> "Outbox: " + LogShare.outboxStatus() + "\n"
                + lines(BuildLog.folder().resolve("sent.txt"), 15, true, 0));
        return mask(out.toString());
    }

    private static void title(StringBuilder out, String title) {
        out.append("\n== ").append(title).append('\n');
    }

    private static void section(StringBuilder out, String title, Section section) {
        title(out, title);
        try {
            out.append(section.text());
        } catch (Exception | LinkageError e) {
            out.append("(couldn't be read: ").append(e).append(')');
        }
        out.append('\n');
    }

    private static String now(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return "Not in a world.";
        }
        ItemStack held = player.getMainHandItem();
        return String.join("\n",
                String.format(Locale.ROOT, "Position: %.2f %.2f %.2f in %s, looking %.0f / %.0f", player.getX(), player.getY(), player.getZ(),
                        mc.level.dimension().identifier(), player.getYRot(), player.getXRot()),
                String.format(Locale.ROOT, "World border: x %.1f to %.1f, z %.1f to %.1f%s", mc.level.getWorldBorder().getMinX(),
                        mc.level.getWorldBorder().getMaxX(), mc.level.getWorldBorder().getMinZ(), mc.level.getWorldBorder().getMaxZ(),
                        Edge.known() ? " | edge learned: " + Edge.describe() : ""),
                "Flying: " + player.getAbilities().flying + ", on the ground: " + player.onGround() + ", game mode: "
                        + (mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName()),
                String.format(Locale.ROOT, "Health: %.0f, food: %d", player.getHealth(), player.getFoodData().getFoodLevel()),
                "In hand: slot " + (player.getInventory().getSelectedSlot() + 1) + ", " + (held.isEmpty() ? "nothing" : held.getCount() + " " + name(held)),
                inventory(player.getInventory()),
                "Server: " + ServerName.current(mc) + ", ping " + ServerLag.pingTicks(mc) * 50 + " ms, "
                        + String.format(Locale.ROOT, "%.1f", ServerLag.tps()) + " server ticks a second",
                "Add-on " + (Configs.enabled() ? "on" : "off")
                        + ", auto build " + (AutoBuild.INSTANCE.isRunning() ? "running: " + AutoBuild.INSTANCE.layers.status() : "not running")
                        + ", Auto break " + (AreaBreaker.INSTANCE.isRunning() ? "running: " + AreaBreaker.INSTANCE.status() : "not running"));
    }

    /** The hotbar slot by slot, the rest of the inventory as totals. */
    private static String inventory(Inventory inventory) {
        StringBuilder hotbar = new StringBuilder();
        Map<String, Integer> totals = new TreeMap<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (slot < Inventory.getSelectionSize()) {
                hotbar.append(slot == 0 ? "" : " | ").append(slot + 1).append(": ").append(stack.isEmpty() ? "-" : stack.getCount() + " " + name(stack));
            }
            if (!stack.isEmpty()) {
                totals.merge(name(stack), stack.getCount(), Integer::sum);
            }
        }
        return "Hotbar: " + hotbar + "\nInventory (" + InventoryCleaner.freeSlots(inventory) + " slots free): " + totals;
    }

    private static String name(ItemStack stack) {
        return stack.getItem().toString().replace("minecraft:", "");
    }

    private static String system(Minecraft mc) {
        Runtime runtime = Runtime.getRuntime();
        String graphics = "?";
        com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
        if (device != null) {
            com.mojang.blaze3d.systems.DeviceInfo info = device.getDeviceInfo();
            graphics = info.name() + " (" + info.vendorName() + ", " + info.backendName() + ", driver " + info.driverInfo() + ")";
        }
        com.mojang.blaze3d.platform.Window window = mc.getWindow();
        return String.join("\n",
                "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")",
                "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", " + System.getProperty("java.vm.name") + ")",
                "CPU threads: " + runtime.availableProcessors() + ", memory: " + (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                        + " of " + runtime.maxMemory() / 1048576 + " MB in use",
                "Graphics: " + graphics,
                "Window: " + window.getWidth() + " x " + window.getHeight() + (window.isFullscreen() ? ", fullscreen" : "")
                        + ", GUI scale " + window.getGuiScale(),
                "Render distance " + mc.options.renderDistance().get() + ", simulation distance " + mc.options.simulationDistance().get()
                        + ", max fps " + mc.options.framerateLimit().get() + ", fps now " + mc.getFps(),
                "Minecraft " + mc.getLaunchedVersion() + ", game folder " + mc.gameDirectory.getAbsolutePath());
    }

    private static String mods() {
        List<ModContainer> all = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        List<ModContainer> mods = all.stream().filter(mod -> mod.getContainingMod().isEmpty())
                .sorted(Comparator.comparing((ModContainer mod) -> mod.getMetadata().getId())).toList();
        StringBuilder out = new StringBuilder(mods.size() + " mods (" + all.size() + " with the parts inside them):");
        for (ModContainer mod : mods) {
            ModMetadata meta = mod.getMetadata();
            out.append('\n').append(meta.getId()).append(' ').append(meta.getVersion().getFriendlyString());
            if (!meta.getName().equalsIgnoreCase(meta.getId())) {
                out.append(" (").append(meta.getName()).append(')');
            }
        }
        return out.toString();
    }

    /** Each setting with its value, a * when it isn't the default; the log channel only as set or not. */
    private static void settings(StringBuilder out, String category, List<? extends IConfigBase> options) {
        for (IConfigBase option : options) {
            String value;
            if (option == Configs.Generic.LOG_CHANNEL) {
                value = Configs.Generic.LOG_CHANNEL.getStringValue().isBlank() ? "" : "(set, not shown)";
            } else if (option instanceof IStringRepresentable text) {
                value = text.getStringValue();
            } else if (option instanceof IConfigStringList list) {
                value = String.join(", ", list.getStrings());
            } else {
                value = String.valueOf(option.getAsJsonElement());
            }
            boolean changed = option instanceof IConfigResettable resettable && resettable.isModified();
            out.append(changed ? "* " : "  ").append(category).append('.').append(option.getName()).append(" = ").append(value).append('\n');
        }
    }

    private static String placements() {
        StringBuilder out = new StringBuilder();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            Path file = placement.getSchematicFile();
            out.append(placement.getName()).append(placement.isEnabled() ? "" : " (off)")
                    .append(": file ").append(file == null ? "-" : file.getFileName())
                    .append(", origin ").append(placement.getOrigin().toShortString())
                    .append(", rotation ").append(placement.getRotation()).append(", mirror ").append(placement.getMirror()).append('\n');
        }
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        out.append(areas.size()).append(areas.size() == 1 ? " area" : " areas").append(" switched on");
        for (int i = 0; i < Math.min(20, areas.size()); i++) {
            SchematicHelper.Area a = areas.get(i);
            out.append(String.format(Locale.ROOT, "\n%d %d %d to %d %d %d (%d x %d x %d)", a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ(),
                    a.maxX() - a.minX() + 1, a.maxY() - a.minY() + 1, a.maxZ() - a.minZ() + 1));
        }
        return out.toString();
    }

    /** The first (or last) lines of one of the add-on's files; maxAgeMinutes > 0: only when it's that new. */
    private static String lines(Path file, int max, boolean last, long maxAgeMinutes) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "(none)";
        }
        if (maxAgeMinutes > 0 && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > maxAgeMinutes * 60_000) {
            return "(none that new)";
        }
        List<String> all = List.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\r?\n"));
        List<String> some = last ? all.subList(Math.max(0, all.size() - max), all.size()) : all.subList(0, Math.min(max, all.size()));
        return String.join("\n", some) + (all.size() > max ? "\n(" + (all.size() - max) + " more lines left out)" : "");
    }

    /**
     * The game's own log without its chat lines, masked; its start and its end when it's big. Off the game's thread.
     * {@code thisGame}: latest.log; else the log of the game that was running at {@code seen} (the game puts latest.log
     * into a .log.gz when it starts again: the first one written after then).
     */
    static byte[] gameLog(Path gameDir, boolean thisGame, long seen) {
        Path dir = gameDir.resolve("logs");
        Path file = thisGame ? dir.resolve("latest.log") : rolledLog(dir, seen);
        if (file == null || !Files.isRegularFile(file)) {
            return new byte[0];
        }
        try {
            String text;
            if (file.getFileName().toString().endsWith(".gz")) {
                text = "(" + file.getFileName() + ": the game's log from the game the run was in)\n" + readGz(file);
            } else {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    long size = channel.size();
                    text = size <= GAME_LOG_READ ? read(channel, 0, (int) size)
                            : read(channel, 0, GAME_LOG_HEAD) + "\n" + read(channel, size - (GAME_LOG_READ - GAME_LOG_HEAD), GAME_LOG_READ - GAME_LOG_HEAD);
                }
            }
            StringBuilder kept = new StringBuilder(text.length());
            for (String line : text.split("\r?\n")) {
                // Chat is what other players wrote (and private messages): never sent
                if (!line.contains("[CHAT]")) {
                    kept.append(line).append('\n');
                }
            }
            return cutMiddle(mask(kept.toString()).getBytes(StandardCharsets.UTF_8), GAME_LOG_BYTES, GAME_LOG_HEAD);
        } catch (IOException | RuntimeException e) {
            return (file.getFileName() + " couldn't be read: " + e).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** The rolled game log (logs/<date>-<n>.log.gz) of the game that was running at {@code seen}, or null. */
    @Nullable
    private static Path rolledLog(Path dir, long seen) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log.gz") && p.toFile().lastModified() >= seen)
                    .min(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A .log.gz's start and its last part, at most GAME_LOG_READ bytes in all. */
    private static String readGz(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
            byte[] head = in.readNBytes(GAME_LOG_HEAD);
            int keep = GAME_LOG_READ - GAME_LOG_HEAD;
            // The last bytes, round and round in a ring
            byte[] ring = new byte[keep];
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                int at = (int) (total % keep);
                int first = Math.min(n, keep - at);
                System.arraycopy(buffer, 0, ring, at, first);
                System.arraycopy(buffer, first, ring, 0, n - first);
                total += n;
            }
            byte[] tail;
            if (total <= keep) {
                tail = Arrays.copyOf(ring, (int) total);
            } else {
                int at = (int) (total % keep);
                tail = new byte[keep];
                System.arraycopy(ring, at, tail, 0, keep - at);
                System.arraycopy(ring, 0, tail, keep - at, at);
            }
            return new String(head, StandardCharsets.UTF_8) + (total > keep ? "\n" : "") + new String(tail, StandardCharsets.UTF_8);
        }
    }

    /** A crash report's "Description:" line ("Unexpected error"), or "?". */
    static String crashDescription(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            java.util.regex.Matcher m = Pattern.compile("Description: (.+)").matcher(new String(in.readNBytes(16 * 1024), StandardCharsets.UTF_8));
            return m.find() ? m.group(1).trim() : "?";
        } catch (IOException | RuntimeException e) {
            return "?";
        }
    }

    /** A crash report's text, masked and cut to 512 KB. */
    static byte[] crashReport(Path file) {
        try {
            return cutMiddle(mask(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                    CRASH_BYTES, CRASH_BYTES / 2);
        } catch (IOException | RuntimeException e) {
            return ("The crash report couldn't be read: " + e).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String read(FileChannel channel, long from, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, from + buffer.position()) < 0) {
                break;
            }
        }
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
    }

    /** At most max bytes: the first head bytes and the last part, cut at line ends, with a note where the middle went. */
    static byte[] cutMiddle(byte[] bytes, int max, int head) {
        if (bytes.length <= max) {
            return bytes;
        }
        int headEnd = Math.min(head, max / 2);
        while (headEnd > 0 && bytes[headEnd - 1] != '\n') {
            headEnd--;
        }
        byte[] note = String.format(Locale.ROOT, "\n... (%.1f MB cut here) ...\n\n", (bytes.length - max) / 1048576.0).getBytes(StandardCharsets.UTF_8);
        int tailStart = bytes.length - (max - headEnd - note.length);
        while (tailStart < bytes.length && bytes[tailStart - 1] != '\n') {
            tailStart++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(max);
        out.write(bytes, 0, headEnd);
        out.writeBytes(note);
        out.write(bytes, tailStart, bytes.length - tailStart);
        return out.toByteArray();
    }

    /** The user folder as ~ (it has the player's name in it), login tokens and webhook addresses hidden. */
    static String mask(String text) {
        String home = System.getProperty("user.home", "");
        if (home.length() > 3) {
            text = text.replace(home, "~").replace(home.replace('\\', '/'), "~");
        }
        String user = System.getProperty("user.name", "");
        if (user.length() > 2) {
            text = text.replace("Users\\" + user, "Users\\~").replace("Users/" + user, "Users/~").replace("home/" + user, "home/~");
        }
        text = JWT.matcher(text).replaceAll("(token hidden)");
        text = TOKEN.matcher(text).replaceAll("$1 (hidden)");
        return WEBHOOK.matcher(text).replaceAll("(webhook hidden)");
    }

    @Nullable
    private static Recent newest(Path dir, String suffix, long maxMinutes) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix) && Files.isRegularFile(p))
                    .map(p -> new Recent(p, (now - p.toFile().lastModified()) / 60_000))
                    .filter(r -> r.minutesAgo() >= 0 && r.minutesAgo() <= maxMinutes)
                    .min(Comparator.comparingLong(Recent::minutesAgo)).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
