package no.sindrens.printeraddon.shop;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads button names and price lines from shop menu items. English text (ShopGUI+ defaults). */
public final class ShopText {
    private ShopText() {
    }

    public enum Nav { NONE, NEXT, PREVIOUS, BACK, CLOSE }

    /** A number like 5, 1,250, 2.5, 118k, $3.2m */
    private static final Pattern NUMBER = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\b",
            Pattern.CASE_INSENSITIVE);

    public static String lower(String text) {
        return text.toLowerCase(Locale.ROOT).trim();
    }

    public static boolean isFiller(String itemId, String name) {
        if (itemId.equals("minecraft:air")) {
            return true;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return true;
        }
        // Unnamed glass panes are decoration ("Gray Stained Glass Pane" is the item's default name)
        return itemId.endsWith("glass_pane") && lower(n).endsWith("glass pane");
    }

    public static Nav navOf(String name) {
        String n = lower(name);
        if (n.contains("previous") || n.contains("prev page") || n.equals("<") || n.equals("<<")
                || n.contains("←") || n.contains("«")) {
            return Nav.PREVIOUS;
        }
        if (n.contains("next") || n.equals(">") || n.equals(">>") || n.contains("→") || n.contains("»")) {
            return Nav.NEXT;
        }
        if (n.equals("back") || n.startsWith("back ") || n.contains("go back") || n.contains("return")
                || n.contains("main menu")) {
            return Nav.BACK;
        }
        if (n.equals("close") || n.startsWith("close ") || n.equals("exit")) {
            return Nav.CLOSE;
        }
        return Nav.NONE;
    }

    @Nullable
    public static Double buyPrice(List<String> lore) {
        return priceAfter(lore, "buy");
    }

    @Nullable
    public static Double sellPrice(List<String> lore) {
        return priceAfter(lore, "sell");
    }

    /** First number on the first lore line that mentions {@code keyword} (e.g. "Buy price: $5"). */
    @Nullable
    private static Double priceAfter(List<String> lore, String keyword) {
        for (String line : lore) {
            String l = lower(line);
            int at = l.indexOf(keyword);
            // "Left click to buy" has no number: keep looking
            if (at < 0) {
                continue;
            }
            Double value = parseNumber(l.substring(at + keyword.length()));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Parses the first number in the text, with k / m / b suffixes ("118k" = 118000). */
    @Nullable
    public static Double parseNumber(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        String suffix = m.group(2);
        if (suffix != null) {
            switch (Character.toLowerCase(suffix.charAt(0))) {
                case 'k' -> value *= 1_000;
                case 'm' -> value *= 1_000_000;
                case 'b' -> value *= 1_000_000_000;
                default -> {
                }
            }
        }
        return value;
    }

    /** Words that show a menu is the amount/confirm screen after clicking an item. */
    public static boolean looksLikeBuyScreen(String title, List<String> buttonNames) {
        String t = lower(title);
        if (t.startsWith("buying") || t.startsWith("selling") || t.contains("confirm")) {
            return true;
        }
        int amountButtons = 0;
        for (String name : buttonNames) {
            String n = lower(name);
            if (n.matches("[+-]\\s*\\d+.*") || n.startsWith("add ") || n.startsWith("remove ")
                    || n.startsWith("set to") || n.contains("confirm")) {
                amountButtons++;
            }
        }
        return amountButtons >= 2;
    }
}
