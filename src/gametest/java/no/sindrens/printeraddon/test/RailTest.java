package no.sindrens.printeraddon.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Tries ways to place two powered rails side by side that should both run north-south (they always want to
 * connect to each other). Runs only with PRINTER_ADDON_RAIL_TEST=1; prints what each way gives.
 */
public class RailTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (System.getenv("PRINTER_ADDON_RAIL_TEST") == null) {
            return;
        }
        List<String> results = new ArrayList<>();

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("give @p powered_rail 64");
            server.runCommand("give @p rail 64");
            context.waitTicks(10);

            String[] ways = {"plain", "temp north of A", "temp both ends of A", "temp north of A and of B"};
            for (int way = 0; way < ways.length; way++) {
                int x = way * 6;
                // A at (x, -60, 0), B at (x+1, -60, 0); stone under everything
                server.runCommand("fill " + (x - 1) + " -61 -2 " + (x + 2) + " -61 2 stone");
                server.runCommand("fill " + (x - 1) + " -60 -2 " + (x + 2) + " -58 2 air");
                server.runCommand("tp @p " + (x + 0.5) + " -60 4.5 180 30");
                context.waitTicks(10);

                BlockPos a = new BlockPos(x, -60, 0);
                BlockPos b = a.east();
                final int w = way;
                context.runOnClient(mc -> {
                    if (w >= 1) place(mc, a.north(), Items.RAIL);
                    if (w >= 2) place(mc, a.south(), Items.RAIL);
                });
                context.waitTicks(5);
                context.runOnClient(mc -> place(mc, a, Items.POWERED_RAIL));
                context.waitTicks(5);
                context.runOnClient(mc -> {
                    if (w == 3) place(mc, b.north(), Items.RAIL);
                });
                context.waitTicks(5);
                context.runOnClient(mc -> place(mc, b, Items.POWERED_RAIL));
                context.waitTicks(5);
                // Remove the temporary rails
                server.runCommand("setblock " + a.north().getX() + " -60 " + a.north().getZ() + " air");
                server.runCommand("setblock " + a.south().getX() + " -60 " + a.south().getZ() + " air");
                server.runCommand("setblock " + b.north().getX() + " -60 " + b.north().getZ() + " air");
                context.waitTicks(10);

                String result = context.computeOnClient(mc -> "A=" + mc.level.getBlockState(a) + " B=" + mc.level.getBlockState(b));
                results.add(ways[way] + ": " + result.replace("Block{minecraft:powered_rail}", ""));
            }
        }

        System.out.println("RAIL TEST RESULTS");
        results.forEach(r -> System.out.println("  " + r));
    }

    /** Places an item on the block below the spot, like a player clicking its top, looking north. */
    private static void place(Minecraft mc, BlockPos pos, Item item) {
        Inventory inventory = mc.player.getInventory();
        int slot = inventory.findSlotMatchingItem(new net.minecraft.world.item.ItemStack(item));
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        }
        mc.player.setYRot(180);
        mc.player.setXRot(60);
        BlockPos below = pos.below();
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
    }
}
