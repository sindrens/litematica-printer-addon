package no.sindrens.printeraddon.test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A /shop that works like ShopGUI+ on the real server: a main menu with the money shop (paper) on the left and
 * other shops next to it, categories, items with price text, two pages, a category whose items show no price,
 * and the "Buying → item" screen with +1/+16/+32, buy more stacks and confirm. Counts every click that a scan
 * must never make.
 */
public final class FakeShop {
    /** Clicks on main menu buttons other than the money shop. */
    public static int otherShopClicks;
    /** Clicks on items for sale (each opens the buy screen). */
    public static int itemClicks;
    /** Clicks on purchase. */
    public static int confirmClicks;
    /** Items bought with confirm: item id -> amount. */
    public static final Map<String, Integer> bought = new HashMap<>();

    private FakeShop() {
    }

    public static void reset() {
        otherShopClicks = 0;
        itemClicks = 0;
        confirmClicks = 0;
        bought.clear();
    }

    private record Offer(Item item, List<String> lore) {
    }

    private static final Item[] BLOCKS = {Items.STONE, Items.COBBLESTONE, Items.DIRT, Items.GRASS_BLOCK, Items.SAND,
            Items.GRAVEL, Items.OAK_LOG, Items.OAK_PLANKS, Items.GLASS, Items.BRICKS, Items.HAY_BLOCK, Items.MUD,
            Items.CONCRETE.purple(), Items.CONCRETE.white(), Items.CONCRETE.black(), Items.CONCRETE.red(), Items.CONCRETE.blue(),
            Items.CONCRETE.green(), Items.CONCRETE.yellow(), Items.CONCRETE.orange(), Items.SMOOTH_STONE, Items.ANDESITE,
            Items.DIORITE, Items.GRANITE, Items.DEEPSLATE, Items.TUFF, Items.CALCITE, Items.CLAY,
            // page 2
            Items.CHERRY_LEAVES, Items.REDSTONE_BLOCK, Items.COBBLESTONE_SLAB, Items.IRON_CHAIN, Items.CAULDRON};

    public static void openMain(ServerPlayer player) {
        Menu menu = new Menu("Shop");
        menu.button(18, named(Items.PAPER, "Money Shop", "Click to open"), p -> openMoney(p));
        menu.button(22, named(Items.EMERALD, "Token Shop", "Spend your tokens here"), p -> {
            otherShopClicks++;
            openOffers(p, "Token Shop", List.of(new Offer(Items.DIAMOND, List.of("Buy price: 5 tokens"))));
        });
        menu.button(26, named(Items.NETHER_STAR, "Crates"), p -> {
            otherShopClicks++;
            openOffers(p, "Crates", List.of(new Offer(Items.TRIPWIRE_HOOK, List.of("Buy price: $50000"))));
        });
        menu.open(player);
    }

    private static void openMoney(ServerPlayer player) {
        Menu menu = new Menu("Money Shop");
        menu.button(10, named(Items.GRASS_BLOCK, "Blocks", "Click to browse"), p -> openBlocks(p, 0));
        menu.button(12, named(Items.WHEAT, "Farming", "Click to browse"), p -> openFarming(p));
        menu.button(14, named(Items.REDSTONE, "Redstone"), p -> openRedstone(p));
        menu.button(49, named(Items.BARRIER, "Back"), p -> openMain(p));
        menu.open(player);
    }

    private static void openBlocks(ServerPlayer player, int page) {
        Menu menu = new Menu("Blocks (" + (page + 1) + "/2)");
        int first = page * 28;
        for (int i = first; i < Math.min(BLOCKS.length, first + 28); i++) {
            Item item = BLOCKS[i];
            double price = 2 + i * 0.5;
            List<String> lore = List.of("Buy price: $" + price, "Sell price: $" + (price / 4), "",
                    "Left click to buy", "Right click to sell");
            int slot = 10 + (i - first) / 7 * 9 + (i - first) % 7;
            menu.button(slot, withLore(new ItemStack(item), lore), p -> openBuy(p, item, () -> openBlocks(p, page)));
        }
        if (page == 0) {
            menu.button(53, named(Items.ARROW, "Next Page"), p -> openBlocks(p, 1));
        } else {
            menu.button(45, named(Items.ARROW, "Previous Page"), p -> openBlocks(p, 0));
        }
        menu.button(49, named(Items.BARRIER, "Back"), p -> openMoney(p));
        menu.open(player);
    }

    private static void openFarming(ServerPlayer player) {
        Menu menu = new Menu("Farming");
        menu.button(10, withLore(new ItemStack(Items.PUMPKIN_SEEDS), List.of("Cost: 1.5k", "Left-click to purchase")),
                p -> openBuy(p, Items.PUMPKIN_SEEDS, () -> openFarming(p)));
        menu.button(11, withLore(new ItemStack(Items.MELON_SEEDS), List.of("Buy 1x for $3")),
                p -> openBuy(p, Items.MELON_SEEDS, () -> openFarming(p)));
        menu.button(12, withLore(new ItemStack(Items.LAVA_BUCKET), List.of("Buy: $250", "Sell: $100")),
                p -> openBuy(p, Items.LAVA_BUCKET, () -> openFarming(p)));
        menu.button(13, withLore(new ItemStack(Items.WATER_BUCKET), List.of("Price: $20")),
                p -> openBuy(p, Items.WATER_BUCKET, () -> openFarming(p)));
        menu.button(49, named(Items.BARRIER, "Back"), p -> openMoney(p));
        menu.open(player);
    }

