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
 * the way), powered rails from gold blocks and redstone, pumpkin seeds from pumpkins.
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
        }
    }
}
