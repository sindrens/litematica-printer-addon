package no.sindrens.printeraddon.shop;

import com.google.gson.Gson;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;
import no.sindrens.printeraddon.util.ServerName;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Buys items from /shop with the menus the shop scan found: opens the money shop, the category and the page,
 * clicks the item, sets the amount with the +32/+16/+1 buttons, presses purchase and checks the items arrived.
 * Buys only what fits in the inventory. Stops when a purchase doesn't arrive (not enough money).
 */
public class ShopBuyer {
    public static final ShopBuyer INSTANCE = new ShopBuyer();

    private static final int STABLE_TICKS = 5;
    /** How long a +amount click may take before it counts as "the amount can't go higher". */
    private static final int AMOUNT_TIMEOUT = 20;
    private static final Pattern PLUS = Pattern.compile("^\\+\\s*(\\d+)$");
    private static final Pattern MINUS = Pattern.compile("^[-\u2212]\\s*(\\d+)$");
    private static final Pattern TIMES = Pattern.compile("^x\\s*(\\d+)$");

    private enum Phase { OPEN, WAIT_MENU, NAVIGATE, OPEN_ITEM, AMOUNT, WAIT_AMOUNT, PURCHASE, VERIFY }

    private static final class Order {
        final Item item;
        final String id;
        int left;
        final ShopScanResult.Location location;
        /** Tries from the start (/shop again) after something didn't look right. */
        int retries;

        Order(Item item, int left, ShopScanResult.Location location) {
            this.item = item;
            this.id = BuiltInRegistries.ITEM.getKey(item).toString();
            this.left = left;
            this.location = location;
        }
    }

    private final Deque<Order> orders = new ArrayDeque<>();
    private final Deque<Integer> navigation = new ArrayDeque<>();
    private final Map<Item, Integer> bought = new LinkedHashMap<>();
    private final List<Item> notSold = new ArrayList<>();
    private final List<Item> notBought = new ArrayList<>();

    private boolean running;
    private ShopScanResult shop;
    private Phase phase;
    private int delay;
    private int waited;
    @Nullable
    private String before;
    @Nullable
    private String lastSignature;
    private int stable;
    private int countBefore;
    private int lastAmount;
    private double spent;
    @Nullable
    private String error;

    public boolean isRunning() {
        return running;
    }

    /** Items bought in the last run. */
    public Map<Item, Integer> bought() {
        return bought;
    }

    /** Items from the last run that the shop doesn't sell. */
    public List<Item> notSold() {
        return notSold;
    }

    /** Items from the last run that couldn't be bought (no money, or the shop didn't work). */
    public List<Item> notBought() {
        return notBought;
    }

    private static ShopScanResult cachedScan;
    private static long cachedScanTime;

    /** True if this server's shop scan lists the item with a buy price. */
    public static boolean sells(Minecraft mc, Item item) {
        Path file = scanFile(mc);
        if (file == null) {
            return false;
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (cachedScan == null || modified != cachedScanTime) {
                cachedScan = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), ShopScanResult.class);
                cachedScanTime = modified;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        List<ShopScanResult.Location> locations = cachedScan.items.get(BuiltInRegistries.ITEM.getKey(item).toString());
        return locations != null && locations.stream().anyMatch(l -> l.buyPrice != null);
    }

    @Nullable
    public static Path scanFile(Minecraft mc) {
        Path file = FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("shops")
                .resolve(ServerName.current(mc) + ".json");
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * Starts buying the list (item -> how many). Returns false if there's nothing it can buy: no shop scan for this
     * server, nothing on the list sold there, or no room.
     */
    public boolean start(Minecraft mc, Map<Item, Integer> list) {
        if (running || mc.player == null) {
            return false;
        }
        orders.clear();
        bought.clear();
        notSold.clear();
        notBought.clear();
        spent = 0;
        error = null;

        Path file = scanFile(mc);
        if (file == null) {
            Chat.warn("No shop scan for this server yet: hold J and press S once, then auto buy works.");
            notBought.addAll(list.keySet());
            return false;
        }
        try {
            shop = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), ShopScanResult.class);
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.error("Couldn't read the shop scan {}", file, e);
            Chat.warn("Couldn't read the shop scan (" + e.getMessage() + "). Scan the shop again (J+S).");
            notBought.addAll(list.keySet());
            return false;
        }

        for (Map.Entry<Item, Integer> entry : list.entrySet()) {
            String id = BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
            ShopScanResult.Location location = cheapest(shop.items.get(id));
            if (location == null) {
                notSold.add(entry.getKey());
            } else if (entry.getValue() > 0) {
                orders.add(new Order(entry.getKey(), entry.getValue(), location));
            }
        }
        if (!notSold.isEmpty()) {
            Chat.warn("Not sold in /" + shop.shopCommand + ": " + names(notSold) + ". Put them in your inventory.");
        }
        if (orders.isEmpty()) {
            return false;
        }

