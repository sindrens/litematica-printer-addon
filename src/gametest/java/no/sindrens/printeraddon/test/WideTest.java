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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.BlockCheck;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.MoveMode;

import java.util.ArrayList;
import java.util.List;

/**
 * A build wider than what's loaded around the player, with a farm at work in it: a strip 200 blocks long (both ends
 * are never loaded at once with the render distance at 2), stone with pistons that a clock under them pushes all the
 * time. Its second layer must get finished, with no flying from one end to the other and back (a log had that for
 * minutes on the player's 119 x 119 farm: the far corner never loaded, and moving pistons counted as wrong blocks).
 * Pillars of the player's own stand at both ends of the lane, where the player would fly.
 */
public class WideTest implements FabricClientGameTest {
    private static final int LENGTH = 200;
    private static final int Y = -60;
    /** A clock with a piston on it every this many blocks. */
    private static final int CLOCK_EVERY = 25;
    /** Where a wall of the player's stands across the strip, above it. */
    private static final int WALL_X = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass()) || System.getenv("PRINTER_ADDON_SCHEMATIC") != null) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(mc -> {
                mc.options.renderDistance().set(2);
                mc.options.simulationDistance().set(5);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.setBooleanValue(false);
                mc.options.framerateLimit().set(10);
            });
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set noon");
            server.runCommand("forceload add 0 0 " + (LENGTH - 1) + " 2");

            // The schematic: stone floor, a second layer of stone with a piston (facing up) on each clock's output
            context.runOnClient(WideTest::placeSchematic);
            // The world: the floor with the clocks in it (two observers watching each other power the stone under
            // the piston), the second layer left to build
            server.runCommand("fill 0 " + Y + " 0 " + (LENGTH - 1) + " " + Y + " 2 stone");
            for (int x = 5; x + 2 < LENGTH; x += CLOCK_EVERY) {
                server.runCommand("setblock " + x + " " + Y + " 0 observer[facing=east]");
                server.runCommand("setblock " + (x + 1) + " " + Y + " 0 observer[facing=west]");
            }
            // The player's own pillars just past both ends of the strip, up through the height the player flies at:
            // the lane's ends can't be got to (a log had a lane end inside a farm's column, and the player stood
            // still below the layer there every tick for 20 s). The far one isn't loaded when the lane is planned
            server.runCommand("fill -1 " + Y + " 1 -1 " + (Y + 3) + " 1 cobblestone");
            server.runCommand("fill " + LENGTH + " " + Y + " 1 " + LENGTH + " " + (Y + 3) + " 1 cobblestone");
            // A wall across the lane where the player flies, 15 wide (like the walls of hoppers in the middle of the
            // player's farm): the lane goes over it, not around it (around, it went 9 blocks to the side)
            server.runCommand("fill " + WALL_X + " " + (Y + 2) + " -6 " + WALL_X + " " + (Y + 3) + " 8 cobblestone");
            server.runCommand("tp @p 2 " + (Y + 3) + " 1 -90 30");
            server.runCommand("gamemode survival @p");
            server.runCommand("fly");
            server.runCommand("give @p diamond_pickaxe");
            server.runCommand("give @p diamond_shovel");
            server.runCommand("give @p stone 704");
            server.runCommand("give @p piston 16");
            server.runCommand("give @p observer 16");
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
            int limit = 20 * 60 * 8;
            // How far from the lane's line the player got near the wall while flying the lane
            double offLane = 0;
            while (ticks < limit && context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning())) {
                context.waitTicks(5);
                ticks += 5;
                offLane = Math.max(offLane, context.computeOnClient(mc -> AutoBuild.INSTANCE.doing().startsWith("lane")
                        && Math.abs(mc.player.getX() - (WALL_X + 0.5)) < 12 ? Math.abs(mc.player.getZ() - 1.5) : 0.0));
                if (ticks % 200 == 0) {
                    context.takeScreenshot("wide");
                    int at = ticks;
                    context.runOnClient(mc -> System.out.println("WIDE TEST: " + at / 20 + " s, player at "
                            + mc.player.blockPosition().toShortString() + ", " + AutoBuild.INSTANCE.layers.status()
                            + ", " + AutoBuild.INSTANCE.doing()));
                }
            }
            boolean finished = !context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning());
            if (!finished) {
                context.runOnClient(mc -> AutoBuild.INSTANCE.stop(mc, "test time is up"));
            }
            context.takeScreenshot("wide-end");
            // The far end is loaded for the check only with the player there
            server.runCommand("tp @p " + (LENGTH / 2) + " " + (Y + 3) + " 1");
            context.waitTicks(40);
            List<String> problems = new ArrayList<>();
            for (int from = 0; from < LENGTH; from += 60) {
                server.runCommand("tp @p " + (from + 30) + " " + (Y + 3) + " 1");
                context.waitTicks(60);
                int start = from;
                problems.addAll(context.computeOnClient(mc -> problems(mc, start, Math.min(LENGTH, start + 60))));
            }
            String result = (finished ? "finished" : "NOT finished") + " after " + ticks / 20 + " s game time ("
                    + (System.currentTimeMillis() - started) / 1000 + " s real), " + problems.size() + " blocks wrong"
                    + (problems.isEmpty() ? "" : ": " + problems.subList(0, Math.min(10, problems.size())))
                    + String.format(java.util.Locale.ROOT, ", at most %.1f from the lane at the wall", offLane);
            System.out.println("WIDE TEST: " + result);
            if (!finished || !problems.isEmpty() || offLane > 2.5) {
                throw new AssertionError("Wide test: " + result);
            }
        }
    }

    private static void placeSchematic(Minecraft mc) {
        BlockPos min = new BlockPos(0, Y, 0);
        BlockPos max = new BlockPos(LENGTH - 1, Y + 1, 2);
        AreaSelection area = new AreaSelection();
        area.addSubRegionBox(new Box(min, max, "strip"), false);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "test");
        var container = schematic.getSubRegionContainer("strip");
        for (int x = 0; x < LENGTH; x++) {
            for (int z = 0; z < 3; z++) {
                container.set(x, 0, z, Blocks.STONE.defaultBlockState());
                container.set(x, 1, z, Blocks.STONE.defaultBlockState());
            }
        }
        for (int x = 5; x + 2 < LENGTH; x += CLOCK_EVERY) {
            container.set(x, 0, 0, Blocks.OBSERVER.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
            container.set(x + 1, 0, 0, Blocks.OBSERVER.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.WEST));
            // Over the observers: air (the clock needs nothing there); on the powered stone: the piston
            container.set(x, 1, 0, Blocks.AIR.defaultBlockState());
            container.set(x + 1, 1, 0, Blocks.AIR.defaultBlockState());
            container.set(x + 2, 1, 0, Blocks.PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP));
        }
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, min, "strip", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
    }

    /** Blocks of the strip (between these x) that aren't right, with the player near them. */
    private static List<String> problems(Minecraft mc, int fromX, int toX) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        List<String> problems = new ArrayList<>();
        for (int x = fromX; x < toX; x++) {
            for (int y = Y; y <= Y + 1; y++) {
                for (int z = 0; z < 3; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                    if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                        BlockState is = mc.level.getBlockState(pos);
                        problems.add(pos.toShortString() + " " + result + " " + is.getBlock());
                    }
                }
            }
        }
        return problems;
    }
}
