package no.sindrens.printeraddon.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;
import no.sindrens.printeraddon.util.ServerName;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads the whole /shop: opens every category, goes through every page, and saves where each item is and
 * what it costs. It never clicks an item that shows a price or a button that says buy/sell/confirm, except
 * once at the end (if enabled) to read the buy screen, which it then closes without confirming.
 */
public class ShopScanner {
    public static final ShopScanner INSTANCE = new ShopScanner();

    /** How many clicks deep (from the main menu) to look for sub-categories. */
    private static final int MAX_DEPTH = 3;
    /** A menu must look the same for this many ticks before it's read (items arrive after the window). */
    private static final int STABLE_TICKS = 3;
    /** Some servers close and reopen the window when changing page: allow a short gap. */
    private static final int CLOSED_GRACE_TICKS = 10;
    /** Never click buttons with these words: they might buy, sell or confirm something. */
    private static final List<String> UNSAFE_WORDS = List.of("sell", "buy", "purchase", "confirm");

    private enum Status { RUNNING, DONE, FAILED, CLOSED }

    private final Deque<Step> steps = new ArrayDeque<>();
    private final Deque<ShopScanResult.Category> pendingCategories = new ArrayDeque<>();
    private final Set<String> seenMenus = new HashSet<>();

    private boolean running;
    private boolean probeDone;
    private ShopScanResult result;
    @Nullable
    private ScreenSnapshot current;
    @Nullable
    private String failReason;
    @Nullable
    private Probe cheapest;

    /** The cheapest item found, and how to get back to it. */
    private record Probe(List<Integer> path, List<Integer> nextSlots, int slot, String name, double price) {
    }

    public boolean isRunning() {
        return running;
    }

    public void start(Minecraft mc) {
        if (running) {
            Chat.info("A shop scan is already running.");
            return;
        }

        steps.clear();
        pendingCategories.clear();
        seenMenus.clear();
        probeDone = !Configs.Shop.PROBE_BUY_SCREEN.getBooleanValue();
        cheapest = null;
        current = null;

        result = new ShopScanResult();
        result.server = ServerName.current(mc);
        result.scannedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        result.shopCommand = Configs.Shop.SHOP_COMMAND.getStringValue();

        steps.add(new OpenShop());
        steps.add(new Run(this::readMainMenu));
        steps.add(new Boundary());

        running = true;
        Chat.info("Scanning /" + result.shopCommand + "... don't touch the menu. Press the stop key to cancel.");
    }

    public void cancel(@Nullable String reason) {
        if (!running) {
            return;
        }
        running = false;
        steps.clear();
        pendingCategories.clear();

        if (reason != null) {
            result.notes.add("Scan stopped early: " + reason);
            Path file = save();
            Chat.warn("Shop scan stopped: " + reason + "." + (file != null ? " What was found so far is saved." : ""));
        }
    }

    public void tick(Minecraft mc) {
        if (!running) {
            return;
        }

        if (steps.isEmpty() && !planNext()) {
            finish(mc);
            return;
        }

        Step step = steps.peekFirst();
        Status status = step.tick(mc);

        switch (status) {
            case RUNNING -> {
            }
            case DONE -> steps.remove(step);
            case CLOSED -> cancel("the shop menu was closed");
            case FAILED -> {
                result.notes.add(failReason);
                PrinterAddon.LOGGER.info("Shop scan: {}", failReason);
                // Skip the rest of this category
                Step removed;
                do {
                    removed = steps.pollFirst();
                } while (removed != null && !(removed instanceof Boundary));
            }
        }
    }

    /** Queues the next category, or the buy screen check. Returns false when everything is done. */
    private boolean planNext() {
        ShopScanResult.Category category = pendingCategories.pollFirst();

        if (category != null) {
            steps.add(new OpenShop());
            for (int slot : category.path) {
                steps.add(new Click(slot, "open " + category.name));
            }
            steps.add(new Run(snapshot -> enterCategory(category, snapshot)));
            steps.add(new Boundary());
            return true;
        }

        if (!probeDone) {
            probeDone = true;
            if (cheapest == null) {
                result.notes.add("No item with a buy price was found, so the buy screen wasn't checked.");
                return true;
            }

            Probe probe = cheapest;
            steps.add(new OpenShop());
            for (int slot : probe.path()) {
                steps.add(new Click(slot, "open the category of " + probe.name()));
            }
            for (int slot : probe.nextSlots()) {
                steps.add(new Click(slot, "go to the page of " + probe.name()));
            }
            steps.add(new ProbeClick(probe.slot(), probe.name()));
            steps.add(new Run(snapshot -> {
                if (snapshot != null) {
                    result.buyScreen = ShopScanResult.menuOf(snapshot);
                    result.buyScreenProbedWith = probe.name();
                }
            }));
            steps.add(new CloseMenu());
            steps.add(new Boundary());
            return true;
        }

        return false;
    }