        running = true;
        phase = Phase.OPEN;
        Chat.info("Buying from /" + shop.shopCommand + ": " + describe(orders) + ". Don't touch the menu.");
        return true;
    }

    public void cancel(@Nullable String reason) {
        if (!running) {
            return;
        }
        running = false;
        for (Order order : orders) {
            notBought.add(order.item);
        }
        orders.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        if (reason != null) {
            Chat.warn("Buying stopped: " + reason + ".");
        }
    }

    public void tick(Minecraft mc) {
        if (!running || mc.player == null || mc.gameMode == null) {
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }
        Order order = orders.peekFirst();
        if (order == null) {
            finish(mc);
            return;
        }
        waited++;

        switch (phase) {
            case OPEN -> {
                if (room(mc, order.item) <= 0) {
                    // Not a failure: buy the rest when there's room again
                    PrinterAddon.debug("No room for more {}", order.location.name);
                    orders.pollFirst();
                    return;
                }
                if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
                    mc.player.closeContainer();
                    delay = clickDelay();
                    return;
                }
                mc.player.connection.sendCommand(shop.shopCommand);
                navigation.clear();
                navigation.addAll(order.location.path);
                ShopScanResult.Category category = categoryOf(order.location.path);
                for (int page = 0; page < order.location.page; page++) {
                    Integer next = category == null || page >= category.pages.size() ? null : category.pages.get(page).nextPageSlot;
                    if (next == null) {
                        skip(order, "page " + (order.location.page + 1) + " isn't in the scan");
                        return;
                    }
                    navigation.add(next);
                }
                await(null, Phase.NAVIGATE);
            }
            case WAIT_MENU -> {
                ScreenSnapshot snapshot = stableMenu(mc);
                if (snapshot != null) {
                    phase = next;
                    waited = 0;
                    delay = clickDelay();
                } else if (waited > timeout()) {
                    fail(order, "the shop menu didn't open or change");
                }
            }
            case NAVIGATE -> {
                Integer slot = navigation.pollFirst();
                if (slot == null) {
                    phase = Phase.OPEN_ITEM;
                    waited = 0;
                    return;
                }
                click(mc, slot, Phase.NAVIGATE);
            }
            case OPEN_ITEM -> {
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                ScreenSnapshot.Item item = snapshot == null ? null : itemAt(snapshot, order.location.slot);
                int slot = order.location.slot;
                if (item == null || !item.itemId().equals(order.id)) {
                    // The page may still be filling in (lag): give it time before deciding anything
                    if (waited < 40 + 2 * no.sindrens.printeraddon.build.ServerLag.pingTicks(mc)) {
                        return;
                    }
                    // Moved on the page? Take it where it is now
                    ScreenSnapshot.Item elsewhere = snapshot == null ? null : snapshot.items().stream()
                            .filter(i -> i.itemId().equals(order.id) && i.slot() < snapshot.size()).findFirst().orElse(null);
                    if (elsewhere != null) {
                        PrinterAddon.debug("{} moved from slot {} to {} in the shop", order.location.name, slot, elsewhere.slot());
                        slot = elsewhere.slot();
                    } else if (order.retries++ < 2) {
                        // Once more from the start, slowly
                        PrinterAddon.debug("{} isn't where it was: opening the shop again (try {})", order.location.name, order.retries + 1);
                        mc.player.closeContainer();
                        phase = Phase.OPEN;
                        waited = 0;
                        delay = 20 + clickDelay();
                        return;
                    } else {
                        fail(order, "the shop has changed (" + order.location.name + " isn't where it was). Scan the shop again (J+S)");
                        return;
                    }
                }
                click(mc, slot, Phase.AMOUNT);
            }
            case AMOUNT -> {
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                if (snapshot == null || !snapshot.looksLikeBuyScreen()) {
                    if (order.retries++ < 2) {
                        PrinterAddon.debug("Clicking {} didn't open the buy screen: opening the shop again", order.location.name);
                        mc.player.closeContainer();
                        phase = Phase.OPEN;
                        waited = 0;
                        delay = 20 + clickDelay();
                        return;
                    }
                    fail(order, "clicking " + order.location.name + " didn't open the buy screen");
                    return;
                }
                int amount = amountShown(snapshot, order.id);
                int room = room(mc, order.item);
                int want = Math.max(1, Math.min(64, Math.min(order.left, room)));
                Integer button = null;
                if (amount < want) {
                    button = plusButton(snapshot, want - amount, PLUS);
                    // Many +1 clicks for the last few aren't worth it: buy what's set, the rest next time
                    if (button != null && stepOf(snapshot, button) == 1 && want - amount > 4 && amount >= 16) {
                        button = null;
                    }
                } else if (amount > want) {
                    button = plusButton(snapshot, amount - want, MINUS);
                    if (button == null && amount > room) {
                        // Can't lower it here: start over with a fresh buy screen
                        mc.player.closeContainer();
                        phase = Phase.OPEN;
                        delay = clickDelay();
                        return;
                    }
                }
                if (button != null) {
                    lastAmount = amount;
                    before = snapshot.signature();
                    clickSlot(mc, snapshot, button);
                    phase = Phase.WAIT_AMOUNT;
                    waited = 0;
                } else {
                    phase = Phase.PURCHASE;
                }
            }
            case WAIT_AMOUNT -> {
                ScreenSnapshot snapshot = stableMenu(mc);
                if (snapshot != null && amountShown(snapshot, order.id) != lastAmount) {
                    phase = Phase.AMOUNT;
                    delay = clickDelay();
                } else if (waited > AMOUNT_TIMEOUT) {
                    // The amount doesn't go higher: buy what's set
                    phase = Phase.PURCHASE;
                }
            }
            case PURCHASE -> {
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                Integer button = snapshot == null ? null : purchaseButton(snapshot);
                if (button == null) {
                    fail(order, "no purchase button on the buy screen");
                    return;
                }
                countBefore = count(mc, order.item);
                lastAmount = amountShown(snapshot, order.id);
                clickSlot(mc, snapshot, button);
                phase = Phase.VERIFY;
                waited = 0;
            }
            case VERIFY -> {
                int got = count(mc, order.item) - countBefore;
                if (got > 0 && (got >= lastAmount || waited > 10)) {
                    order.left -= got;
                    bought.merge(order.item, got, Integer::sum);
                    if (order.location.buyPrice != null) {
                        spent += got * order.location.buyPrice;
                    }
                    PrinterAddon.debug("Bought {} {}", got, order.location.name);
                    if (order.left <= 0 || room(mc, order.item) <= 0) {
                        orders.pollFirst();
                        phase = Phase.OPEN;
                    } else {
                        // Buy screens usually stay open: set the amount again
                        ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                        phase = snapshot != null && snapshot.looksLikeBuyScreen() ? Phase.AMOUNT : Phase.OPEN;
                    }
                    delay = clickDelay();
                } else if (waited > timeout() && order.retries++ < 1) {
                    // Nothing arrived: once more from the start (the first purchase after opening sometimes gets lost)
                    PrinterAddon.debug("The purchase of {} didn't arrive: trying once more", order.location.name);
                    mc.player.closeContainer();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = 20 + clickDelay();
                } else if (waited > timeout()) {
                    // Nothing arrived again: most likely not enough money. Stop, don't keep clicking.
                    fail(order, "the purchase of " + order.location.name + " didn't arrive (not enough money?)");
                    orders.forEach(o -> notBought.add(o.item));
                    orders.clear();
                }
            }
        }
    }

    private Phase next = Phase.NAVIGATE;

    private void await(@Nullable String mustDiffer, Phase then) {
        before = mustDiffer;
        lastSignature = null;
        stable = 0;
        waited = 0;
        next = then;
        phase = Phase.WAIT_MENU;
    }

    private void click(Minecraft mc, int slot, Phase then) {
        ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
        if (snapshot == null) {
            Order order = orders.peekFirst();
            if (order != null) {
                fail(order, "the shop menu closed");
            }
            return;
        }
        clickSlot(mc, snapshot, slot);
        await(snapshot.signature(), then);
    }

    private static void clickSlot(Minecraft mc, ScreenSnapshot snapshot, int slot) {
        mc.gameMode.handleContainerInput(snapshot.containerId(), slot, 0, ContainerInput.PICKUP, mc.player);
    }

    /** The open menu once it has stopped changing (and differs from {@link #before}), else null. */
    @Nullable
    private ScreenSnapshot stableMenu(Minecraft mc) {
        ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
        if (snapshot == null) {
            lastSignature = null;
            stable = 0;
            return null;
        }
        String signature = snapshot.signature();
        if (signature.equals(lastSignature)) {
            stable++;
        } else {
            stable = 0;
            lastSignature = signature;
        }
        // Unchanged for a few ticks (more with a high ping): shop plugins often fill a page in several steps
        return stable >= STABLE_TICKS + no.sindrens.printeraddon.build.ServerLag.pingTicks(Minecraft.getInstance()) / 2 && !signature.equals(before) ? snapshot : null;
    }

    private void skip(Order order, String why) {
        PrinterAddon.debug("Not buying {}: {}", order.location.name, why);
        notBought.add(order.item);
        orders.remove(order);
        phase = Phase.OPEN;
    }

    private void fail(Order order, String why) {
        error = why;
        Chat.warn("Couldn't buy " + order.location.name + ": " + why + ".");
        skip(order, why);
    }

    private void finish(Minecraft mc) {
        running = false;
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        if (!bought.isEmpty()) {
            StringBuilder sb = new StringBuilder("Bought ");
            bought.forEach((item, n) -> sb.append(n).append(' ').append(new ItemStack(item).getHoverName().getString()).append(", "));
            String text = sb.substring(0, sb.length() - 2);
            Chat.info(spent > 0 ? text + String.format(" (about $%,.2f)", spent) : text);
        } else if (error == null) {
            Chat.warn("Nothing was bought.");
        }
    }

    // ---- Reading the menus ---------------------------------------------------------------------------------

    @Nullable
    private static ShopScanResult.Location cheapest(@Nullable List<ShopScanResult.Location> locations) {
        if (locations == null) {
            return null;
        }
        ShopScanResult.Location best = null;
        for (ShopScanResult.Location location : locations) {
            if (best == null || (location.buyPrice != null && (best.buyPrice == null || location.buyPrice < best.buyPrice))) {
                best = location;
            }
        }
        return best;
    }

    @Nullable
    private ShopScanResult.Category categoryOf(List<Integer> path) {
        for (ShopScanResult.Category category : shop.categories) {
            if (category.path.equals(path)) {
                return category;
            }
        }
        return null;
    }

    @Nullable
    private static ScreenSnapshot.Item itemAt(ScreenSnapshot snapshot, int slot) {
        for (ScreenSnapshot.Item item : snapshot.items()) {
            if (item.slot() == slot) {
                return item;
            }
        }
        return null;
    }

    /** The amount the buy screen is set to: "x 16" under the item, or the item's stack size. */
    private static int amountShown(ScreenSnapshot snapshot, String itemId) {
        for (ScreenSnapshot.Item item : snapshot.items()) {
            if (!item.itemId().equals(itemId)) {
                continue;
            }
            for (String line : item.lore()) {
                Matcher m = TIMES.matcher(ShopText.plain(line));
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            return item.count();
        }
        return 1;
    }

    /** The biggest +N (or -N) button that doesn't go past {@code missing}. */
    @Nullable
    private static Integer plusButton(ScreenSnapshot snapshot, int missing, Pattern pattern) {
        Integer best = null;
        int bestStep = 0;
        for (ScreenSnapshot.Item item : snapshot.items()) {
            Matcher m = pattern.matcher(ShopText.plain(item.name()));
            if (m.find()) {
                int step = Integer.parseInt(m.group(1));
                if (step <= missing && step > bestStep) {
                    best = item.slot();
                    bestStep = step;
                }
            }
        }
        return best;
    }

    private static int stepOf(ScreenSnapshot snapshot, int slot) {
        ScreenSnapshot.Item item = itemAt(snapshot, slot);
        if (item == null) {
            return 0;
        }
        Matcher m = Pattern.compile("(\\d+)").matcher(item.name());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** "Purchase item" / "Confirm" / "Buy", never "purchase stacks". */
    @Nullable
    private static Integer purchaseButton(ScreenSnapshot snapshot) {
        for (ScreenSnapshot.Item item : snapshot.items()) {
            String name = ShopText.plain(item.name());
            if (name.contains("stack") || name.contains("cancel")) {
                continue;
            }
            if (name.contains("purchase") || name.contains("confirm") || name.equals("buy")) {
                return item.slot();
            }
        }
        return null;
    }

    // ---- Inventory -----------------------------------------------------------------------------------------

    private static int count(Minecraft mc, Item item) {
        return mc.player.getInventory().countItem(item);
    }

    /** How many of the item still fit in the main inventory. */
    public static int room(Minecraft mc, Item item) {
        Inventory inventory = mc.player.getInventory();
        int max = item.getDefaultMaxStackSize();
        int room = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                room += max;
            } else if (stack.is(item) && stack.getComponents().equals(new ItemStack(item).getComponents())) {
                room += Math.max(0, max - stack.getCount());
            }
        }
        return room;
    }

    private static int timeout() {
        return Configs.Shop.WAIT_TIMEOUT_TICKS.getIntegerValue();
    }

    /** Between clicks: never faster than 8 ticks, plus half the ping (servers drop clicks that come too fast). */
    private static int clickDelay() {
        return Math.max(8, Configs.Speed.shopClickDelayTicks()) + no.sindrens.printeraddon.build.ServerLag.pingTicks(Minecraft.getInstance()) / 2;
    }

    private static String names(List<Item> items) {
        List<String> names = new ArrayList<>();
        for (Item item : items) {
            names.add(new ItemStack(item).getHoverName().getString());
        }
        return String.join(", ", names);
    }

    private static String describe(Deque<Order> orders) {
        List<String> parts = new ArrayList<>();
        for (Order order : orders) {
            parts.add(order.left + " " + order.location.name);
        }
        return String.join(", ", parts);
    }
}
