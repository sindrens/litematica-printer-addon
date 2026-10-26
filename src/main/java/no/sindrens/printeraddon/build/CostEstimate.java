package no.sindrens.printeraddon.build;

import com.google.gson.Gson;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.shop.ShopBuyer;
import no.sindrens.printeraddon.shop.ShopScanResult;
import no.sindrens.printeraddon.shop.ShopText;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a schematic still costs: when a schematic is loaded (or with the hotkey) it counts what's missing compared
 * with the inventory, prices it with the shop scan of this server, includes what can be crafted from shop items
 * (rails from iron blocks, powered rails from gold blocks, seeds from pumpkins), and compares it with the balance.
 */
public final class CostEstimate {
    private static String lastPlacement = "";
    private static int ticks;

    private CostEstimate() {
    }

    /** Shows the estimate when another schematic placement is selected. */
    public static void tick(Minecraft mc) {
        if (++ticks % 40 != 0 || mc.player == null) {
            return;
        }
        // Any placement counts (the same ones auto build uses), not only the selected one
        String key = SchematicHelper.world() == null ? "" : SchematicHelper.areas().toString();
        if (!key.equals(lastPlacement)) {
            lastPlacement = key;
            if (!key.isEmpty() && !SchematicHelper.areas().isEmpty()) {
                show(mc);
            }
        }
    }

    /** One way to get an item: buy it, or craft it from things bought. */
    private record Price(double each, String how) {
    }

