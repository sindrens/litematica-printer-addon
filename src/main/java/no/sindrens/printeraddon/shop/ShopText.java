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
    private static final Pattern NUMBER = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\b(?!\\s*x\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Small-caps letters some servers use ("ᴘᴜʀᴄʜᴀѕᴇ ɪᴛᴇᴍ") and the plain letters they stand for. */
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕꜱᴛᴜᴠᴡʏᴢ";
    private static final String PLAIN = "abcdefghijklmnopqrsstuvwyz";

    public static String lower(String text) {
        return text.toLowerCase(Locale.ROOT).trim();
    }

    /** Lower case with small-caps letters turned into plain ones, for matching words. */
    public static String plain(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : lower(text).toCharArray()) {
            int i = SMALL_CAPS.indexOf(c);
            sb.append(i >= 0 ? PLAIN.charAt(i) : c);
        }
        return sb.toString();
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
        String n = plain(name);
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

    /** The buy price in an item's lore, or null if it can't be bought (only sold, or no price). */
    @Nullable
    public static Double buyPrice(List<String> lore) {
        return prices(lore)[0];
    }

    /** The sell price in an item's lore, or null if it can't be sold there. */
    @Nullable
    public static Double sellPrice(List<String> lore) {
        return prices(lore)[1];
    }

    /**
     * True for an item in a shop page: it has a buy or sell price, or says what a click on it buys or sells ("Left
     * click to buy"). Such an item is never clicked while scanning (clicking it opens the buy screen, or buys it).
     * A category button that only mentions buying or selling ("Sell your mob drops here!", "Purchase & sell in this
     * shop") is not one: it's opened, and its pages are read.
     */
    public static boolean isShopItem(List<String> lore) {
        Double[] prices = prices(lore);
        if (prices[0] != null || prices[1] != null) {
            return true;
        }
        for (String line : lore) {
            String l = plain(line);
            if (l.contains("click") && (l.contains("buy") || l.contains("sell") || l.contains("purchase"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {buy, sell} from an item's lore. Prices are on the line with "buy" / "sell" ("Buy price: $5"), or on the lines
     * under such a heading until the next empty line (" ʙᴜʏ ᴘʀɪᴄᴇ" then " ▬ $750.00 $712.50": the last $ is what it
     * costs now). Words are matched in plain letters (servers write them in small caps: "ѕᴇʟʟ ᴘʀɪᴄᴇ" is "sell
     * price"; matched as typed, a sell-only item's "$4.16" once counted as its buy price). A price under no heading
     * counts as the buy price only when the lore has neither.
     */
    static Double[] prices(List<String> lore) {
        Double buy = null;
        Double sell = null;
        Double other = null;
        String section = null;
        for (String raw : lore) {
            String l = plain(raw);
            if (l.isBlank()) {
                section = null;
                continue;
            }
            int b = firstOf(l, "buy", "cost", "purchase");
            int s = l.indexOf("sell");
            if (b >= 0 && s >= 0) {
                // Both on one line ("Buy $5 / Sell $2"): each number belongs to the word before it
                Double first = numberIn(l.substring(Math.min(b, s), Math.max(b, s)));
                Double second = numberIn(l.substring(Math.max(b, s)));
                if (b < s) {
                    buy = buy == null ? first : buy;
                    sell = sell == null ? second : sell;
                } else {
                    sell = sell == null ? first : sell;
                    buy = buy == null ? second : buy;
                }
                section = null;
                continue;
            }
            if (b >= 0 || s >= 0) {
                String kind = b >= 0 ? "buy" : "sell";
                Double value = numberIn(l.substring(b >= 0 ? b : s));
                if (value != null) {
                    if (kind.equals("buy") && buy == null) {
                        buy = value;
                    } else if (kind.equals("sell") && sell == null) {
                        sell = value;
                    }
                    section = null;
                } else {
                    // A heading ("Buy price"): the prices under it are its own. A line about clicking isn't one
                    section = isHeading(l) ? kind : section;
                }
                continue;
            }
            if (!l.contains("$")) {
                continue;
            }
            Double value = numberIn(l);
            if (value == null) {
                continue;
            }
            if ("buy".equals(section) && buy == null) {
                buy = value;
            } else if ("sell".equals(section) && sell == null) {
                sell = value;
            } else if (section == null && other == null) {
                other = value;
            }
        }
        if (buy == null && sell == null) {
            buy = other;
        }
        return new Double[]{buy, sell};
    }

    /** "buy price", "sell price:", "buy", "price (sell)": only the word and "price" (no "click to buy"). */
    private static boolean isHeading(String plain) {
        String rest = plain.replace("buy", "").replace("sell", "").replace("cost", "").replace("purchase", "")
                .replace("price", "").replace("each", "").replaceAll("[\\s:()\\-▬•|»>*]", "");
        return rest.isEmpty();
    }

    private static int firstOf(String text, String... words) {
        int best = -1;
        for (String word : words) {
            int at = text.indexOf(word);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    /** The number after the last $ on the line ("$750.00 $712.50" is 712.50 now), else the first number. */
    @Nullable
    private static Double numberIn(String text) {
        int dollar = text.lastIndexOf('$');
        return parseNumber(dollar >= 0 ? text.substring(dollar + 1) : text);
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
