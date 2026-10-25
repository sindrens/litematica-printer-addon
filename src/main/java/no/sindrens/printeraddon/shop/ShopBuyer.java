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
 * Two stacks or more at a time go through the buy screen's "purchase stacks" (several stacks in one purchase);
 * if that screen isn't understood, a stack at a time as before. Buys only what fits in the inventory. Stops when a
 * purchase doesn't arrive (not enough money).
 */
public class ShopBuyer {
    public static final ShopBuyer INSTANCE = new ShopBuyer();

    private static final int STABLE_TICKS = 5;
    /** How long a +amount click may take before it counts as "the amount can't go higher". */
    private static final int AMOUNT_TIMEOUT = 20;
    /** Amount clicks for one purchase at most, whatever the screen does (then what's set is bought). */
    private static final int MAX_AMOUNT_CLICKS = 40;
    private static final Pattern PLUS = Pattern.compile("^\\+\\s*(\\d+)$");
    private static final Pattern MINUS = Pattern.compile("^[-\u2212]\\s*(\\d+)$");
    private static final Pattern TIMES = Pattern.compile("^x\\s*(\\d+)$");

    private enum Phase { OPEN, WAIT_MENU, NAVIGATE, OPEN_ITEM, AMOUNT, WAIT_AMOUNT, PURCHASE, VERIFY, STACKS, SOLD, BACK, BACK_CHECK }

    private static final class Order {
        final Item item;
        final String id;
        int left;
        final ShopScanResult.Location location;
        /** Tries from the start (/shop again) after something didn't look right. */
        int retries;
        /** The stacks screen has nothing small enough for what's left of this one. */
        boolean noStacks;
        /** Bought of this order so far. */
        int got;
        /** The amount screen doesn't go this high (a + to it changed nothing), in items. */
        int below = Integer.MAX_VALUE;
        /** Amount clicks in a row that changed nothing. */
        int unchanged;
        /** A + changed nothing: going up by one from then on, to find the most the screen takes. */
        boolean probing;
        /** Amount clicks since the last purchase. */
        int amountClicks;
        /** Times the shop was opened again because the amount screen couldn't be lowered. */
        int reopened;

        /** Selling (all of it, shift + right click on it in the shop), not buying. */
        boolean sell;
        /** Bought by the whole stack only (its stack costs less than the setting), the amount a whole number of stacks. */
        boolean wholeStacks;

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
    /** Ticks this run waited for a lagging server without counting them (see ServerLag#hardLag). */
    private int lagHeld;
    @Nullable
    private String before;
    @Nullable
    private String lastSignature;
    private int stable;
    private int countBefore;
    private int lastAmount;
    private double spent;
    /** Sold in this run, and about what for. */
    private final Map<Item, Integer> sold = new LinkedHashMap<>();
    private double earned;
    @Nullable
    private String error;
    /** The "purchase stacks" screen didn't work out: a stack at a time for the rest of the session. */
    private static boolean stacksBroken;
    /** When buying by the stack last didn't work: tried again 10 minutes later (it was off for the whole game before). */
    private static long stacksBrokenAt;
    /** The amount screen had no stacks button: logged once. */
    private boolean loggedNoStacks;
    /** The last purchase went through the stacks screen (and its title, to know it again). */
    private boolean stacksPurchase;
    @Nullable
    private String stacksTitle;
    private boolean confirmTried;
    /**
     * The open amount screen is the stacks screen with +1/+16/+32 buttons that count stacks and "purchase item"
     * (the item shows the total, "x 64" for one stack), not one with a choice per number of stacks.
     */
    private boolean stacksScreen;
    /** The last amount click was a + (so no change means the screen can't go higher). */
    private boolean lastClickPlus;
    /** The number on the last amount button clicked (+16 is 16), and the items one step was worth then. */
    private int lastStep;
    private int lastUnit = 1;
    /** Items one step of the open amount screen's buttons is worth, as seen when a click changed it (0: not seen yet). */
    private int stepItems;
    /** What the server said in chat since the last purchase click (null: nothing). */
    @Nullable
    private String serverSaid;
    /** Server messages written to the build log in this run. */
    private int messagesLogged;
    /** The -N buttons seen on the normal buy screen and on the stacks screen (shown only once the amount is above 1). */
    private static final java.util.Set<Integer> MINUS_SEEN_NORMAL = new java.util.HashSet<>();
    private static final java.util.Set<Integer> MINUS_SEEN_STACKS = new java.util.HashSet<>();
    /** Every different screen the stacks button led to is written to the build log once. */
    private final java.util.Set<String> loggedScreens = new java.util.HashSet<>();
    /** "Back" clicks on the way to the next item's page (two at most: from the stacks screen to the buy screen to the page). */
    private int backClicks;

    /** Both on the same page of the same category: the second is a "Back" away from the first's buy screen. */
    private static boolean samePage(Order a, Order b) {
        return a.location.path != null && a.location.path.equals(b.location.path) && a.location.page == b.location.page;
    }

    /** Ticks while buying, and the last click (or shop command) at that count: the time between clicks counts from it. */
    private long ticks;
    private long lastClickTick = -1000;
    /** For the build log: the order being timed, when it began, its clicks and how often the shop was opened for it. */
    @Nullable
    private Order timedOrder;
    private long timedSince;
    private int timedClicks;
    private int timedOpens;

    public boolean isRunning() {
        return running;
    }

    /** Items bought in the last run. */
    public Map<Item, Integer> bought() {
        return bought;
    }

    /** Items from the last run that the shop doesn't sell. */
    /** Of the last list, the items the shop scan doesn't have at all (a new scan may find them). */
    private final List<Item> missingFromScan = new ArrayList<>();

    public List<Item> missingFromScan() {
        return missingFromScan;
    }

    /** A chat message from the server: written to the build log while buying, and read after a purchase click. */
    public void serverMessage(String text) {
        String line = text.strip();
        if (!running || line.isEmpty()) {
            return;
        }
        if (messagesLogged++ < 60) {
            no.sindrens.printeraddon.build.BuildLog.line("SHOP", "server: " + line);
        }
        serverSaid = line;
    }

