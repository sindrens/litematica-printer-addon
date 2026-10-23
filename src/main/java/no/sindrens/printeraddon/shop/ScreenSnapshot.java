package no.sindrens.printeraddon.shop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** What a server menu (chest GUI) shows right now: its title and the items in its own slots. */
public record ScreenSnapshot(int containerId, String title, int size, List<Item> items) {

    /** One non-empty slot of the menu (not the player's inventory). */
    public record Item(int slot, String itemId, int count, String name, List<String> lore) {
        public boolean isFiller() {
            return ShopText.isFiller(itemId, name);
        }

        public ShopText.Nav nav() {
            return ShopText.navOf(name);
        }

        @Nullable
        public Double buyPrice() {
            return ShopText.buyPrice(lore);
        }

        @Nullable
        public Double sellPrice() {
            return ShopText.sellPrice(lore);
        }
    }

    /** Snapshot of the open server menu, or null if no such menu is open. */
    @Nullable
    public static ScreenSnapshot capture(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen) || screen instanceof InventoryScreen
                || mc.player == null) {
            return null;
        }

        AbstractContainerMenu menu = containerScreen.getMenu();
        Inventory playerInventory = mc.player.getInventory();
        List<Item> items = new ArrayList<>();
        int size = 0;

        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) {
                continue;
            }
            size++;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            List<String> lore = new ArrayList<>();
            ItemLore itemLore = stack.get(DataComponents.LORE);
            if (itemLore != null) {
                for (Component line : itemLore.lines()) {
                    lore.add(line.getString());
                }
            }

            items.add(new Item(slot.index, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), stack.getHoverName().getString(), lore));
        }

        if (size == 0) {
            return null;
        }

        return new ScreenSnapshot(menu.containerId, screen.getTitle().getString(), size, items);
    }

    /** Changes when the menu or anything in it changes. */
    public String signature() {
        StringBuilder sb = new StringBuilder().append(containerId).append('|').append(title);
        for (Item item : items) {
            sb.append('|').append(item.slot).append(':').append(item.itemId).append('x').append(item.count)
                    .append(':').append(item.name).append(':').append(item.lore.hashCode());
        }
        return sb.toString();
    }

    /** Same as signature() but ignoring which window it is, to notice pages that repeat. */
    public String contentSignature() {
        return signature().substring(signature().indexOf('|') + 1);
    }

    public List<Item> realItems() {
        return items.stream().filter(i -> !i.isFiller()).toList();
    }

    @Nullable
    public Item findNav(ShopText.Nav nav) {
        for (Item item : items) {
            if (!item.isFiller() && item.nav() == nav) {
                return item;
            }
        }
        return null;
    }

    public boolean hasPricedItems() {
        return items.stream().anyMatch(i -> !i.isFiller() && i.buyPrice() != null);
    }

    public boolean looksLikeBuyScreen() {
        return ShopText.looksLikeBuyScreen(title, items.stream().map(Item::name).toList());
    }
}