    /** About what the rest of the build costs in the shop (null without a schematic or a shop scan). */
    @Nullable
    public static Double estimate(Minecraft mc) {
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null || mc.player == null) {
            return null;
        }
        ShopScanResult shop = loadShop(mc);
        if (shop == null) {
            return null;
        }
        double total = 0;
        for (Map.Entry<Item, Integer> entry : needed(mc, schematic).entrySet()) {
            int missing = entry.getValue() - mc.player.getInventory().countItem(entry.getKey());
            Price price = missing <= 0 ? null : priceOf(shop, entry.getKey());
            if (price != null) {
                total += missing * price.each();
            }
        }
        return total;
    }

    /** What the rest of the build needs (item -> count). */
    private static Map<Item, Integer> needed(Minecraft mc, WorldSchematic schematic) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState target = schematic.getBlockState(pos);
                        if (target.isAir()) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.BUCKET) {
                            if (Fluids.bucketFor(target) != null) {
                                needed.put(Fluids.bucketFor(target), 1);
                            }
                        } else if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                            ItemStack stack = StateRules.itemFor(target);
                            if (!stack.isEmpty()) {
                                needed.merge(stack.getItem(), 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }
        EntityPlacer.missing(mc, schematic).forEach((item, count) -> needed.merge(item, count, Integer::sum));
        return needed;
    }

    public static void show(Minecraft mc) {
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null || mc.player == null) {
            Chat.warn("No schematic loaded.");
            return;
        }
        Map<Item, Integer> needed = new LinkedHashMap<>();
        int blocks = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState target = schematic.getBlockState(pos);
                        if (target.isAir()) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        Item item = null;
                        if (result == BlockCheck.Result.BUCKET) {
                            // One bucket does them all
                            if (Fluids.bucketFor(target) != null) {
                                needed.put(Fluids.bucketFor(target), 1);
                            }
                        } else if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                            ItemStack stack = StateRules.itemFor(target);
                            item = stack.isEmpty() ? null : stack.getItem();
                        }
                        if (item != null) {
                            needed.merge(item, 1, Integer::sum);
                            blocks++;
                        }
                    }
                }
            }
        }
        EntityPlacer.missing(mc, schematic).forEach((item, count) -> needed.merge(item, count, Integer::sum));

        ShopScanResult shop = loadShop(mc);
        double total = 0;
        List<String> lines = new ArrayList<>();
        List<String> notSold = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int missing = entry.getValue() - mc.player.getInventory().countItem(entry.getKey());
            if (missing <= 0) {
                continue;
            }
            String name = new ItemStack(entry.getKey()).getHoverName().getString();
            Price price = shop == null ? null : priceOf(shop, entry.getKey());
            if (price == null) {
                notSold.add(missing + " " + name);
                continue;
            }
            double cost = missing * price.each();
            total += cost;
            lines.add(String.format(java.util.Locale.ROOT, "%,d %s: $%,.0f%s", missing, name, cost, price.how().isEmpty() ? "" : " (" + price.how() + ")"));
        }

        Chat.info("§lThis build:§r " + String.format(java.util.Locale.ROOT, "%,d", blocks) + " blocks left to place.");
        if (shop == null) {
            Chat.warn("No shop scan for this server yet (J+S), so no prices.");
        } else if (lines.isEmpty() && notSold.isEmpty()) {
            Chat.info("§aYou have everything it needs.");
        } else {
            lines.sort((a, b) -> Double.compare(cost(b), cost(a)));
            for (String line : lines.subList(0, Math.min(8, lines.size()))) {
                Chat.info(" - " + line);
            }
            if (lines.size() > 8) {
                Chat.info(" - and " + (lines.size() - 8) + " more");
            }
            Double balance = balance(mc);
            Chat.info(String.format(java.util.Locale.ROOT, "§lCost: about $%,.0f§r", total) + (balance == null ? ""
                    : String.format(java.util.Locale.ROOT, " | balance $%,.0f%s", balance, balance >= total ? " §a(enough)"
                    : String.format(java.util.Locale.ROOT, " §c(short by $%,.0f)", total - balance))));
        }
        if (!notSold.isEmpty()) {
            Chat.warn("Not in the shop, bring these: " + String.join(", ", notSold));
        }
    }

    private static double cost(String line) {
        int at = line.indexOf(": $");
        if (at < 0) {
            return 0;
        }
        Double value = ShopText.parseNumber(line.substring(at + 3));
        return value == null ? 0 : value;
    }

    @Nullable
    private static ShopScanResult loadShop(Minecraft mc) {
        Path file = ShopBuyer.scanFile(mc);
        if (file == null) {
            return null;
        }
        try {
            return ShopBuyer.load(file);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't read {}", file, e);
            return null;
        }
    }

    /** The cheapest way to get one: buy it, or craft it from shop items. */
    @Nullable
    private static Price priceOf(ShopScanResult shop, Item item) {
        Double direct = buyPrice(shop, item);
        Price best = direct == null ? null : new Price(direct, "");
        Price crafted = craftedPrice(shop, item);
        if (crafted != null && (best == null || crafted.each() < best.each())) {
            best = crafted;
        }
        return best;
    }

    @Nullable
    private static Price craftedPrice(ShopScanResult shop, Item item) {
        Double stick = cheapest(buyPrice(shop, Items.STICK), divide(buyPrice(shop, Items.OAK_PLANKS), 2),
                divide(buyPrice(shop, Items.OAK_LOG), 8));
        Double iron = cheapest(buyPrice(shop, Items.IRON_INGOT), divide(buyPrice(shop, Items.IRON_BLOCK), 9));
        Double gold = cheapest(buyPrice(shop, Items.GOLD_INGOT), divide(buyPrice(shop, Items.GOLD_BLOCK), 9));
        Double redstone = cheapest(buyPrice(shop, Items.REDSTONE), divide(buyPrice(shop, Items.REDSTONE_BLOCK), 9));
        if (item == Items.RAIL && iron != null && stick != null) {
            return new Price((6 * iron + stick) / 16, "crafted from iron and sticks");
        }
        if (item == Items.POWERED_RAIL && gold != null && stick != null && redstone != null) {
            return new Price((6 * gold + stick + redstone) / 6, "crafted from gold, sticks and redstone");
        }
        if (item == Items.PUMPKIN_SEEDS) {
            Double pumpkin = buyPrice(shop, Items.PUMPKIN);
            return pumpkin == null ? null : new Price(pumpkin / 4, "crafted from pumpkins");
        }
        if (item == Items.MELON_SEEDS) {
            Double melon = buyPrice(shop, Items.MELON_SLICE);
            return melon == null ? null : new Price(melon, "crafted from melon slices");
        }
        return null;
    }

    @Nullable
    private static Double divide(@Nullable Double value, int by) {
        return value == null ? null : value / by;
    }

    @Nullable
    private static Double cheapest(Double... values) {
        Double best = null;
        for (Double value : values) {
            if (value != null && (best == null || value < best)) {
                best = value;
            }
        }
        return best;
    }

    /** Every item the scanned shop sells, with its cheapest price for one (for the schematic library on Discord). */
    public static java.util.Map<String, Double> unitPrices(Minecraft mc) {
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        ShopScanResult shop = loadShop(mc);
        if (shop == null) {
            return out;
        }
        shop.items.forEach((id, locations) -> {
            for (ShopScanResult.Location location : locations) {
                if (location.buyPrice != null && location.buyable() && location.amountShown > 0) {
                    double each = location.buyPrice / Math.max(1, location.amountShown);
                    out.merge(id, each, Math::min);
                }
            }
        });
        return out;
    }

    /** When the saved shop scan last changed (0: none). */
    public static long scanTime(Minecraft mc) {
        Path file = ShopBuyer.scanFile(mc);
        try {
            return file == null ? 0 : java.nio.file.Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    @Nullable
    static Double buyPrice(ShopScanResult shop, Item item) {
        List<ShopScanResult.Location> locations = shop.items.get(BuiltInRegistries.ITEM.getKey(item).toString());
        if (locations == null) {
            return null;
        }
        Double best = null;
        for (ShopScanResult.Location location : locations) {
            if (location.buyPrice != null && location.buyable() && location.amountShown > 0) {
                double each = location.buyPrice / Math.max(1, location.amountShown);
                if (best == null || each < best) {
                    best = each;
                }
            }
        }
        return best;
    }

    /** The money shown on the sidebar ("Balance: $118k"), if there is such a line. */
    @Nullable
    public static Double balance(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return null;
        }
        // "Balance: $9.9M" first, else any line with a $ amount (the lines come in no particular order)
        Double anyDollar = null;
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
            // Read in plain letters: small-caps "ᴍ" is a million too
            String plain = ShopText.plain(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString());
            boolean named = plain.contains("bal") || plain.contains("money") || plain.contains("coins");
            if (!named && !plain.contains("$")) {
                continue;
            }
            int dollar = plain.indexOf('$');
            Double value = ShopText.parseNumber(dollar >= 0 ? plain.substring(dollar + 1) : plain);
            if (value == null) {
                continue;
            }
            if (named) {
                return value;
            }
            if (anyDollar == null) {
                anyDollar = value;
            }
        }
        return anyDollar;
    }
}