    private void readMainMenu(ScreenSnapshot snapshot) {
        result.mainMenu = ShopScanResult.menuOf(snapshot);
        seenMenus.add(snapshot.contentSignature());

        ShopScanResult.Category main = new ShopScanResult.Category();
        main.name = "Main menu";

        if (snapshot.hasPricedItems()) {
            // The main menu itself sells items
            result.categories.add(main);
            readPage(main, 0, snapshot, List.of());
        } else {
            queueSubMenus(main, snapshot);
        }

        Chat.info("Main menu '" + snapshot.title() + "': " + pendingCategories.size() + " categories to check.");
    }

    private void enterCategory(ShopScanResult.Category category, ScreenSnapshot snapshot) {
        if (snapshot.looksLikeBuyScreen()) {
            result.notes.add("'" + category.name + "' opened a buy screen ('" + snapshot.title() + "'), not a category.");
            if (result.buyScreen == null) {
                result.buyScreen = ShopScanResult.menuOf(snapshot);
                result.buyScreenProbedWith = category.name;
            }
            steps.addFirst(new CloseMenu());
            return;
        }

        if (!seenMenus.add(snapshot.contentSignature())) {
            // Opens a menu we've already read (for example a "back" button)
            return;
        }

        result.categories.add(category);
        readPage(category, 0, snapshot, new ArrayList<>());
    }

    /**
     * Records one page, queues sub-categories when the page has no prices, and queues the next page.
     *
     * @param nextSlots the next-page buttons clicked to get to this page
     */
    private void readPage(ShopScanResult.Category category, int index, ScreenSnapshot snapshot, List<Integer> nextSlots) {
        ShopScanResult.Page page = new ShopScanResult.Page();
        page.index = index;
        page.title = snapshot.title();
        category.pages.add(page);

        for (ScreenSnapshot.Item item : snapshot.realItems()) {
            if (item.nav() != ShopText.Nav.NONE) {
                continue;
            }
            page.items.add(ShopScanResult.Button.of(item));

            Double price = item.buyPrice();
            if (price == null) {
                continue;
            }

            ShopScanResult.Location location = new ShopScanResult.Location();
            location.category = category.name;
            location.path = category.path;
            location.page = index;
            location.slot = item.slot();
            location.name = item.name();
            location.amountShown = item.count();
            location.buyPrice = price;
            result.items.computeIfAbsent(item.itemId(), k -> new ArrayList<>()).add(location);

            if (price > 0 && (cheapest == null || price < cheapest.price())) {
                cheapest = new Probe(category.path, List.copyOf(nextSlots), item.slot(), item.name(), price);
            }
        }

        if (!snapshot.hasPricedItems() && index == 0) {
            queueSubMenus(category, snapshot);
        }

        ScreenSnapshot.Item next = snapshot.findNav(ShopText.Nav.NEXT);
        if (next != null && index + 1 < Configs.Shop.MAX_PAGES.getIntegerValue()) {
            page.nextPageSlot = next.slot();
            List<Integer> slotsToNext = new ArrayList<>(nextSlots);
            slotsToNext.add(next.slot());

            // Run first, then the click before it
            steps.addFirst(new Run(nextPage -> {
                if (seenMenus.add(nextPage.contentSignature())) {
                    readPage(category, index + 1, nextPage, slotsToNext);
                }
            }));
            steps.addFirst(new Click(next.slot(), "next page of " + category.name));
        }
    }

    /** Every clickable, price-less button in this menu might open a (sub-)category. */
    private void queueSubMenus(ShopScanResult.Category parent, ScreenSnapshot snapshot) {
        if (parent.path.size() >= MAX_DEPTH) {
            return;
        }

        for (ScreenSnapshot.Item item : snapshot.realItems()) {
            if (item.nav() != ShopText.Nav.NONE || item.buyPrice() != null || item.sellPrice() != null
                    || isUnsafe(item)) {
                continue;
            }

            ShopScanResult.Category category = new ShopScanResult.Category();
            category.name = parent.path.isEmpty() ? item.name() : parent.name + " > " + item.name();
            category.path = new ArrayList<>(parent.path);
            category.path.add(item.slot());
            pendingCategories.add(category);
        }
    }

