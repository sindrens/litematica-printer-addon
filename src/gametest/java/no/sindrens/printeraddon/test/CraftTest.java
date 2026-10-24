package no.sindrens.printeraddon.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.build.CraftPlan;
import no.sindrens.printeraddon.build.Crafter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Crafting from raw materials: rails from iron blocks and logs (sticks, planks and the crafting table are made on
 * the way), powered rails from gold blocks and redstone, pumpkin seeds from pumpkins, diamond tools from a block of
 * diamond.
 */
public class CraftTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass())) {
            return;
        }
        if (System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            server.runCommand("recipe give @p *");
            context.runOnClient(mc -> no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.setBooleanValue(true));
            server.runCommand("fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 stone");
            server.runCommand("give @p iron_block 3");
            server.runCommand("give @p gold_block 2");
            server.runCommand("give @p oak_log 4");
            server.runCommand("give @p redstone 8");
            server.runCommand("give @p pumpkin 3");
            context.waitTicks(40);

            Crafter crafter = new Crafter();
            String plan = context.computeOnClient(mc -> {
                Map<Item, Integer> missing = new LinkedHashMap<>();
                missing.put(Items.RAIL, 32);
                missing.put(Items.POWERED_RAIL, 6);
                missing.put(Items.PUMPKIN_SEEDS, 12);
                CraftPlan p = CraftPlan.of(mc, missing, item -> false, false);
                for (CraftPlan.Step step : p.craft) {
                    crafter.craft(step.item(), step.count());
                }
                return "buy " + p.buy + ", craft " + p.craft;
            });
            System.out.println("CRAFT TEST plan: " + plan);

            int ticks = 0;
            while (ticks < 20 * 90 && context.computeOnClient(mc -> crafter.isBusy())) {
                context.runOnClient(crafter::tick);
                context.waitTicks(1);
                ticks++;
            }
            context.takeScreenshot("craft");
            String have = context.computeOnClient(mc -> "rails=" + mc.player.getInventory().countItem(Items.RAIL)
                    + " powered=" + mc.player.getInventory().countItem(Items.POWERED_RAIL)
                    + " seeds=" + mc.player.getInventory().countItem(Items.PUMPKIN_SEEDS)
                    + " table left in world=" + (mc.level.getBlockStates(mc.player.getBoundingBox().inflate(4))
                    .anyMatch(s -> s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))));
            System.out.println("CRAFT TEST: " + have + " after " + ticks + " ticks");
            int[] counts = context.computeOnClient(mc -> new int[]{mc.player.getInventory().countItem(Items.RAIL),
                    mc.player.getInventory().countItem(Items.POWERED_RAIL), mc.player.getInventory().countItem(Items.PUMPKIN_SEEDS)});
            if (counts[0] < 32 || counts[1] < 6 || counts[2] < 12) {
                throw new AssertionError("Craft test: " + have);
            }

            // Tools for breaking wrong blocks, always diamond: from a block of diamond (9 diamonds) and logs
            server.runCommand("clear @p");
            server.runCommand("give @p diamond_block 1");
            server.runCommand("give @p oak_log 2");
            context.waitTicks(20);
            Crafter tools = new Crafter();
            String toolPlan = context.computeOnClient(mc -> {
                Map<Item, Integer> missing = new LinkedHashMap<>();
                missing.put(no.sindrens.printeraddon.build.Tools.PICKAXE, 1);
                missing.put(no.sindrens.printeraddon.build.Tools.SHOVEL, 1);
                CraftPlan p = CraftPlan.of(mc, missing, item -> false, false);
                for (CraftPlan.Step step : p.craft) {
                    tools.craft(step.item(), step.count());
                }
                return "buy " + p.buy + ", craft " + p.craft;
            });
            System.out.println("CRAFT TEST tools plan: " + toolPlan);
            ticks = 0;
            while (ticks < 20 * 90 && context.computeOnClient(mc -> tools.isBusy())) {
                context.runOnClient(tools::tick);
                context.waitTicks(1);
                ticks++;
            }
            String toolsHave = context.computeOnClient(mc -> "pickaxe=" + mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE)
                    + " shovel=" + mc.player.getInventory().countItem(Items.DIAMOND_SHOVEL)
                    + " diamonds left=" + mc.player.getInventory().countItem(Items.DIAMOND));
            System.out.println("CRAFT TEST tools: " + toolsHave + " after " + ticks + " ticks; client menu "
                    + context.computeOnClient(mc -> mc.player.containerMenu.getClass().getSimpleName() + " #" + mc.player.containerMenu.containerId)
                    + ", server menu " + server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().containerMenu.getClass().getSimpleName()
                    + " #" + s.getPlayerList().getPlayers().getFirst().containerMenu.containerId));
            boolean both = context.computeOnClient(mc -> mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) >= 1
                    && mc.player.getInventory().countItem(Items.DIAMOND_SHOVEL) >= 1
                    && mc.player.getInventory().countItem(Items.DIAMOND) == 5);
            if (!both) {
                throw new AssertionError("Craft test, tools: " + toolsHave + " (plan " + toolPlan + ")");
            }

            // Not in the recipe book: crafted by hand, the pattern clicked into the grid (a shovel on the player's
            // server). Then with limited crafting on, the server gives nothing for it: twice, and it isn't asked for
            // again (breaking goes on without it)
            waitForServerInventory(server, context);
            server.runCommand("clear @p");
            server.runCommand("recipe take @p minecraft:diamond_shovel");
            server.runCommand("give @p diamond 3");
            server.runCommand("give @p stick 6");
            server.runCommand("give @p crafting_table");
            context.waitTicks(20);
            int byHand = craftOne(context, Items.DIAMOND_SHOVEL);
            System.out.println("CRAFT TEST diamond shovel by hand: " + byHand);
            if (byHand < 1) {
                throw new AssertionError("Craft test: the diamond shovel wasn't crafted by hand");
            }
            waitForServerInventory(server, context);
            // Crafting it put it in the recipe book again
            server.runCommand("recipe take @p minecraft:diamond_shovel");
            server.runCommand("gamerule limited_crafting true");
            server.runCommand("clear @p diamond_shovel");
            context.waitTicks(10);
            int refusedOnce = craftOne(context, Items.DIAMOND_SHOVEL);
            waitForServerInventory(server, context);
            int refusedTwice = craftOne(context, Items.DIAMOND_SHOVEL);
            boolean refused = context.computeOnClient(mc -> Crafter.refused(Items.DIAMOND_SHOVEL));
            String stillMissing = context.computeOnClient(mc -> no.sindrens.printeraddon.build.Tools.breakingToolsMissing(
                    java.util.Set.of(net.minecraft.world.level.block.Blocks.DIRT), mc.player.getInventory()).toString());
            server.runCommand("gamerule limited_crafting false");
            System.out.println("CRAFT TEST limited crafting: crafted " + refusedOnce + " and " + refusedTwice + ", refused=" + refused
                    + ", tools still asked for " + stillMissing);
            if (refusedOnce != 0 || refusedTwice != 0 || !refused || stillMissing.contains("shovel")) {
                throw new AssertionError("Craft test, limited crafting: crafted " + refusedOnce + "/" + refusedTwice + ", refused="
                        + refused + ", tools still asked for " + stillMissing);
            }
            context.runOnClient(mc -> Crafter.forgetRefusals());

            // A full inventory: crafting a hoe ends at once and asks for room (it crafted on and on, the server
            // saying "Your inventory is full!"). First the server has to have the crafting table closed: changes made
            // before that are sent for the table's menu, which the client no longer shows
            waitForServerInventory(server, context);
            server.runCommand("clear @p");
            server.runCommand("give @p diamond 2");
            server.runCommand("give @p stick 2");
            server.runCommand("give @p dirt " + 64 * 34);
            // Until the client shows the full inventory (a moment after the commands)
            int waitedFull = 0;
            while (waitedFull < 20 * 10 && !context.computeOnClient(mc -> mc.player.getInventory().countItem(Items.DIRT) == 64 * 34
                    && no.sindrens.printeraddon.build.InventoryCleaner.freeSlots(mc.player.getInventory()) == 0)) {
                context.waitTicks(1);
                waitedFull++;
            }
            System.out.println("CRAFT TEST full inventory: waited " + waitedFull + " ticks, menu "
                    + context.computeOnClient(mc -> mc.player.containerMenu.getClass().getSimpleName() + ", screen " + mc.gui.screen()
                    + ", client dirt " + mc.player.getInventory().countItem(Items.DIRT) + ", free " + no.sindrens.printeraddon.build.InventoryCleaner.freeSlots(mc.player.getInventory()))
                    + ", server dirt " + server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.DIRT))
                    + ", server menu " + server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().containerMenu.getClass().getSimpleName()
                    + " #" + s.getPlayerList().getPlayers().getFirst().containerMenu.containerId)
                    + ", server items " + server.computeOnServer(s -> {
                        net.minecraft.world.entity.player.Inventory inv = s.getPlayerList().getPlayers().getFirst().getInventory();
                        StringBuilder sb = new StringBuilder();
                        for (int slot = 0; slot < 36; slot++) {
                            if (!inv.getItem(slot).isEmpty()) {
                                sb.append(slot).append('=').append(inv.getItem(slot).getCount()).append(inv.getItem(slot).getItem()).append(' ');
                            }
                        }
                        return sb.toString();
                    }));
            String inventoryNow = context.computeOnClient(mc -> {
                StringBuilder sb = new StringBuilder("free=" + no.sindrens.printeraddon.build.InventoryCleaner.freeSlots(mc.player.getInventory()) + ":");
                for (int slot = 0; slot < 36; slot++) {
                    sb.append(' ').append(slot).append('=').append(mc.player.getInventory().getItem(slot).getCount())
                            .append(mc.player.getInventory().getItem(slot).getItem());
                }
                return sb.toString();
            });
            System.out.println("CRAFT TEST full inventory before: " + inventoryNow);
            Crafter full = new Crafter();
            context.runOnClient(mc -> full.craft(Items.DIAMOND_HOE, 1));
            ticks = 0;
            while (ticks < 20 * 20 && context.computeOnClient(mc -> full.isBusy())) {
                context.runOnClient(full::tick);
                context.waitTicks(1);
                ticks++;
            }
            boolean ended = !context.computeOnClient(mc -> full.isBusy());
            boolean askedForRoom = context.computeOnClient(mc -> full.needsRoom());
            System.out.println("CRAFT TEST full inventory: ended=" + ended + " askedForRoom=" + askedForRoom + " after " + ticks + " ticks");
            if (!ended || !askedForRoom) {
                throw new AssertionError("Craft test, full inventory: ended=" + ended + " askedForRoom=" + askedForRoom);
            }
        }
    }

    /** Until the server has no crafting table open for the player any more (changes before that go to its menu). */
    private static void waitForServerInventory(TestServerContext server, ClientGameTestContext context) {
        for (int i = 0; i < 100 && !server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().containerMenu
                == s.getPlayerList().getPlayers().getFirst().inventoryMenu); i++) {
            context.waitTicks(1);
        }
    }

    /** Crafts one of the item with a new crafter: how many more the inventory has after. */
    private static int craftOne(ClientGameTestContext context, Item item) {
        Crafter crafter = new Crafter();
        int before = context.computeOnClient(mc -> mc.player.getInventory().countItem(item));
        context.runOnClient(mc -> crafter.craft(item, 1));
        int ticks = 0;
        while (ticks < 20 * 30 && context.computeOnClient(mc -> crafter.isBusy())) {
            context.runOnClient(crafter::tick);
            context.waitTicks(1);
            ticks++;
        }
        context.waitTicks(10);
        return context.computeOnClient(mc -> mc.player.getInventory().countItem(item)) - before;
    }
}
