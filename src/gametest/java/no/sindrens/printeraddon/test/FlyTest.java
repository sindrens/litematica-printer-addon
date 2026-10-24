package no.sindrens.printeraddon.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import no.sindrens.printeraddon.build.Mover;

/**
 * Flying around a building: a stone building 13 wide and 11 high stands between the player and the spot to get to.
 * The player must get there without bumping into it (flying around or over, never through).
 */
public class FlyTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass()) || System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            server.runCommand("fill ~-4 ~-1 ~-10 ~34 ~-1 ~10 stone");
            server.runCommand("fill ~-4 ~ ~-10 ~34 ~14 ~10 air");
            // The building, right in the way
            server.runCommand("fill ~10 ~ ~-6 ~22 ~10 ~6 stone_bricks");
            server.runCommand("setblock ~30 ~ ~ gold_block");
            context.waitTicks(40);
            BlockPos goal = context.computeOnClient(mc -> mc.player.blockPosition().offset(30, 0, 0));

            context.runOnClient(mc -> no.sindrens.printeraddon.config.Configs.Generic.DEBUG_LOG.setBooleanValue(true));
            Mover mover = new Mover();
            int ticks = 0;
            int bumps = 0;
            boolean there = false;
            while (ticks < 20 * 60) {
                there = context.computeOnClient(mc -> {
                    boolean reached = mover.goTo(goal);
                    if (!reached) {
                        mover.tick(mc);
                    }
                    return reached;
                });
                if (there) {
                    break;
                }
                if (context.computeOnClient(mc -> mc.player.horizontalCollision)) {
                    bumps++;
                    context.runOnClient(mc -> System.out.println("FLY TEST: bump at " + mc.player.position()
                            + " moving " + mc.player.getDeltaMovement()));
                }
                if (ticks % 40 == 0) {
                    context.takeScreenshot("fly");
                }
                context.waitTicks(1);
                ticks++;
            }
            context.takeScreenshot("fly-end");
            String result = "there=" + there + " after " + ticks + " ticks, bumped into something " + bumps + " ticks";
            System.out.println("FLY TEST: " + result);
            context.runOnClient(mc -> mover.goTo(null));
            if (!there || bumps > 3) {
                throw new AssertionError("Fly test: " + result);
            }
        }
    }
}