    private static boolean isUnsafe(ScreenSnapshot.Item item) {
        String text = ShopText.lower(item.name() + " " + String.join(" ", item.lore()));
        for (String word : UNSAFE_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private void finish(Minecraft mc) {
        running = false;
        closeMenu(mc);

        int pages = result.categories.stream().mapToInt(c -> c.pages.size()).sum();
        Path file = save();

        Chat.info("§aShop scan done:§r " + result.categories.size() + " categories, " + pages + " pages, "
                + result.items.size() + " different items with a buy price.");

        if (result.buyScreen != null) {
            List<String> names = result.buyScreen.buttons.stream().map(b -> b.name).toList();
            Chat.info("Buy screen '" + result.buyScreen.title + "': " + String.join(", ", names));
        } else if (result.clickBuysInstantly) {
            Chat.warn("Clicking an item didn't open a buy screen. The shop may buy right away on click.");
        }

        if (file != null) {
            Chat.info("Saved to " + file.toAbsolutePath());
        }
    }

    @Nullable
    private Path save() {
        Path dir = FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("shops");
        Path file = dir.resolve(result.server + ".json");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        try {
            Files.createDirectories(dir);
            Files.writeString(file, gson.toJson(result), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            PrinterAddon.LOGGER.error("Couldn't save the shop scan to {}", file, e);
            Chat.warn("Couldn't save the shop scan: " + e.getMessage());
            return null;
        }
    }

    private static void closeMenu(Minecraft mc) {
        if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
    }

    private static int timeout() {
        return Configs.Shop.WAIT_TIMEOUT_TICKS.getIntegerValue();
    }

    // ---- Steps -------------------------------------------------------------------------------------------

    private abstract static class Step {
        abstract Status tick(Minecraft mc);
    }

    /** Marks the end of a category: on failure, steps are skipped up to here. */
    private static class Boundary extends Step {
        @Override
        Status tick(Minecraft mc) {
            return Status.DONE;
        }
    }

    /** Waits until an open menu has stopped changing, then stores it as {@link #current}. */
    private abstract class WaitForMenu extends Step {
        private String lastSignature;
        private int stableTicks;
        private int closedTicks;
        protected int ticks;

        /** Returns DONE once a stable menu that {@code accept}s is open, CLOSED if no menu stays open. */
        protected Status waitFor(Minecraft mc, @Nullable String mustDifferFrom, boolean failWhenClosed) {
            ticks++;
            ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);

            if (snapshot == null) {
                stableTicks = 0;
                lastSignature = null;
                if (failWhenClosed && ++closedTicks > CLOSED_GRACE_TICKS) {
                    return Status.CLOSED;
                }
            } else {
                closedTicks = 0;
                String signature = snapshot.signature();
                if (signature.equals(lastSignature)) {
                    stableTicks++;
                } else {
                    stableTicks = 0;
                    lastSignature = signature;
                }

                if (stableTicks >= STABLE_TICKS && !signature.equals(mustDifferFrom)) {
                    current = snapshot;
                    return Status.DONE;
                }
            }

            return ticks > timeout() ? Status.FAILED : Status.RUNNING;
        }
    }

    /** Closes any open menu, runs the shop command and waits for the shop to open. */
    private class OpenShop extends WaitForMenu {
        private boolean sent;
        private int delay;

        @Override
        Status tick(Minecraft mc) {
            if (!sent) {
                closeMenu(mc);
                if (++delay < Configs.Speed.shopClickDelayTicks()) {
                    return Status.RUNNING;
                }
                mc.player.connection.sendCommand(Configs.Shop.SHOP_COMMAND.getStringValue());
                sent = true;
                return Status.RUNNING;
            }

            Status status = waitFor(mc, null, false);
            if (status == Status.FAILED) {
                failReason = "/" + Configs.Shop.SHOP_COMMAND.getStringValue() + " didn't open a menu.";
            }
            return status;
        }
    }

    /** Left-clicks one slot of the open menu and waits until the menu shows something else. */
    private class Click extends WaitForMenu {
        private final int slot;
        private final String what;
        private String before;
        private int delay;

        Click(int slot, String what) {
            this.slot = slot;
            this.what = what;
        }

        @Override
        Status tick(Minecraft mc) {
            if (before == null) {
                if (++delay < Configs.Speed.shopClickDelayTicks()) {
                    return Status.RUNNING;
                }
                ScreenSnapshot snapshot = ScreenSnapshot.capture(mc);
                if (snapshot == null) {
                    return Status.CLOSED;
                }
                before = snapshot.signature();
                mc.gameMode.handleContainerInput(snapshot.containerId(), slot, 0, ContainerInput.PICKUP, mc.player);
                return Status.RUNNING;
            }

            // The click briefly shows the item on the cursor; only a lasting change counts
            Status status = waitFor(mc, before, true);
            if (status == Status.FAILED) {
                failReason = "Clicking slot " + slot + " (" + what + ") changed nothing.";
            }
            return status;
        }
    }

    /** Clicks an item once to read the buy screen. No change means the click may have bought it directly. */
    private class ProbeClick extends Click {
        private final String name;

        ProbeClick(int slot, String name) {
            super(slot, "read the buy screen with " + name);
            this.name = name;
        }

        @Override
        Status tick(Minecraft mc) {
            Status status = super.tick(mc);
            if (status == Status.FAILED || status == Status.CLOSED) {
                result.clickBuysInstantly = true;
                result.buyScreenProbedWith = name;
                current = null;
                return Status.DONE;
            }
            return status;
        }
    }

    private class CloseMenu extends Step {
        @Override
        Status tick(Minecraft mc) {
            closeMenu(mc);
            return Status.DONE;
        }
    }

    /** Runs code with the menu that the previous step waited for. */
    private class Run extends Step {
        private final Consumer<ScreenSnapshot> action;

        Run(Consumer<ScreenSnapshot> action) {
            this.action = action;
        }

        @Override
        Status tick(Minecraft mc) {
            action.accept(current);
            return Status.DONE;
        }
    }
}
