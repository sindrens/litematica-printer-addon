package no.sindrens.printeraddon.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a shop scan found. Saved as JSON so the buyer can find items later
 * (and so the file can be read by a person).
 */
public class ShopScanResult {
    public String server;
    public String scannedAt;
    public String shopCommand;
    public Menu mainMenu;
    public List<Category> categories = new ArrayList<>();
    /** The amount/confirm screen that opens when an item is left-clicked, if it was read. */
    public Menu buyScreen;
    /** Which item was clicked to read the buy screen. */
    public String buyScreenProbedWith;
    /** True if clicking an item opened no new screen (the shop may buy right away on click). */
    public boolean clickBuysInstantly;
    /** item id -> where it can be bought. */
    public Map<String, List<Location>> items = new LinkedHashMap<>();
    public List<String> notes = new ArrayList<>();

    public static class Menu {
        public String title;
        public int size;
        public List<Button> buttons = new ArrayList<>();
    }

    public static class Button {
        public int slot;
        public String item;
        public int count;
        public String name;
        public List<String> lore;
        public String nav;
        public Double buyPrice;
        public Double sellPrice;

        static Button of(ScreenSnapshot.Item item) {
            Button b = new Button();
            b.slot = item.slot();
            b.item = item.itemId();
            b.count = item.count();
            b.name = item.name();
            b.lore = item.lore();
            ShopText.Nav nav = item.nav();
            b.nav = nav == ShopText.Nav.NONE ? null : nav.name();
            b.buyPrice = item.buyPrice();
            b.sellPrice = item.sellPrice();
            return b;
        }
    }

    public static class Category {
        public String name;
        /** Slots to click, starting from the main menu, to open this category. */
        public List<Integer> path = new ArrayList<>();
        public List<Page> pages = new ArrayList<>();
    }

    public static class Page {
        public int index;
        public String title;
        /** Slot of the next page button on this page, or null on the last page. */
        public Integer nextPageSlot;
        public List<Button> items = new ArrayList<>();
    }

    /** Where one item can be bought: open the category (path), go to the page, click the slot. */
    public static class Location {
        public String category;
        public List<Integer> path;
        public int page;
        public int slot;
        public String name;
        public int amountShown;
        public Double buyPrice;
    }

    public static Menu menuOf(ScreenSnapshot snapshot) {
        Menu menu = new Menu();
        menu.title = snapshot.title();
        menu.size = snapshot.size();
        for (ScreenSnapshot.Item item : snapshot.realItems()) {
            menu.buttons.add(Button.of(item));
        }
        return menu;
    }
}
