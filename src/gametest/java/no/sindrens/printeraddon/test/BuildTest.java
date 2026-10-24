package no.sindrens.printeraddon.test;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.BlockCheck;
import no.sindrens.printeraddon.config.Configs;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a small structure, saves it as a schematic, breaks it on purpose (wrong block, wrong direction,
 * missing blocks, an extra block) and checks that auto build puts everything right.
 */
public class BuildTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(1, -60, -1);
    private static final BlockPos MAX = new BlockPos(3, -58, 1);
    private static final BlockPos EXTRA = new BlockPos(3, -59, -1);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass())) {
            return;
        }
        // A farm run (PRINTER_ADDON_SCHEMATIC set) goes straight to the farm
        if (System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();

            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set day");
            server.runCommand("tp @p 0 -60 0 -90 0");

            // The finished build: a stone floor, an observer, stairs, planks, and cobblestone on top
            server.runCommand("fill 1 -60 -1 3 -60 1 stone");
            server.runCommand("setblock 1 -59 -1 observer[facing=north]");
            server.runCommand("setblock 2 -59 0 oak_stairs[facing=east]");
            server.runCommand("setblock 3 -59 1 oak_planks");
            server.runCommand("setblock 2 -58 0 cobblestone");
            context.waitTicks(10);

            context.runOnClient(BuildTest::saveAndPlaceSchematic);
            context.waitTicks(20);

            // Break it on purpose
            // A wrong block of a kind the schematic uses (a block of another kind that was already there is the
            // player's own building: left alone, see AutoBuild#isConflict)
            server.runCommand("setblock 1 -60 -1 cobblestone");                // wrong block
            server.runCommand("setblock 2 -60 0 air");                         // missing
            server.runCommand("setblock 1 -59 -1 observer[facing=south]");     // wrong direction
            server.runCommand("setblock 2 -59 0 oak_stairs[facing=west]");     // wrong direction
            server.runCommand("setblock 3 -59 -1 cobblestone");                // extra (schematic has air)
            server.runCommand("setblock 2 -58 0 air");                         // missing

            server.runCommand("give @p diamond_pickaxe");
            server.runCommand("give @p diamond_axe");
            server.runCommand("give @p diamond_shovel");
            server.runCommand("give @p stone 64");
            server.runCommand("give @p observer 16");
            server.runCommand("give @p oak_stairs 16");
            server.runCommand("give @p oak_planks 16");
            server.runCommand("give @p cobblestone 16");
            context.waitTicks(20);

            context.runOnClient(mc -> {
                Configs.Generic.DEBUG_LOG.setBooleanValue(true);
                AutoBuild.INSTANCE.start(mc);
            });
            context.waitFor(mc -> !AutoBuild.INSTANCE.isRunning(), 20 * 90);
            context.waitTicks(10);
            context.takeScreenshot("after_auto_build");

            List<String> problems = context.computeOnClient(BuildTest::findProblems);
            boolean extraStillThere = context.computeOnClient(mc -> !mc.level.getBlockState(EXTRA).isAir());
            if (!problems.isEmpty()) {
                throw new AssertionError("Auto build left problems: " + problems);
            }
            if (!extraStillThere) {
                throw new AssertionError("Auto build broke the extra block; only the 'clear extra' key should do that");
            }

            // The clear-extra key removes the cobblestone that isn't in the schematic
            context.runOnClient(mc -> AutoBuild.INSTANCE.clearExtraNow());
            context.waitFor(mc -> !AutoBuild.INSTANCE.isManualTaskRunning(), 20 * 30);
            context.waitTicks(10);
            boolean extraGone = context.computeOnClient(mc -> mc.level.getBlockState(EXTRA).isAir());
            if (!extraGone) {
                throw new AssertionError("Clear extra blocks didn't remove the extra block");
            }
            context.takeScreenshot("after_clear_extra");
        }
    }

    private static void saveAndPlaceSchematic(Minecraft mc) {
        AreaSelection area = new AreaSelection();
        area.addSubRegionBox(new Box(MIN, MAX, "test"), false);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, true), "test", message -> {
                    throw new AssertionError("Couldn't create schematic: " + message);
                });
        if (schematic == null) {
            throw new AssertionError("Couldn't create schematic");
        }

        SchematicPlacement placement = SchematicPlacement.createFor(schematic, area.getEffectiveOrigin(), "test", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
    }

    private static List<String> findProblems(Minecraft mc) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        List<String> problems = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) {
            if (pos.equals(EXTRA)) {
                continue;
            }
            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            if (result != BlockCheck.Result.OK) {
                problems.add(pos.toShortString() + " " + result + " is " + mc.level.getBlockState(pos)
                        + " should be " + schematic.getBlockState(pos));
            }
        }

        if (mc.level.getBlockState(new BlockPos(1, -60, -1)).getBlock() != Blocks.STONE) {
            problems.add("cobblestone at 1 -60 -1 wasn't replaced with stone");
        }
        return problems;
    }
}