    /** Items with no text at all: only clicking one shows it's for sale. */
    private static void openRedstone(ServerPlayer player) {
        Menu menu = new Menu("Redstone");
        Item[] items = {Items.HOPPER, Items.OBSERVER, Items.PISTON, Items.COMPARATOR, Items.RAIL, Items.POWERED_RAIL};
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            menu.button(10 + i, new ItemStack(item), p -> openBuy(p, item, () -> openRedstone(p)));
        }
        menu.button(49, named(Items.BARRIER, "Back"), p -> openMoney(p));
        menu.open(player);
    }

    private static void openOffers(ServerPlayer player, String title, List<Offer> offers) {
        Menu menu = new Menu(title);
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            menu.button(10 + i, withLore(new ItemStack(offer.item()), offer.lore()), p -> {
                itemClicks++;
                p.closeContainer();
            });
        }
        menu.open(player);
    }

    /**
     * The buy screen as on the real server: "Buying ➠ item", small-caps "Purchase item" (with the cost) and
     * "Purchase stacks", +1/+16/+32 (and -1/-16/-32 once the amount is above 1), the item with "x N", at most 64.
     * The screen stays open after a purchase.
     */
    private static void openBuy(ServerPlayer player, Item item, Runnable back) {
        itemClicks++;
        int[] amount = {1};
        String name = new ItemStack(item).getHoverName().getString();
        Menu menu = new Menu("Buying ➠ " + name, 5);
        Runnable[] refresh = new Runnable[1];
        Consumer<Integer> change = step -> {
            amount[0] = Math.max(1, Math.min(64, amount[0] + step));
            refresh[0].run();
        };
        refresh[0] = () -> {
            menu.container.setItem(22, withLore(new ItemStack(item, Math.min(amount[0], item.getDefaultMaxStackSize())),
                    List.of("x " + amount[0])));
            for (int[] button : new int[][]{{11, -1}, {20, -16}, {29, -32}}) {
                menu.container.setItem(button[0], amount[0] > 1
                        ? named(Items.DYED_TERRACOTTA.red(), "-" + -button[1]) : new ItemStack(Items.STAINED_GLASS_PANE.gray()));
            }
        };
        for (int[] button : new int[][]{{11, -1}, {20, -16}, {29, -32}}) {
            menu.actions.put(button[0], p -> change.accept(button[1]));
        }
        menu.button(15, named(Items.DYED_TERRACOTTA.lime(), "+1"), p -> change.accept(1));
        menu.button(24, new ItemStack(Items.DYED_TERRACOTTA.lime(), 16), p -> change.accept(16));
        menu.container.getItem(24).set(DataComponents.CUSTOM_NAME, Component.literal("+16"));
        menu.button(33, new ItemStack(Items.DYED_TERRACOTTA.lime(), 32), p -> change.accept(32));
        menu.container.getItem(33).set(DataComponents.CUSTOM_NAME, Component.literal("+32"));
        menu.button(13, named(Items.PAPER, "ᴘᴜʀᴄʜᴀѕᴇ ɪᴛᴇᴍ",
                "ᴄᴏѕᴛ: $0.10", "", "→ ᴄʟɪᴄᴋ ᴛᴏ ʙᴜʏ"), p -> {
            confirmClicks++;
            bought.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), amount[0], Integer::sum);
            int left = amount[0];
            while (left > 0) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                p.getInventory().add(new ItemStack(item, n));
                left -= n;
            }
        });
        menu.button(31, named(Items.NETHER_STAR, "ᴘᴜʀᴄʜᴀѕᴇ ѕᴛᴀᴄᴋѕ"),
                p -> otherShopClicks++);
        menu.button(40, named(Items.BARRIER, "Back"), p -> back.run());
        refresh[0].run();
        menu.open(player);
    }

    private static ItemStack named(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return lore.length == 0 ? stack : withLore(stack, List.of(lore));
    }

    private static ItemStack withLore(ItemStack stack, List<String> lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Component.literal(line));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    /** A 6-row menu filled with grey panes, with click actions per slot. */
    private static class Menu {
        final String title;
        final SimpleContainer container = new SimpleContainer(54);
        int rows = 6;
        final Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();

        Menu(String title) {
            this(title, 6);
        }

        Menu(String title, int rows) {
            this.title = title;
            this.rows = rows;
            for (int i = 0; i < rows * 9; i++) {
                container.setItem(i, new ItemStack(Items.STAINED_GLASS_PANE.gray()));
            }
        }

        void button(int slot, ItemStack stack, Consumer<ServerPlayer> action) {
            container.setItem(slot, stack);
            actions.put(slot, action);
        }

        void open(ServerPlayer player) {
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ShopMenu(id, inventory, this),
                    Component.literal(title)));
        }
    }

    private static class ShopMenu extends ChestMenu {
        private final Menu menu;

        ShopMenu(int id, Inventory inventory, Menu menu) {
            super(menu.rows == 5 ? MenuType.GENERIC_9x5 : MenuType.GENERIC_9x6, id, inventory, menu.container, menu.rows);
            this.menu = menu;
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            // Nothing can be taken out; a button runs its action
            Consumer<ServerPlayer> action = menu.actions.get(slot);
            if (action != null && input == ContainerInput.PICKUP && player instanceof ServerPlayer serverPlayer) {
                action.accept(serverPlayer);
            }
            if (player.containerMenu == this) {
                sendAllDataToRemote();
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
