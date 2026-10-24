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
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.BlockCheck;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.MoveMode;

import java.util.ArrayList;
import java.util.List;

/**
 * A layer around a middle that's built already and walled in high, like the player's farm (walls of hoppers 10 above
 * the layer, the middle built by the player): the lanes go round the middle, one side after the other, never up over
 * the walls for each lane. A log had the lanes climb from 30 blocks before the wall to 11 above the layer, place almost
 * nothing on either side and leave 1273 blocks for spot by spot. Two of the walls stand where the lanes' lines would
 * be when centred on the build: those lanes are laid beside them.
 */
public class MiddleTest implements FabricClientGameTest {
    private static final int SIZE = 45;
    private static final int Y = -60;
    /** The middle, its walls on its edge (x and z from-to): built already. */
    private static final int IN_FROM = 15;
    private static final int IN_TO = 29;
    private static final int WALL_TOP = Y + 11;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass()) || System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.setBooleanValue(false);
                mc.options.framerateLimit().set(10);
            });
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set noon");
            server.runCommand("forceload add 0 0 " + (SIZE - 1) + " " + (SIZE - 1));

            // The schematic: two layers of stone. The world: the floor, the middle of the second layer, and the
            // player's own walls round the middle on top of it, up to 11 above the floor
            context.runOnClient(MiddleTest::placeSchematic);
            server.runCommand("fill 0 " + Y + " 0 " + (SIZE - 1) + " " + Y + " " + (SIZE - 1) + " stone");
            server.runCommand("fill " + IN_FROM + " " + (Y + 1) + " " + IN_FROM + " " + IN_TO + " " + (Y + 1) + " " + IN_TO + " stone");
            server.runCommand("fill " + IN_FROM + " " + (Y + 2) + " " + IN_FROM + " " + IN_TO + " " + WALL_TOP + " " + IN_TO + " cobblestone");
            server.runCommand("fill " + (IN_FROM + 1) + " " + (Y + 2) + " " + (IN_FROM + 1) + " " + (IN_TO - 1) + " " + WALL_TOP + " "
                    + (IN_TO - 1) + " air");
            server.runCommand("tp @p -2 " + (Y + 2) + " -2 -45 30");
            server.runCommand("gamemode survival @p");
            server.runCommand("fly");
            server.runCommand("give @p diamond_pickaxe");
            server.runCommand("give @p diamond_shovel");
            server.runCommand("give @p stone " + 64 * 29);
            context.waitTicks(40);

            context.runOnClient(mc -> {
                Configs.Generic.DEBUG_LOG.setBooleanValue(true);
                Configs.Movement.MOVE_MODE.setOptionListValue(MoveMode.FLY);
                Configs.Shop.VAULTS.setValueFromString("");
                Configs.Shop.AUTO_BUY.setBooleanValue(false);
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.setDoubleValue(4.5);
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.setIntegerValue(2);
                AutoBuild.INSTANCE.start(mc);
            });

            long started = System.currentTimeMillis();
            int ticks = 0;
            int limit = 20 * 60 * 6;
            // Highest the player got while flying along a lane (not to one), and how often it went up over the walls
            double highestOnLane = Double.NEGATIVE_INFINITY;
            int overWalls = 0;
            boolean up = false;
            while (ticks < limit && context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning())) {
                context.waitTicks(5);
                ticks += 5;
                double[] at = context.computeOnClient(mc -> new double[]{mc.player.getY(),
                        AutoBuild.INSTANCE.doing().startsWith("lane") ? 1 : 0});
                if (at[1] > 0) {
                    highestOnLane = Math.max(highestOnLane, at[0]);
                }
                boolean nowUp = at[0] > Y + 6;
                if (nowUp && !up) {
                    overWalls++;
                }
                up = nowUp;
                if (ticks % 200 == 0) {
                    context.takeScreenshot("middle");
                    int t = ticks;
                    context.runOnClient(mc -> System.out.println("MIDDLE TEST: " + t / 20 + " s, player at "
                            + mc.player.blockPosition().toShortString() + ", " + AutoBuild.INSTANCE.layers.status()
                            + ", " + AutoBuild.INSTANCE.doing()));
                }
            }
            boolean finished = !context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning());
            if (!finished) {
                context.runOnClient(mc -> AutoBuild.INSTANCE.stop(mc, "test time is up"));
            }
            context.takeScreenshot("middle-end");
            List<String> problems = context.computeOnClient(MiddleTest::problems);
            String result = (finished ? "finished" : "NOT finished") + " after " + ticks / 20 + " s game time ("
                    + (System.currentTimeMillis() - started) / 1000 + " s real), " + problems.size() + " blocks wrong"
                    + (problems.isEmpty() ? "" : ": " + problems.subList(0, Math.min(10, problems.size())))
                    + String.format(java.util.Locale.ROOT, ", highest on a lane %.1f (hover height %d), up over the walls %d times",
                    highestOnLane, Y + 2, overWalls);
            System.out.println("MIDDLE TEST: " + result);
            // Along a lane at most a little over the hover height; over the walls only to get to the other side
            if (!finished || !problems.isEmpty() || highestOnLane > Y + 2 + 2.5 || overWalls > 4) {
                throw new AssertionError("Middle test: " + result);
            }
        }
    }

    private static void placeSchematic(Minecraft mc) {
        BlockPos min = new BlockPos(0, Y, 0);
        BlockPos max = new BlockPos(SIZE - 1, Y + 1, SIZE - 1);
        AreaSelection area = new AreaSelection();
        area.addSubRegionBox(new Box(min, max, "square"), false);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "test");
        var container = schematic.getSubRegionContainer("square");
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                container.set(x, 0, z, Blocks.STONE.defaultBlockState());
                container.set(x, 1, z, Blocks.STONE.defaultBlockState());
            }
        }
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, min, "square", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
    }

    /** Blocks of the build that aren't right. */
    private static List<String> problems(Minecraft mc) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        List<String> problems = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int y = Y; y <= Y + 1; y++) {
                for (int z = 0; z < SIZE; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                    if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                        problems.add(pos.toShortString() + " " + result + " " + mc.level.getBlockState(pos).getBlock());
                    }
                }
            }
        }
        return problems;
    }
}