    /**
     * Why the server turned the last purchase down, from what it said after the click: "money", "space" (a full
     * inventory), "limit" (a limit or cooldown), or null when it said nothing like that.
     */
    @Nullable
    private String refusal() {
        return refusalIn(serverSaid);
    }

    @Nullable
    static String refusalIn(@Nullable String said) {
        if (said == null) {
            return null;
        }
        String p = ShopText.plain(said).replace('’', '\'');
        if (p.contains("purchased") || p.contains("bought")) {
            return null;
        }
        // Said as a "no" (a broadcast like "Earn enough money by voting!" or "Limited time offer" isn't one)
        boolean no = p.contains("not ") || p.contains("n't") || p.contains("dont ") || p.contains("cannot") || p.contains("unable");
        boolean money = p.contains("money") || p.contains("funds") || p.contains("balance") || p.contains("coins") || p.contains("$");
        if (p.contains("insufficient") || (no && p.contains("afford")) || (no && p.contains("enough") && money)) {
            return "money";
        }
        if (p.contains("inventory") && (p.contains("is full") || (no && (p.contains("space") || p.contains("room"))))) {
            return "space";
        }
        if ((p.contains("limit") && (p.contains("reached") || no)) || p.contains("cooldown") || p.contains("too fast")) {
            return "limit";
        }
        return null;
    }

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
                cachedScan = load(file);
                cachedScanTime = modified;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        List<ShopScanResult.Location> locations = cachedScan.items.get(BuiltInRegistries.ITEM.getKey(item).toString());
        return locations != null && locations.stream().anyMatch(l -> l.buyPrice != null && l.buyable());
    }

