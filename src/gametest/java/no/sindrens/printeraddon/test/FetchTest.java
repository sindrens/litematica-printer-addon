package no.sindrens.printeraddon.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import no.sindrens.printeraddon.build.ChestFetcher;
import no.sindrens.printeraddon.build.ChestLinks;
import no.sindrens.printeraddon.build.Mover;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Taking missing items from linked containers: a chest next to the player and a barrel 12 blocks away (flown to).
 * Items nobody asked for stay where they are, and a chest that isn't linked is never opened.
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
            // Not linked: must stay shut although it has what's asked for
            server.runCommand("setblock ~-2 ~ ~ chest{Items:[{Slot:0b,id:\"minecraft:redstone\",count:10}]}");
            context.waitTicks(40);
            context.runOnClient(mc -> {
                net.minecraft.core.BlockPos at = mc.player.blockPosition();
                ChestLinks.clear(mc);
                ChestLinks.toggle(mc, at.offset(2, 0, 0));
                ChestLinks.toggle(mc, at.offset(12, 0, 0));
            });

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
                    mc.player.getInventory().countItem(Items.GOLD_BLOCK), mc.player.getInventory().countItem(Items.DIAMOND),
                    mc.player.getInventory().countItem(Items.REDSTONE)});
            // Leftovers go back into the nearest linked chest
            boolean storing = context.computeOnClient(mc -> fetcher.startStore(mc, java.util.Set.of(Items.IRON_BLOCK)));
            ticks = 0;
            while (storing && ticks < 20 * 60 && context.computeOnClient(mc -> fetcher.isBusy())) {
                context.runOnClient(mc -> fetcher.tick(mc, mover));
                context.waitTicks(1);
                ticks++;
            }
            int ironLeft = context.computeOnClient(mc -> mc.player.getInventory().countItem(Items.IRON_BLOCK));
            System.out.println("FETCH TEST: stored iron, " + ironLeft + " left after " + ticks + " ticks");
            if (!storing || ironLeft != 0) {
                throw new AssertionError("Fetch test: storing leftovers, started=" + storing + " iron left=" + ironLeft);
            }
            context.runOnClient(ChestLinks::clear);
            String have = "started=" + started + " iron_block=" + counts[0] + " gold_block=" + counts[1] + " diamond=" + counts[2]
                    + " redstone from the unlinked chest=" + counts[3];
            System.out.println("FETCH TEST: " + have + " after " + ticks + " ticks");
            if (!started || counts[0] < 5 || counts[1] < 7 || counts[2] != 0 || counts[3] != 0) {
                throw new AssertionError("Fetch test: " + have);
            }
        }
    }
}
