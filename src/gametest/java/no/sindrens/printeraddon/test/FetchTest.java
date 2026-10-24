package no.sindrens.printeraddon.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.build.ChestFetcher;
import no.sindrens.printeraddon.build.Mover;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Taking missing items from containers nearby: a chest next to the player and a barrel 12 blocks away (flown to).
 * Items nobody asked for stay where they are.
 */
public class FetchTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            server.runCommand("fill ~-4 ~-1 ~-4 ~16 ~-1 ~4 stone");
            server.runCommand("fill ~-4 ~ ~-4 ~16 ~3 ~4 air");
            server.runCommand("setblock ~2 ~ ~ chest{Items:[{Slot:0b,id:\"minecraft:iron_block\",count:5},"
                    + "{Slot:1b,id:\"minecraft:diamond\",count:3}]}");
            server.runCommand("setblock ~12 ~ ~ barrel{Items:[{Slot:4b,id:\"minecraft:gold_block\",count:7}]}");
            context.waitTicks(40);

            ChestFetcher fetcher = new ChestFetcher();
            Mover mover = new Mover();
            boolean started = context.computeOnClient(mc -> {
                Map<Item, Integer> missing = new LinkedHashMap<>();
                missing.put(Items.IRON_BLOCK, 5);
                missing.put(Items.GOLD_BLOCK, 7);
                missing.put(Items.REDSTONE, 10);
                return fetcher.start(mc, missing);
            });

            int ticks = 0;
            while (started && ticks < 20 * 60 && context.computeOnClient(mc -> fetcher.isBusy())) {
                context.runOnClient(mc -> fetcher.tick(mc, mover));
                context.waitTicks(1);
                ticks++;
            }
            context.takeScreenshot("fetch");
            int[] counts = context.computeOnClient(mc -> new int[]{mc.player.getInventory().countItem(Items.IRON_BLOCK),
                    mc.player.getInventory().countItem(Items.GOLD_BLOCK), mc.player.getInventory().countItem(Items.DIAMOND)});
            String have = "started=" + started + " iron_block=" + counts[0] + " gold_block=" + counts[1] + " diamond=" + counts[2];
            System.out.println("FETCH TEST: " + have + " after " + ticks + " ticks");
            if (!started || counts[0] < 5 || counts[1] < 7 || counts[2] != 0) {
                throw new AssertionError("Fetch test: " + have);
            }
        }
    }
}
