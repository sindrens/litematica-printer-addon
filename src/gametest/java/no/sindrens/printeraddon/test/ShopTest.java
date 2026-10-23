package no.sindrens.printeraddon.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.malilib.util.FileUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.shop.ShopScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Scans the fake ShopGUI+ shop: only the money shop (paper) may be opened, items for sale are read from their
 * text and never clicked (except once to read the buy screen, and once where items show no text), nothing is
 * bought, and both pages and all prices are found.
 */
public class ShopTest implements FabricClientGameTest {
    private static final int MAX_TICKS = 20 * 120;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            FakeShop.reset();
            Path dir = context.computeOnClient(mc -> FileUtils.getConfigDirectory()).resolve("printer_addon").resolve("shops");
            deleteScans(dir);

            context.runOnClient(mc -> {
                Configs.Shop.SHOP_COMMAND.setValueFromString("shop");
                Configs.Shop.SHOP_SECTION.setValueFromString("minecraft:paper");
                Configs.Shop.PROBE_BUY_SCREEN.setBooleanValue(true);
                ShopScanner.INSTANCE.start(mc);
            });

            long started = System.currentTimeMillis();
            int ticks = 0;
            while (ticks < MAX_TICKS && context.computeOnClient(mc -> ShopScanner.INSTANCE.isRunning())) {
                context.waitTicks(10);
                ticks += 10;
                if (ticks % 100 == 0) {
                    context.takeScreenshot("shop");
                }
            }
            double seconds = (System.currentTimeMillis() - started) / 1000.0;
            if (ticks >= MAX_TICKS) {
                throw new AssertionError("Shop scan didn't finish in " + MAX_TICKS / 20 + " s");
            }

            Path file;
            try (Stream<Path> files = Files.list(dir)) {
                file = files.filter(f -> f.toString().endsWith(".json"))
                        .max(Comparator.comparingLong(f -> f.toFile().lastModified())).orElseThrow();
            }
            JsonObject scan = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject items = scan.getAsJsonObject("items");

            StringBuilder report = new StringBuilder("SHOP TEST: scan took " + seconds + " s (" + ticks + " ticks)\n");
            report.append("  other shops opened: ").append(FakeShop.otherShopClicks)
                    .append(", item clicks: ").append(FakeShop.itemClicks)
                    .append(", confirm clicks: ").append(FakeShop.confirmClicks).append('\n');
            report.append("  items found: ").append(items.size()).append(", categories: ")
                    .append(scan.getAsJsonArray("categories").size()).append('\n');
            report.append("  notes: ").append(scan.getAsJsonArray("notes")).append('\n');
            System.out.println(report);

            check(FakeShop.otherShopClicks == 0, "opened a shop other than the money shop");
            check(FakeShop.confirmClicks == 0, "clicked confirm (would buy)");
            // One probe of the buy screen, plus one click in the category whose items show no text
            check(FakeShop.itemClicks <= 2, "clicked " + FakeShop.itemClicks + " items for sale (at most 2 allowed)");
            check(scan.get("buyScreen") != null && !scan.get("buyScreen").isJsonNull(), "no buy screen recorded");

            checkPrice(items, "minecraft:stone", 2.0);
            checkPrice(items, "minecraft:hay_block", 7.0);
            checkPrice(items, "minecraft:cauldron", 18.0);          // page 2
            checkPrice(items, "minecraft:pumpkin_seeds", 1500.0);   // "Cost: 1.5k"
            checkPrice(items, "minecraft:melon_seeds", 3.0);        // "Buy 1x for $3"
            checkPrice(items, "minecraft:lava_bucket", 250.0);      // "Buy: $250" next to "Sell: $100"
            checkPrice(items, "minecraft:water_bucket", 20.0);      // "Price: $20"
            for (String id : new String[]{"minecraft:hopper", "minecraft:observer", "minecraft:piston", "minecraft:powered_rail"}) {
                check(items.has(id), id + " (no price text) wasn't found");
            }
            check(!items.has("minecraft:diamond") && !items.has("minecraft:tripwire_hook"), "read items from another shop");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void checkPrice(JsonObject items, String id, double price) {
        JsonArray locations = items.getAsJsonArray(id);
        check(locations != null && !locations.isEmpty(), id + " wasn't found");
        JsonElement found = locations.get(0).getAsJsonObject().get("buyPrice");
        check(found != null && !found.isJsonNull() && Math.abs(found.getAsDouble() - price) < 0.001,
                id + " price " + found + ", expected " + price);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError("Shop test: " + message);
        }
    }

    private static void deleteScans(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
        }
    }
}
