package no.sindrens.printeraddon.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What auto build is doing, as JSON, for a live view outside the game (setting "Write status file"). */
public final class StatusFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private StatusFile() {
    }

    private record ItemCount(String id, String name, int count) {
    }

    private record Status(String time, String layers, String next, int[] nextPos, String doing,
                          List<ItemCount> missingForLayer, List<ItemCount> missingForBuild, List<String> recent) {
    }

    public static void write(Minecraft mc, String layers, String next, @Nullable BlockPos nextPos, String doing,
                             Map<Item, Integer> missingForLayer, Map<Item, Integer> missingForBuild) {
        Status status = new Status(Instant.now().toString(), layers, next,
                nextPos == null ? null : new int[]{nextPos.getX(), nextPos.getY(), nextPos.getZ()}, doing,
                toList(missingForLayer), toList(missingForBuild), Chat.recent());
        Path file = FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("status.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(status), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't write {}", file, e);
        }
    }

    private static List<ItemCount> toList(Map<Item, Integer> items) {
        List<ItemCount> list = new ArrayList<>();
        items.forEach((item, count) -> list.add(new ItemCount(BuiltInRegistries.ITEM.getKey(item).getPath(),
                new ItemStack(item).getHoverName().getString(), count)));
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return list;
    }
}
