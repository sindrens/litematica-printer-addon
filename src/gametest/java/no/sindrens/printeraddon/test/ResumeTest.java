package no.sindrens.printeraddon.test;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.config.Configs;

/**
 * Logging out in the middle of auto build and joining again: it starts by itself and finishes the build.
 */
public class ResumeTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(1, -60, -1);
    private static final BlockPos MAX = new BlockPos(5, -60, 1);

    private static LitematicaSchematic schematic;
    private static String viewBefore;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass())) {
            return;
        }
        if (System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        TestWorldSave save;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("tp @p 0 -59 0 -90 0");
            server.runCommand("fill 1 -60 -1 5 -60 1 stone");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                AreaSelection area = new AreaSelection();
                area.addSubRegionBox(new Box(MIN, MAX, "resume"), false);
                schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, true), "resume", message -> {
                            throw new AssertionError("Couldn't create schematic: " + message);
                        });
                place(mc);
            });
            server.runCommand("fill 1 -60 -1 5 -60 1 air");
            server.runCommand("give @p stone 64");
            context.waitTicks(20);

            viewBefore = context.computeOnClient(mc -> DataManager.getRenderLayerRange().getLayerMode().name());
            context.runOnClient(mc -> {
                Configs.Generic.RESUME_AFTER_REJOIN.setBooleanValue(true);
                AutoBuild.INSTANCE.start(mc);
            });
            // Long enough to have started, not to finish: then "log out"
            context.waitTicks(3);
            save = singleplayer.getWorldSave();
        }

        boolean stopped = context.computeOnClient(mc -> !AutoBuild.INSTANCE.isRunning());
        if (!stopped) {
            throw new AssertionError("Resume test: auto build still running after leaving the world");
        }

        try (TestSingleplayerContext again = save.open()) {
            again.getClientLevel().waitForChunksRender();
            // The test's schematic only lived in memory: put the same placement back, as Litematica does for saved ones
            context.runOnClient(ResumeTest::place);
            boolean resumed = context.waitFor(mc -> AutoBuild.INSTANCE.isRunning(), 20 * 30) >= 0;
            context.waitFor(mc -> !AutoBuild.INSTANCE.isRunning(), 20 * 60);
            context.takeScreenshot("after_resume");
            int placed = context.computeOnClient(mc -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) {
                    if (mc.level.getBlockState(pos).is(Blocks.STONE)) {
                        count++;
                    }
                }
                return count;
            });
            String viewAfter = context.computeOnClient(mc -> DataManager.getRenderLayerRange().getLayerMode().name());
            System.out.println("RESUME TEST: resumed=" + resumed + " stone placed " + placed + " of 15, layer view "
                    + viewBefore + " -> " + viewAfter);
            if (!viewAfter.equals(viewBefore)) {
                throw new AssertionError("Resume test: the layer view wasn't put back (" + viewBefore + " -> " + viewAfter + ")");
            }
            // Placements live on in memory into the next test's world: take this one away again
            context.runOnClient(mc -> DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic));
            if (!resumed || placed < 15) {
                throw new AssertionError("Resume test: resumed=" + resumed + ", placed " + placed + " of 15");
            }
        }
    }

    private static void place(Minecraft mc) {
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, MIN, "resume", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
    }
}
