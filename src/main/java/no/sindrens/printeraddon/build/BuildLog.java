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
 * to, how long), and every 5 seconds where the player is and how fast blocks go in. One file per build in
 * .minecraft/logs/printer_addon/, the last 20 are kept.
 */
public final class BuildLog {
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int KEEP_FILES = 20;

    @Nullable
    private static BufferedWriter out;
    @Nullable
    private static Path file;
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
        return out != null;
    }

    public static void open() {
        close();
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            removeOld(dir);
            file = dir.resolve("build-" + LocalDateTime.now().format(FILE_NAME) + ".log");
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't open the build log", e);
            out = null;
            file = null;
        }
    }

    public static void close() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** One line: time, game tick, kind, text. */
    public static void line(String kind, String text) {
        BufferedWriter writer = out;
        if (writer == null) {
            return;
        }
        try {
            writer.write(LocalDateTime.now().format(TIME));
            writer.write(" t=");
            writer.write(Long.toString(AutoBuild.clientTicks));
            writer.write(' ');
            writer.write(kind);
            writer.write(' ');
            writer.write(text);
            writer.newLine();
            long now = System.currentTimeMillis();
            if (now - lastFlush > 1000) {
                lastFlush = now;
                writer.flush();
            }
        } catch (IOException e) {
            close();
        }
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
