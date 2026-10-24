package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.util.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.Set;

/**
 * Eats when the food bar gets low, so a long build never starves (starving hurts, and damage stops auto build).
 * Building pauses for the second and a half it takes.
 */
public class AutoEat {
    /** Food that does something bad (poison, hunger, teleporting) or is too valuable to eat while building. */
    private static final Set<Item> NEVER = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.CHICKEN);
    private static final int GIVE_UP_TICKS = 80;

    private int ticks = -1;
    private boolean started;
    private long warnedAt = -100000;
    /** The feed command didn't fill the food bar (no permission): food is eaten instead this session. */
    private static boolean feedBroken;
    /** When the feed command was last sent (-1: no answer awaited), and the food level then. */
    private long fedAt = -1;
    private int foodBeforeFeed;

    public boolean isBusy() {
        return ticks >= 0;
    }

    public void reset(Minecraft mc) {
        if (isBusy()) {
            mc.options.keyUse.setDown(false);
        }
        ticks = -1;
        started = false;
    }

    /** Returns true while it's eating (nothing else should happen then). */
    public boolean tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            return false;
        }
        if (!isBusy()) {
            int level = player.getFoodData().getFoodLevel();
            if (fedAt >= 0 && AutoBuild.clientTicks - fedAt >= 40 + 2L * ServerLag.pingTicks(mc)) {
                // Did the feed command work? (it fills the bar at once)
                if (level <= foodBeforeFeed) {
                    feedBroken = true;
                    Chat.warn("/" + Configs.Safety.FEED_COMMAND.getStringValue() + " didn't fill the food bar: eating food from the inventory instead"
                            + " (switch 'Eat with /feed' off to skip this).");
                    BuildLog.line("EAT", "the feed command did nothing (food " + foodBeforeFeed + " -> " + level + "): not used again this session");
                } else {
                    BuildLog.line("EAT", "fed with the feed command: food " + foodBeforeFeed + " -> " + level);
                }
                fedAt = -1;
            }
            if (!Configs.Safety.AUTO_EAT.getBooleanValue() || player.getAbilities().instabuild
                    || level > Configs.Safety.EAT_BELOW.getIntegerValue()) {
                return false;
            }
            // The server's feed command: no food needed, no pause for building
            if (Configs.Safety.USE_FEED_COMMAND.getBooleanValue() && !feedBroken) {
                if (fedAt < 0) {
                    fedAt = AutoBuild.clientTicks;
                    foodBeforeFeed = level;
                    player.connection.sendCommand(Configs.Safety.FEED_COMMAND.getStringValue());
                }
                return false;
            }
            int slot = findFood(player.getInventory());
            if (slot == -1) {
                if (AutoBuild.clientTicks - warnedAt > 20 * 60 * 5) {
                    warnedAt = AutoBuild.clientTicks;
                    Chat.warn("Hungry and no food in the inventory: bring some (bread, steak, carrots...).");
                }
                return false;
            }
            ItemStack food = player.getInventory().getItem(slot);
            PrinterAddon.debug("Eating {} (food level {})", food.getHoverName().getString(), player.getFoodData().getFoodLevel());
            if (Inventory.isHotbarSlot(slot)) {
                player.getInventory().setSelectedSlot(slot);
            } else {
                InventoryUtils.setPickedItemToHand(slot, food.copy(), mc);
                AutoBuild.INSTANCE.inventory.moved();
            }
            ticks = 0;
            started = false;
            return true;
        }

        ticks++;
        if (!started) {
            // Wait until the server has the food in the hand too
            if (AutoBuild.INSTANCE.inventory.settling()) {
                return true;
            }
            if (!player.getMainHandItem().has(DataComponents.FOOD)) {
                reset(mc);
                return false;
            }
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            started = true;
            return true;
        }
        if (player.isUsingItem() && ticks < GIVE_UP_TICKS) {
            return true;
        }
        PrinterAddon.debug("Done eating, food level {}", player.getFoodData().getFoodLevel());
        reset(mc);
        return false;
    }

    /** The slot with the food that fills the most, or -1. */
    private static int findFood(Inventory inventory) {
        int best = -1;
        int bestValue = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || NEVER.contains(stack.getItem())) {
                continue;
            }
            if (food.nutrition() > bestValue) {
                best = slot;
                bestValue = food.nutrition();
            }
        }
        return best;
    }
}