    /**
     * A shop scan from its file, with the prices read again from the saved lore (a scan from before 0.9.10 took the
     * sell price of sell-only items as their buy price); saved back when that changed something.
     */
    public static ShopScanResult load(Path file) throws IOException {
        ShopScanResult scan = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), ShopScanResult.class);
        if (scan.rereadPrices()) {
            save(file, scan);
            no.sindrens.printeraddon.build.BuildLog.line("SHOP", "prices in the shop scan read again from its lore (sell-only items aren't bought any more)");
        }
        return scan;
    }

    private static void save(Path file, ShopScanResult scan) {
        try {
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(scan),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't save the shop scan {}", file, e);
        }
    }

    /**
     * Clicking the item in the shop didn't open a buy screen, twice: it can't be bought there (only sold). Written
     * into the scan, so it isn't tried again; what it's made of is bought instead (dust from redstone blocks).
     */
    private void markCantBuy(Minecraft mc, Order order) {
        // Only for now (not saved in the scan: a slow server once made a buyable item look like this); auto build
        // leaves it a few minutes and buys what it's made of meanwhile, if it can
        order.location.cantBuy = true;
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", order.location.name + " in " + order.location.category
                + " opened no buy screen, twice: left for a few minutes");
    }

    /** The lowest buy price of one of the item in this server's shop scan, or null if it's not sold. */
    @Nullable
    /**
     * Bought only by the whole stack, the amount rounded up: its stack costs less than "Whole stacks below" (mud,
     * dirt, observers; not blocks of diamond or iron). Setting an exact amount on the buy screen took many clicks.
     */
    public static boolean buysWholeStacks(Minecraft mc, Item item) {
        Double each = price(mc, item);
        return each != null && wholeStacks(item, each);
    }

    private static boolean wholeStacks(Item item, double each) {
        int stack = item.getDefaultMaxStackSize();
        return stack > 1 && each * stack < Configs.Shop.STACKS_BELOW.getIntegerValue();
    }

    public static Double price(Minecraft mc, Item item) {
        if (!sells(mc, item)) {
            return null;
        }
        ShopScanResult.Location location = cheapest(cachedScan.items.get(BuiltInRegistries.ITEM.getKey(item).toString()));
        if (location == null || location.buyPrice == null) {
            return null;
        }
        return location.buyPrice / Math.max(1, location.amountShown);
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
        return start(mc, list, Double.MAX_VALUE);
    }

    /** This run sells (see startSelling). */
    private boolean selling;

    /** What the shop pays for one, the most it pays anywhere in the scan; null if it doesn't buy it. */
    @Nullable
    public static Double sellPrice(Minecraft mc, Item item) {
        // (reads this server's scan, if there is one)
        sells(mc, item);
        if (cachedScan == null) {
            return null;
        }
        ShopScanResult.Location best = bestSale(cachedScan.items.get(BuiltInRegistries.ITEM.getKey(item).toString()));
        return best == null ? null : best.sellPrice / Math.max(1, best.amountShown);
    }

    @Nullable
    private static ShopScanResult.Location bestSale(@Nullable List<ShopScanResult.Location> locations) {
        if (locations == null) {
            return null;
        }
        ShopScanResult.Location best = null;
        for (ShopScanResult.Location location : locations) {
            if (location.sellPrice != null && location.sellPrice > 0 && (best == null || location.sellPrice > best.sellPrice)) {
                best = location;
            }
        }
        return best;
    }

    /**
     * Sells all of each of these items in the shop (shift + right click on it: "sell all"), where the shop pays the
     * most for it. Only for what the shop scan says it buys back. Returns false if nothing could be started.
     */
    public boolean startSelling(Minecraft mc, java.util.Collection<Item> items) {
        if (running || mc.player == null) {
            return false;
        }
        Path file = scanFile(mc);
        if (file == null) {
            return false;
        }
        try {
            shop = load(file);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        orders.clear();
        sold.clear();
        earned = 0;
        error = null;
        serverSaid = null;
        messagesLogged = 0;
        for (Item item : items) {
            ShopScanResult.Location location = bestSale(shop.items.get(BuiltInRegistries.ITEM.getKey(item).toString()));
            int count = count(mc, item);
            if (location != null && count > 0) {
                Order order = new Order(item, count, location);
                order.sell = true;
                orders.add(order);
            }
        }
        if (orders.isEmpty()) {
            return false;
        }
        running = true;
        lagHeld = 0;
        selling = true;
        phase = Phase.OPEN;
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", "selling " + describe(orders) + " (storage is full and the build doesn't need them)");
        Chat.info("Selling in /" + shop.shopCommand + " what the build doesn't need and storage has no room for: " + describe(orders)
                + ". Don't touch the menu.");
        return true;
    }

    /** What the last trip cost (from the scanned prices). */
    public double spent() {
        return spent;
    }

    /**
     * Starts buying the list, for at most {@code budget} (the spending limit's rest): each item is cut to what the
     * money still left pays for, in the list's order.
     */
    public boolean start(Minecraft mc, Map<Item, Integer> list, double budget) {
        if (running || mc.player == null) {
            return false;
        }
        orders.clear();
        bought.clear();
        notSold.clear();
        notBought.clear();
        sold.clear();
        spent = 0;
        earned = 0;
        error = null;
        serverSaid = null;
        messagesLogged = 0;

        Path file = scanFile(mc);
        if (file == null) {
            Chat.warn("No shop scan for this server yet: hold J and press S once, then auto buy works.");
            notBought.addAll(list.keySet());
            return false;
        }
        try {
            shop = load(file);
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.error("Couldn't read the shop scan {}", file, e);
            Chat.warn("Couldn't read the shop scan (" + e.getMessage() + "). Scan the shop again (J+S).");
            notBought.addAll(list.keySet());
            return false;
        }

        missingFromScan.clear();
        for (Map.Entry<Item, Integer> entry : list.entrySet()) {
            String id = BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
            List<ShopScanResult.Location> locations = shop.items.get(id);
            ShopScanResult.Location location = cheapest(locations);
            if (location == null) {
                notSold.add(entry.getKey());
                // Why: not in the scan at all (an old or broken scan: a new one may find it), or only sold there
                String why = locations == null || locations.isEmpty() ? "not in the shop scan"
                        : locations.stream().anyMatch(l -> Boolean.TRUE.equals(l.cantBuy)) ? "its buy screen didn't open"
                        : "only sold there (no buy price)";
                if (locations == null || locations.isEmpty()) {
                    missingFromScan.add(entry.getKey());
                }
                no.sindrens.printeraddon.build.BuildLog.line("SHOP", "can't buy " + names(List.of(entry.getKey())) + ": " + why
                        + " | the scan from " + shop.scannedAt + " has " + shop.categories.size() + " categories, " + shop.items.size() + " items");
            } else if (entry.getValue() > 0) {
                int count = entry.getValue();
                int stack = entry.getKey().getDefaultMaxStackSize();
                boolean whole = location.buyPrice != null && wholeStacks(entry.getKey(), location.buyPrice / Math.max(1, location.amountShown));
                if (whole) {
                    // Whole stacks, rounded up: the rest goes into storage for later
                    count = (count + stack - 1) / stack * stack;
                }
                if (budget < Double.MAX_VALUE && location.buyPrice != null && location.buyPrice > 0) {
                    double each = location.buyPrice / Math.max(1, location.amountShown);
                    int affordable = (int) Math.floor(budget / each);
                    if (whole) {
                        affordable = affordable / stack * stack;
                    }
                    if (affordable < count) {
                        no.sindrens.printeraddon.build.BuildLog.line("SHOP", String.format(java.util.Locale.ROOT, "%s: %d wanted, %d within the spending limit ($%,.0f left)",
                                names(List.of(entry.getKey())), count, affordable, budget));
                        count = affordable;
                    }
                    budget -= count * each;
                }
                if (count > 0) {
                    Order order = new Order(entry.getKey(), count, location);
                    order.wholeStacks = whole;
                    orders.add(order);
                } else {
                    notBought.add(entry.getKey());
                }
            }
        }
        if (!notSold.isEmpty()) {
            Chat.warn("Not sold in /" + shop.shopCommand + ": " + names(notSold) + (missingFromScan.isEmpty() ? ". Put them in your inventory."
                    : " (not in the shop scan: the shop is scanned again to look for it)."));
        }
        if (orders.isEmpty()) {
            return false;
        }
        // Items on the same page of the shop one after the other (a "Back" away from each other, see samePage); the
        // pages in the order they first come up
        List<Order> grouped = new ArrayList<>(orders);
        Map<String, Integer> firstSeen = new java.util.HashMap<>();
        for (int i = 0; i < grouped.size(); i++) {
            firstSeen.putIfAbsent(grouped.get(i).location.path + "|" + grouped.get(i).location.page, i);
        }
        grouped.sort(java.util.Comparator.comparingInt(o -> firstSeen.get(o.location.path + "|" + o.location.page)));
        orders.clear();
        orders.addAll(grouped);

        if (stacksBroken && no.sindrens.printeraddon.build.AutoBuild.clientTicks - stacksBrokenAt > 20L * 60 * 10) {
            stacksBroken = false;
            no.sindrens.printeraddon.build.BuildLog.line("SHOP", "buying by the stack is tried again (it didn't work 10 minutes ago)");
        }
        running = true;
        lagHeld = 0;
        phase = Phase.OPEN;
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", "buying " + describe(orders) + (stacksBroken ? " | a stack at a time (buying by the stack didn't work)" : ""));
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

    /**
     * On the stacks screen, whole stacks only: when more than half a stack would be left to buy on the normal screen
     * afterwards, one stack more, if there's room and the whole build still needs that many.
     */
    private static boolean wantedOneStackMore(Minecraft mc, Order order, int want, int room, int most) {
        int stack = order.item.getDefaultMaxStackSize();
        int rest = Math.min(order.left, room) - want;
        if (order.sell || rest < stack / 2 || want + stack > Math.min(room, most)) {
            return false;
        }
        int have = mc.player == null ? 0 : mc.player.getInventory().countItem(order.item);
        return no.sindrens.printeraddon.build.AutoBuild.INSTANCE.wholeCount(order.item) >= have + want + stack;
    }

    public void tick(Minecraft mc) {
        if (!running || mc.player == null || mc.gameMode == null) {
            return;
        }
        ticks++;
        if (delay > 0) {
            delay--;
            return;
        }
        Order order = orders.peekFirst();
        if (order == null) {
            finish(mc);
            return;
        }
        // A menu late because the server lags hard isn't a failure: the wait holds (up to a minute a run); a lag spike
        // failed all three items of a trip, and the build stopped for sand
        if (no.sindrens.printeraddon.build.ServerLag.hardLag(mc) && lagHeld < no.sindrens.printeraddon.build.ServerLag.MOST_LAG_HELD) {
            if (lagHeld++ == 0) {
                no.sindrens.printeraddon.build.BuildLog.line("SHOP", "the server lags: waiting for its menus without counting");
            }
            return;
        }
        waited++;

        switch (phase) {
            case OPEN -> {
                if (!order.sell && (room(mc, order.item) <= 0 || order.wholeStacks && room(mc, order.item) < order.item.getDefaultMaxStackSize())) {
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
                if (timedOrder != order) {
                    timedOrder = order;
                    timedSince = ticks;
                    timedClicks = 0;
                    timedOpens = 0;
                }
                timedOpens++;
                mc.player.connection.sendCommand(shop.shopCommand);
                lastClickTick = ticks;
                stacksScreen = false;
                stepItems = 0;
                // A fresh buy screen: the most it takes (if found) is kept, the clicks start over
                order.amountClicks = 0;
                order.probing = false;
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
                    delay = delaySinceClick();
                } else if (waited > timeout() + 2 * no.sindrens.printeraddon.build.ServerLag.pingTicks(mc)) {
                    if (next == Phase.STACKS) {
                        // The stacks button did nothing: not the end of this purchase, a stack at a time instead
                        stacksFailed(mc, order, "nothing happened when purchase stacks was clicked");
                    } else if (next == Phase.AMOUNT && order.retries >= 1) {
                        // Clicking the item opened nothing, twice: it can't be bought here
                        markCantBuy(mc, order);
                        fail(order, "clicking it opens no buy screen (it can only be sold there)");
                    } else if (order.retries++ < 1) {
                        clickLost();
                        // A slow server (or a lost click): once more from the start before giving up on it
                        logScreen("no change after a click, buying " + order.location.name + " (trying again from the start)", ScreenSnapshot.capture(mc));
                        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
                            mc.player.closeContainer();
                        }
                        phase = Phase.OPEN;
                        waited = 0;
                        delay = 20 + clickDelay();
                    } else {
                        fail(order, "the shop menu didn't open or change");
                    }
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
                if (order.sell) {
                    // Shift + right click on it: "sell all" (what the shop's own text says)
                    countBefore = count(mc, order.item);
                    serverSaid = null;
                    no.sindrens.printeraddon.build.BuildLog.line("SHOP", "selling " + countBefore + " " + order.location.name + " in " + order.location.category
                            + " (shift + right click)");
                    mc.gameMode.handleContainerInput(snapshot.containerId(), slot, 1, ContainerInput.QUICK_MOVE, mc.player);
                    phase = Phase.SOLD;
                    waited = 0;
                    return;
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
                int room = room(mc, order.item);
                int stack = order.item.getDefaultMaxStackSize();
                // A stack or more still to buy (and room for it): whole stacks in one purchase (a stack the normal way
                // is 49 with the +32/+16 buttons and then 15 clicks on +1)
                if (!stacksScreen && !stacksBroken && !order.noStacks && Math.min(order.left, room) >= stack && stack > 1) {
                    Integer stacks = stacksButton(snapshot);
                    if (stacks != null) {
                        PrinterAddon.debug("Buying {} by the stack ({} wanted, room for {})", order.location.name, order.left, room);
                        click(mc, stacks, Phase.STACKS);
                        return;
                    }
                    if (!loggedNoStacks) {
                        loggedNoStacks = true;
                        logScreen("buy screen for " + order.location.name + " (no purchase stacks button found)", snapshot);
                    }
                }
                int amount = shownAmount(snapshot, order.id, stack, stacksScreen);
                // On the stacks screen only whole stacks are bought, and its +N buttons add N stacks
                int unit = stepItems > 0 ? stepItems : stacksScreen ? stack : 1;
                // The most this screen takes (64 on the normal one; lower once a + changed nothing)
                int most = Math.min(order.below - 1, stacksScreen ? Integer.MAX_VALUE : 64);
                int want = stacksScreen ? Math.min(Math.min(order.left, room), most) / stack * stack
                        : Math.max(1, Math.min(most, Math.min(order.left, room)));
                if (stacksScreen && !order.wholeStacks && wantedOneStackMore(mc, order, want, room, most)) {
                    // Most of a stack would be left over: one stack more now (the build uses them later) instead
                    // of another purchase for the rest (a log had 6 stacks, then 33, then 27: two trips more, 12 s)
                    want += stack;
                }
                if (stacksScreen && want < stack && order.wholeStacks) {
                    // Whole stacks only: no room for another one (the rest next time), never a few on the normal screen
                    orders.pollFirst();
                    mc.player.closeContainer();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = clickDelay();
                    return;
                }
                if (stacksScreen && want < stack) {
                    // Less than a stack left to buy (or room for): the rest on the normal buy screen
                    order.noStacks = true;
                    mc.player.closeContainer();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = clickDelay();
                    return;
                }
                // -N buttons seen on this kind of screen: they show up again once it's above 1
                java.util.Set<Integer> minusSeen = stacksScreen ? MINUS_SEEN_STACKS : MINUS_SEEN_NORMAL;
                minusSeen.addAll(buttonSteps(snapshot, MINUS).keySet());
                AmountStep next = amountStep(snapshot, amount, want, unit, most, minusSeen);
                Integer button = next == null ? null : next.slot();
                // Many clicks for the last few aren't worth it: buy what's set, the rest next time. A click is a
                // fraction of a second, another purchase several seconds: only when it's really many
                if (!stacksScreen && next != null && amount < want && amount >= 16 && next.clicks() > 12) {
                    button = null;
                }
                if (order.probing && amount < want) {
                    // A + went past what the screen takes: +1 at a time from here, to find where it stops
                    button = buttonSteps(snapshot, PLUS).get(1);
                }
                if (order.unchanged > 3 || order.amountClicks >= MAX_AMOUNT_CLICKS) {
                    // Clicks keep changing nothing (or far too many): buy what's set (or start over if that's too much)
                    order.unchanged = 0;
                    button = null;
                }
                if (button == null && amount > want) {
                    // Can't get it down to what's wanted here: a fresh buy screen (it starts at 1), never more than asked
                    minusSeen.clear();
                    if (++order.reopened > 2) {
                        if (!stacksScreen) {
                            fail(order, "its buy screen can't be set lower than " + amount);
                            return;
                        }
                        order.noStacks = true;
                    }
                    PrinterAddon.debug("The buy screen for {} is at {} and can't go down to {}: opening it again", order.location.name, amount, want);
                    mc.player.closeContainer();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = clickDelay();
                    return;
                }
                if (button != null) {
                    order.amountClicks++;
                    lastAmount = amount;
                    lastStep = stepOf(snapshot, button);
                    lastClickPlus = ShopText.plain(itemAt(snapshot, button).name()).startsWith("+");
                    lastUnit = unit;
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
                int stack = order.item.getDefaultMaxStackSize();
                int amount = snapshot == null ? lastAmount : shownAmount(snapshot, order.id, stack, stacksScreen);
                if (amount != lastAmount) {
                    // What one step is worth here, as seen: an item, or a stack on the stacks screen
                    int change = Math.abs(amount - lastAmount);
                    if (lastStep > 0 && (change == lastStep || change == lastStep * stack)) {
                        stepItems = change / lastStep;
                    } else if (lastClickPlus && amount > lastAmount && amount - lastAmount < lastStep * lastUnit) {
                        // A + that stopped short: this is the most the screen takes
                        order.below = Math.min(order.below, amount + 1);
                        order.probing = false;
                        PrinterAddon.debug("The buy screen for {} stops at {}", order.location.name, amount);
                    }
                    order.unchanged = 0;
                    phase = Phase.AMOUNT;
                    delay = delaySinceClick();
                } else if (waited > AMOUNT_TIMEOUT + 2 * no.sindrens.printeraddon.build.ServerLag.pingTicks(mc)) {
                    // Nothing changed: a + that goes past what the screen takes (or a lost click). From here up one
                    // step at a time to find where it stops; buy what's set if it keeps not changing
                    if (lastClickPlus) {
                        order.below = Math.min(order.below, lastAmount + lastStep * lastUnit);
                        // A +1 that changed nothing found the most; after a bigger one, look for it by +1
                        order.probing = lastStep > 1;
                        PrinterAddon.debug("The buy screen for {} doesn't go from {} to {}", order.location.name, lastAmount, lastAmount + lastStep * lastUnit);
                    }
                    order.unchanged++;
                    phase = Phase.AMOUNT;
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
                order.amountClicks = 0;
                int stack = order.item.getDefaultMaxStackSize();
                lastAmount = shownAmount(snapshot, order.id, stack, stacksScreen);
                if (stacksScreen) {
                    // Checked like a purchase by the stack (a confirm screen once, a fall-back if nothing arrives)
                    stacksPurchase = true;
                    stacksTitle = snapshot.title();
                    confirmTried = false;
                    PrinterAddon.debug("Buying {} {} by the stack ({} stacks)", lastAmount, order.location.name, lastAmount / stack);
                }
                serverSaid = null;
                clickSlot(mc, snapshot, button);
                phase = Phase.VERIFY;
                waited = 0;
            }
            case STACKS -> {
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                if (snapshot == null) {
                    stacksFailed(mc, order, "the menu closed after clicking purchase stacks");
                    return;
                }
                logScreen("stacks screen for " + order.location.name, snapshot);
                if (isStacksAmountScreen(snapshot)) {
                    // Set like the normal buy screen, its +N adding N stacks, then "purchase item" (clicking the
                    // item there does nothing)
                    if (!stacksScreen) {
                        stacksScreen = true;
                        stepItems = 0;
                    }
                    stacksTitle = snapshot.title();
                    phase = Phase.AMOUNT;
                    return;
                }
                int stack = order.item.getDefaultMaxStackSize();
                int want = Math.min(order.left, room(mc, order.item));
                int bestSlot = -1;
                int bestAmount = 0;
                int options = 0;
                for (ScreenSnapshot.Item item : snapshot.items()) {
                    if (!item.itemId().equals(order.id)) {
                        continue;
                    }
                    int amount = stacksAmount(item, stack);
                    if (amount < stack) {
                        continue;
                    }
                    options++;
                    if (amount <= want && amount > bestAmount) {
                        bestAmount = amount;
                        bestSlot = item.slot();
                    }
                }
                if (options == 0) {
                    stacksFailed(mc, order, "no stack amounts found on the stacks screen");
                    return;
                }
                if (bestSlot < 0) {
                    // Even the smallest is more than wanted (or fits): a stack at a time for the rest of this item
                    PrinterAddon.debug("Every amount on the stacks screen is more than the {} wanted: buying normally", want);
                    order.noStacks = true;
                    mc.player.closeContainer();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = clickDelay();
                    return;
                }
                stacksTitle = snapshot.title();
                stacksPurchase = true;
                confirmTried = false;
                countBefore = count(mc, order.item);
                lastAmount = bestAmount;
                PrinterAddon.debug("Buying {} {} by the stack (slot {})", bestAmount, order.location.name, bestSlot);
                serverSaid = null;
                clickSlot(mc, snapshot, bestSlot);
                phase = Phase.VERIFY;
                waited = 0;
            }
            case BACK -> {
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                ScreenSnapshot.Item back = snapshot == null ? null : snapshot.items().stream()
                        .filter(i -> i.slot() < snapshot.size() && ShopText.navOf(i.name()) == ShopText.Nav.BACK).findFirst().orElse(null);
                if (back == null || backClicks++ >= 2) {
                    // No way back from here (or it didn't lead to the page): the shop from the start
                    no.sindrens.printeraddon.build.BuildLog.line("SHOP", "no way back to the page of " + order.location.name
                            + (back == null ? " (no Back button)" : "") + ": opening the shop again");
                    phase = Phase.OPEN;
                    return;
                }
                click(mc, back.slot(), Phase.BACK_CHECK);
            }
            case BACK_CHECK -> {
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                ScreenSnapshot.Item item = snapshot == null ? null : itemAt(snapshot, order.location.slot);
                if (item != null && item.itemId().equals(order.id) && !snapshot.looksLikeBuyScreen()) {
                    // Its page: on as if the shop had just been opened and gone through to it
                    stacksScreen = false;
                    stepItems = 0;
                    order.amountClicks = 0;
                    order.probing = false;
                    if (timedOrder != order) {
                        timedOrder = order;
                        timedSince = ticks;
                        timedClicks = 0;
                        timedOpens = 0;
                    }
                    phase = Phase.OPEN_ITEM;
                    waited = 0;
                    return;
                }
                // A screen between (the buy screen after the stacks screen): back once more
                phase = Phase.BACK;
            }
            case SOLD -> {
                int gone = countBefore - count(mc, order.item);
                if (gone > 0 && (count(mc, order.item) == 0 || waited > 10 + 2 * no.sindrens.printeraddon.build.ServerLag.pingTicks(mc))) {
                    sold.merge(order.item, gone, Integer::sum);
                    if (order.location.sellPrice != null) {
                        earned += gone * order.location.sellPrice / Math.max(1, order.location.amountShown);
                    }
                    no.sindrens.printeraddon.build.BuildLog.line("SHOP", "sold " + gone + " " + order.location.name);
                    orders.pollFirst();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = clickDelay();
                } else if (gone == 0 && refusal() != null) {
                    fail(order, "the server said \"" + serverSaid + "\"");
                } else if (waited > timeout()) {
                    fail(order, "nothing was sold" + (serverSaid != null ? "; the server said \"" + serverSaid + "\"" : ""));
                }
            }
            case VERIFY -> {
                int got = count(mc, order.item) - countBefore;
                // A purchase by the stack may ask to confirm first: once
                if (got == 0 && stacksPurchase && !confirmTried && waited > 10 + no.sindrens.printeraddon.build.ServerLag.pingTicks(mc)) {
                    confirmTried = true;
                    ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                    Integer confirm = snapshot == null || snapshot.title().equals(stacksTitle) ? null : purchaseButton(snapshot);
                    if (snapshot != null) {
                        logScreen("after choosing the stacks", snapshot);
                    }
                    if (confirm != null) {
                        PrinterAddon.debug("Confirming the purchase by the stack");
                        clickSlot(mc, snapshot, confirm);
                        waited = 0;
                        return;
                    }
                }
                // All of it, or what came (a big purchase may arrive over a few ticks)
                if (got > 0 && (got >= lastAmount || waited > 10 + 2 * no.sindrens.printeraddon.build.ServerLag.pingTicks(mc))) {
                    order.left -= got;
                    order.got += got;
                    bought.merge(order.item, got, Integer::sum);
                    if (order.location.buyPrice != null) {
                        spent += got * order.location.buyPrice / Math.max(1, order.location.amountShown);
                    }
                    PrinterAddon.debug("Bought {} {}{}", got, order.location.name, stacksPurchase ? " (by the stack)" : "");
                    purchaseWentThrough(order.retries == 0 && order.unchanged == 0);
                    boolean byStack = stacksPurchase;
                    stacksPurchase = false;
                    // The last few after a big purchase aren't worth another one (setting the amount took 10 s for 7)
                    boolean tail = order.left > 0 && order.left < 16 && order.got >= 48;
                    if (tail) {
                        PrinterAddon.debug("Leaving the last {} {}: not worth another purchase", order.left, order.location.name);
                    }
                    if (order.left <= 0 || tail || room(mc, order.item) <= 0) {
                        orders.pollFirst();
                        phase = Phase.OPEN;
                        logTiming(order);
                        // The next one on the same page of the shop: back there (its "Back" button) instead of
                        // closing and opening the shop again from the start
                        Order nextOrder = orders.peekFirst();
                        if (nextOrder != null && !nextOrder.sell && samePage(order, nextOrder)) {
                            phase = Phase.BACK;
                            backClicks = 0;
                        }
                    } else {
                        // Buy screens usually stay open: set the amount again (or pick stacks again)
                        ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                        if (byStack && snapshot != null && snapshot.title().equals(stacksTitle)) {
                            phase = Phase.STACKS;
                        } else {
                            phase = snapshot != null && snapshot.looksLikeBuyScreen() ? Phase.AMOUNT : Phase.OPEN;
                        }
                    }
                    waited = 0;
                    delay = delaySinceClick();
                } else if (got == 0 && refusal() != null) {
                    // The server said why not: no waiting, no trying again
                    stacksPurchase = false;
                    String why = "the server said \"" + serverSaid + "\"";
                    if (refusal().equals("money")) {
                        fail(order, "out of money (" + why + ")");
                        orders.forEach(o -> notBought.add(o.item));
                        orders.clear();
                    } else {
                        fail(order, why);
                    }
                } else if (waited > timeout() && stacksPurchase) {
                    // Nothing arrived by the stack: a stack at a time from now on (from the start)
                    stacksPurchase = false;
                    stacksFailed(mc, order, "nothing arrived after choosing " + lastAmount + " by the stack"
                            + (serverSaid != null ? "; the server said \"" + serverSaid + "\"" : ""));
                } else if (waited > timeout() && order.retries++ < 1) {
                    // Nothing arrived: once more from the start (the first purchase after opening sometimes gets lost)
                    PrinterAddon.debug("The purchase of {} didn't arrive: trying once more", order.location.name);
                    mc.player.closeContainer();
                    phase = Phase.OPEN;
                    waited = 0;
                    delay = 20 + clickDelay();
                } else if (waited > timeout()) {
                    // Nothing arrived again. Stop, don't keep clicking.
                    Double balance = no.sindrens.printeraddon.build.CostEstimate.balance(mc);
                    fail(order, "the purchase of " + lastAmount + " " + order.location.name + " didn't arrive"
                            + (balance != null ? String.format(java.util.Locale.ROOT, " (balance $%,.0f)", balance) : " (not enough money?)")
                            + (serverSaid != null ? "; the server said \"" + serverSaid + "\"" : ""));
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
            if (order != null && order.retries++ < 1) {
                // Closed (by the player, by accident, or the server): opened again from the start once
                no.sindrens.printeraddon.build.BuildLog.line("SHOP", "the shop menu closed while buying " + order.location.name + ": opening it again");
                phase = Phase.OPEN;
                waited = 0;
                delay = 20 + clickDelay();
            } else if (order != null) {
                fail(order, "the shop menu closed");
            }
            return;
        }
        clickSlot(mc, snapshot, slot);
        await(snapshot.signature(), then);
    }

    private void clickSlot(Minecraft mc, ScreenSnapshot snapshot, int slot) {
        mc.gameMode.handleContainerInput(snapshot.containerId(), slot, 0, ContainerInput.PICKUP, mc.player);
        lastClickTick = ticks;
        timedClicks++;
    }

    /**
     * The wait before the next click: the time between clicks counts from the last click, not from when the menu had
     * settled (that waited twice: the menu's settling and then the whole time between clicks again, about 0.8 s a
     * click; a purchase was 8 to 10 s).
     */
    private int delaySinceClick() {
        return Math.max(0, clickDelay() - (int) Math.min(Integer.MAX_VALUE, ticks - lastClickTick));
    }

    /** One line per item bought, in the build log: how long it took and what for. */
    private void logTiming(Order order) {
        if (timedOrder != order) {
            return;
        }
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", String.format(java.util.Locale.ROOT,
                "%d %s in %.1f s: %d clicks, the shop opened %d time%s, %d ticks between clicks at least",
                order.got, order.location.name, (ticks - timedSince) / 20.0, timedClicks, timedOpens, timedOpens == 1 ? "" : "s", clickDelay()));
        no.sindrens.printeraddon.build.RunLog.purchase(order.item, order.got,
                order.got * order.location.buyPrice / Math.max(1, order.location.amountShown), timedClicks, ticks - timedSince);
        timedOrder = null;
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
        logScreen("couldn't buy " + order.location.name + " (" + why + "), the screen", ScreenSnapshot.capture(Minecraft.getInstance()));
        skip(order, why);
    }

    private void finish(Minecraft mc) {
        running = false;
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        if (selling) {
            selling = false;
            if (!sold.isEmpty()) {
                StringBuilder sb = new StringBuilder("Sold ");
                sold.forEach((item, n) -> sb.append(n).append(' ').append(new ItemStack(item).getHoverName().getString()).append(", "));
                String text = sb.substring(0, sb.length() - 2) + (earned > 0 ? String.format(java.util.Locale.ROOT, " (about $%,.2f)", earned) : "");
                Chat.info(text);
                no.sindrens.printeraddon.build.BuildLog.line("SHOP", text);
            } else if (error == null) {
                Chat.warn("Nothing was sold.");
            }
            return;
        }
        if (!bought.isEmpty()) {
            StringBuilder sb = new StringBuilder("Bought ");
            bought.forEach((item, n) -> sb.append(n).append(' ').append(new ItemStack(item).getHoverName().getString()).append(", "));
            String text = sb.substring(0, sb.length() - 2);
            Chat.info(spent > 0 ? text + String.format(java.util.Locale.ROOT, " (about $%,.2f)", spent) : text);
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
            if (!location.buyable()) {
                continue;
            }
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

    /**
     * The amount the open buy screen is set to, in items. The stacks screen shows the total under its item ("x 64"
     * for one stack) and the number of stacks as the item's count: whichever says more.
     */
    static int shownAmount(ScreenSnapshot snapshot, String itemId, int stack, boolean stacksScreen) {
        int shown = amountShown(snapshot, itemId);
        if (!stacksScreen) {
            return shown;
        }
        for (ScreenSnapshot.Item item : snapshot.items()) {
            if (item.itemId().equals(itemId)) {
                return Math.max(shown, item.count() * stack);
            }
        }
        return shown;
    }

    /** The first click on the way to the amount wanted, and how many clicks that way takes in all. */
    record AmountStep(int slot, int clicks) {
    }

    /**
     * The first click of the shortest way from {@code amount} to {@code want} (in items, each step of the buttons
     * worth {@code unit}: a stack on the stacks screen), never past {@code most}. Going past what's wanted and back
     * down is used when -N buttons are on the screen, or were seen on it before ({@code minusSeen}: they only show up
     * above 1): 15 stacks is +16 -1 -1, not fourteen +1. Null when it's set, or can't be reached from here.
     */
    @Nullable
    static AmountStep amountStep(ScreenSnapshot snapshot, int amount, int want, int unit, int most, java.util.Set<Integer> minusSeen) {
        int from = Math.max(1, amount / unit);
        int to = want / unit;
        if (from == to || to < 1) {
            return null;
        }
        Map<Integer, Integer> plus = buttonSteps(snapshot, PLUS);
        Map<Integer, Integer> minus = buttonSteps(snapshot, MINUS);
        java.util.Set<Integer> laterMinus = new java.util.TreeSet<>(minus.keySet());
        laterMinus.addAll(minusSeen);
        int top = Math.max(from, Math.min(Math.max(from, to) + 64, most / unit));
        if (to > top) {
            return null;
        }
        // Breadth-first over the amounts: firstStep[v] is the first click of the shortest way to v (+16, -1)
        int[] firstStep = new int[top + 1];
        int[] clicks = new int[top + 1];
        boolean[] seen = new boolean[top + 1];
        Deque<Integer> queue = new ArrayDeque<>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int v = queue.poll();
            List<Integer> moves = new ArrayList<>(plus.keySet());
            // Only buttons on the screen now for the first click; the -N ones seen before for the rest
            for (int d : v == from ? minus.keySet() : laterMinus) {
                moves.add(-d);
            }
            for (int move : moves) {
                int w = v + move;
                if (w < 1 || w > top || seen[w]) {
                    continue;
                }
                seen[w] = true;
                firstStep[w] = v == from ? move : firstStep[v];
                clicks[w] = clicks[v] + 1;
                if (w == to) {
                    Integer slot = firstStep[w] > 0 ? plus.get(firstStep[w]) : minus.get(-firstStep[w]);
                    return slot == null ? null : new AmountStep(slot, clicks[w]);
                }
                queue.add(w);
            }
        }
        return null;
    }

    /** The +N (or -N) buttons on the screen: N to its slot. */
    private static Map<Integer, Integer> buttonSteps(ScreenSnapshot snapshot, Pattern pattern) {
        Map<Integer, Integer> steps = new java.util.TreeMap<>();
        for (ScreenSnapshot.Item item : snapshot.items()) {
            Matcher m = pattern.matcher(ShopText.plain(item.name()));
            if (m.find()) {
                int step = Integer.parseInt(m.group(1));
                if (step > 0) {
                    steps.putIfAbsent(step, item.slot());
                }
            }
        }
        return steps;
    }

    /** A stacks screen set like the buy screen: +N buttons and "purchase item" (this server), not a choice per amount. */
    static boolean isStacksAmountScreen(ScreenSnapshot snapshot) {
        return purchaseButton(snapshot) != null && plusButton(snapshot, Integer.MAX_VALUE, PLUS) != null;
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

    /** The buy screen's "purchase stacks" (or "buy stacks") button, if it has one. */
    @Nullable
    private static Integer stacksButton(ScreenSnapshot snapshot) {
        for (ScreenSnapshot.Item item : snapshot.items()) {
            String name = ShopText.plain(item.name());
            if (name.contains("stack") && (name.contains("purchase") || name.contains("buy"))) {
                return item.slot();
            }
        }
        return null;
    }

    private static final Pattern STACKS_TEXT = Pattern.compile("(\\d+)\\s*(?:x\\s*)?stacks?\\b");
    private static final Pattern ITEMS_TEXT = Pattern.compile("(?:^|\\s)x\\s*(\\d+)|(\\d+)\\s*(?:items|pcs)");

    /**
     * How many items one choice on the stacks screen buys: "3 stacks" (in the name or the text under it), "x 192",
     * else the number the item shows counts as stacks.
     */
    private static int stacksAmount(ScreenSnapshot.Item item, int stack) {
        List<String> texts = new ArrayList<>();
        texts.add(ShopText.plain(item.name()));
        for (String line : item.lore()) {
            texts.add(ShopText.plain(line));
        }
        for (String text : texts) {
            Matcher m = STACKS_TEXT.matcher(text);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) * stack;
            }
        }
        for (String text : texts) {
            Matcher m = ITEMS_TEXT.matcher(text);
            if (m.find()) {
                return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        return item.count() * stack;
    }

    /** The stacks screen didn't work out: a stack at a time for the rest of the session, starting over for this one. */
    private void stacksFailed(Minecraft mc, Order order, String why) {
        stacksBroken = true;
        stacksScreen = false;
        stacksBrokenAt = no.sindrens.printeraddon.build.AutoBuild.clientTicks;
        Chat.warn("Buying by the stack didn't work (" + why + "): buying a stack at a time. The build log has what the screen showed.");
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", "buying by the stack stopped: " + why);
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        phase = Phase.OPEN;
        waited = 0;
        delay = 20 + clickDelay();
    }

    /** A shop screen in the build log, every slot with its text (once per different screen). */
    private void logScreen(String what, @Nullable ScreenSnapshot snapshot) {
        if (snapshot == null) {
            no.sindrens.printeraddon.build.BuildLog.line("SHOP", what + ": no menu open");
            return;
        }
        if (!loggedScreens.add(what + "|" + snapshot.title() + "|" + snapshot.items().size())) {
            return;
        }
        StringBuilder sb = new StringBuilder(what + ": \"" + snapshot.title() + "\", " + snapshot.size() + " slots:");
        for (ScreenSnapshot.Item item : snapshot.items()) {
            if (item.isFiller()) {
                continue;
            }
            sb.append(" [").append(item.slot()).append(' ').append(item.count()).append(' ').append(item.itemId().replace("minecraft:", ""))
                    .append(" \"").append(ShopText.plain(item.name())).append('"');
            if (!item.lore().isEmpty()) {
                sb.append(" lore: ").append(String.join(" / ", item.lore().stream().map(ShopText::plain).toList()));
            }
            sb.append(']');
        }
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", sb.toString());
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

    /**
     * Between clicks: 8 ticks at first, plus half the ping (servers drop clicks that come too fast). Learned: after
     * purchases that went through without a lost click, a tick less each time, down to 4 (a click took close to a
     * second, a purchase 6 s); the first lost click puts it back to 8 for the rest of the session, and each one after
     * that 2 more (up to 16).
     */
    private static int learnedDelay = 8;
    private static boolean delaySettled;
    private static int cleanPurchases;

    private static void purchaseWentThrough(boolean clean) {
        if (!clean || delaySettled || learnedDelay <= 4 || ++cleanPurchases < 2) {
            return;
        }
        cleanPurchases = 0;
        learnedDelay--;
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", "the shop keeps up: a click every " + learnedDelay + " ticks now");
    }

    private static void clickLost() {
        cleanPurchases = 0;
        int was = learnedDelay;
        learnedDelay = was < 8 ? 8 : Math.min(16, was + 2);
        delaySettled = true;
        no.sindrens.printeraddon.build.BuildLog.line("SHOP", "a click got no answer at " + was
                + " ticks between clicks: " + learnedDelay + " for this session");
    }

    private static int clickDelay() {
        return Math.max(learnedDelay, Configs.Speed.shopClickDelayTicks()) + no.sindrens.printeraddon.build.ServerLag.pingTicks(Minecraft.getInstance()) / 2;
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
