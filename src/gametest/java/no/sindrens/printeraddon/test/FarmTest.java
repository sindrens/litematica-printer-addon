package no.sindrens.printeraddon.test;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.guides.Guide;
import me.aleksilassila.litematica.printer.guides.Guides;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.BlockCheck;
import no.sindrens.printeraddon.build.SchematicHelper;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.SpeedPreset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a real schematic in survival with walking and flying, and takes a screenshot every second.
 * Runs only when PRINTER_ADDON_SCHEMATIC points to a .litematic file.
 * The test gives the items the current layer needs (standing in for the shop, which isn't built yet).
 */
public class FarmTest implements FabricClientGameTest {
    private static final BlockPos ORIGIN = new BlockPos(2, -60, 2);
    private static final int MAX_TICKS = 20 * 60 * 60 * 4;
    private static final int SCREENSHOT_EVERY = 20;
    private static final int RESTOCK_EVERY = 40;
    /** Stop and write the report if no layer has finished for this long. */
    private static final int STUCK_STOP_TICKS = 20 * 60 * 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        String file = System.getenv("PRINTER_ADDON_SCHEMATIC");
        if (file == null || file.isBlank()) {
            return;
        }
        Path schematicFile = Path.of(file);
        Path reportFile = schematicFile.resolveSibling("farm-test-report.txt");

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getClientLevel().waitForChunksRender();

            server.runCommand("difficulty peaceful");
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("time set noon");
            server.runCommand("tp @p -2 -60 -2 -45 20");
            server.runCommand("give @p diamond_pickaxe");
            server.runCommand("give @p diamond_axe");
            server.runCommand("give @p diamond_shovel");
            server.runCommand("give @p diamond_hoe");

            context.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.options.renderDistance().set(10);
                LitematicaSchematic schematic = LitematicaSchematic.createFromFile(schematicFile.getParent(),
                        schematicFile.getFileName().toString());
                if (schematic == null) {
                    throw new AssertionError("Couldn't load " + schematicFile);
                }
                SchematicPlacement placement = SchematicPlacement.createFor(schematic, ORIGIN, "farm", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
            });
            context.waitTicks(60);

            // The bottom layers are only the base: put them in place at once, so the test builds the farm itself
            String prebuiltEnv = System.getenv("PRINTER_ADDON_PREBUILT_LAYERS");
            int prebuilt = prebuiltEnv == null || prebuiltEnv.isBlank() ? 6 : Integer.parseInt(prebuiltEnv.trim());
            if (prebuilt > 0) {
                List<String> commands = context.computeOnClient(mc -> prebuildCommands(prebuilt));
                for (String command : commands) {
                    server.runCommand(command);
                }
                System.out.println("FARM TEST: placed the bottom " + prebuilt + " layers with " + commands.size() + " commands");
                context.waitTicks(40);
            }

            restock(context, server);
            context.waitTicks(20);

            context.runOnClient(mc -> {
                Configs.Generic.SPEED_PRESET.setOptionListValue(SpeedPreset.FAST);
                Configs.Movement.MOVE_MODE.setOptionListValue(no.sindrens.printeraddon.config.MoveMode.FLY);
                Configs.Generic.DEBUG_LOG.setBooleanValue(true);
                Configs.Generic.DETAILED_CHAT.setBooleanValue(true);
                Configs.Generic.STATUS_FILE.setBooleanValue(true);
                AutoBuild.INSTANCE.start(mc);
            });

            long started = System.currentTimeMillis();
            int ticks = 0;
            int lastLayer = Integer.MIN_VALUE;
            int ticksOnLayer = 0;
            boolean stuck = false;
            while (ticks < MAX_TICKS && context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning())) {
                context.waitTicks(SCREENSHOT_EVERY);
                ticks += SCREENSHOT_EVERY;
                context.takeScreenshot("rec");
                if (ticks % RESTOCK_EVERY == 0) {
                    restock(context, server);
                }
                // Stuck = no block placed at all for a while (a big layer like the hopper floor takes long)
                int layer = context.computeOnClient(FarmTest::countDone);
                if (layer != lastLayer) {
                    lastLayer = layer;
                    ticksOnLayer = 0;
                } else if ((ticksOnLayer += SCREENSHOT_EVERY) > STUCK_STOP_TICKS) {
                    stuck = true;
                    break;
                }
            }
            String diagnosis = stuck ? context.computeOnClient(FarmTest::diagnose) : "";
            if (stuck) {
                context.runOnClient(mc -> AutoBuild.INSTANCE.stop(mc, "test: stuck on one layer"));
            }
            long seconds = (System.currentTimeMillis() - started) / 1000;

            context.waitTicks(20);
            context.takeScreenshot("rec");

            // An overview from above at the end
            context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
            server.runCommand("tp @p 18 -32 -14 0 55");
            context.waitTicks(60);
            context.takeScreenshot("overview");

            int gameTicks = ticks;
            String report = context.computeOnClient(mc -> report(mc, gameTicks, seconds));
            report = (stuck ? "STOPPED: nothing placed for 3 minutes\n" : "") + report;
            report = report + diagnosis;
            Files.writeString(reportFile, report);
            System.out.println(report);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gives what the current layer still needs, as much as fits in the inventory. */
    private static void restock(ClientGameTestContext context, TestServerContext server) {
        List<String> commands = context.computeOnClient(FarmTest::restockCommands);
        for (String command : commands) {
            server.runCommand(command);
        }
    }

    private static List<String> restockCommands(Minecraft mc) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        List<String> commands = new ArrayList<>();
        if (schematic == null || mc.player == null) {
            return commands;
        }

        Map<Item, Integer> needed = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        BlockState target = schematic.getBlockState(pos);
                        if (result == BlockCheck.Result.BUCKET && no.sindrens.printeraddon.build.Fluids.bucketFor(target) != null) {
                            needed.merge(no.sindrens.printeraddon.build.Fluids.bucketFor(target), 1, Integer::sum);
                            continue;
                        }
                        if (result != BlockCheck.Result.MISSING && result != BlockCheck.Result.PRINTER_FIXES
                                && !result.needsBreaking()) {
                            continue;
                        }
                        ItemStack item = MaterialCache.getInstance().getRequiredBuildItemForState(target);
                        if (!item.isEmpty()) {
                            needed.merge(item.getItem(), 1, Integer::sum);
                        }
                    }
                }
            }
        }

        // Buckets don't stack: a few at a time, like a player would carry
        for (Item bucket : new Item[]{Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.POWDER_SNOW_BUCKET}) {
            needed.computeIfPresent(bucket, (item, count) -> Math.min(count, 4));
        }
        // Spare rails for the temporary rails the rail planner puts down
        if (needed.containsKey(Items.RAIL) || needed.containsKey(Items.POWERED_RAIL)) {
            needed.merge(Items.RAIL, 8, Integer::sum);
        }

        Inventory inventory = mc.player.getInventory();
        int freeSlots = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                freeSlots++;
            } else {
                needed.computeIfPresent(stack.getItem(), (item, count) -> count - stack.getCount());
            }
        }

        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int missing = entry.getValue();
            if (missing <= 0 || freeSlots <= 0) {
                continue;
            }
            int max = entry.getKey().getDefaultMaxStackSize();
            int stacks = Math.min(freeSlots, (missing + max - 1) / max);
            freeSlots -= stacks;
            commands.add("give @p " + BuiltInRegistries.ITEM.getKey(entry.getKey()) + " " + Math.min(missing, stacks * max));
        }
        return commands;
    }

    /** Number of positions that match the schematic. */
    private static int countDone(Minecraft mc) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int done = 0;
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.OK) {
                            done++;
                        }
                    }
                }
            }
        }
        return done;
    }

    /**
     * For the unfinished blocks in the current layers: what is there, what should be there, and what each of the
     * printer's guides says about the spot.
     */
    private static String diagnose(Minecraft mc) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        StringBuilder sb = new StringBuilder("\nStuck. Player at ").append(mc.player.blockPosition().toShortString())
                .append(", flying ").append(mc.player.getAbilities().flying).append('\n');
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Guides guides = new Guides();
        int shown = 0;

        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos) || shown >= 25) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.OK || result == BlockCheck.Result.EXTRA) {
                            continue;
                        }
                        shown++;
                        SchematicBlockState state = new SchematicBlockState(mc.level, schematic, pos.immutable());
                        sb.append(pos.toShortString()).append(' ').append(result).append("\n    is ")
                                .append(state.currentState).append("\n    should be ").append(state.targetState)
                                .append("\n    distance ").append(String.format("%.1f",
                                        Math.sqrt(mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)))))
                                .append("\n    guides:");
                        for (Guide guide : guides.getInteractionGuides(state)) {
                            sb.append(' ').append(guide.getClass().getSimpleName()).append('=')
                                    .append(guide.canExecute(mc.player) ? "can" : "no")
                                    .append(guide.skipOtherGuides() ? "(stops)" : "");
                        }
                        sb.append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String report(Minecraft mc, int ticks, long seconds) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        Map<BlockCheck.Result, Integer> results = new EnumMap<>(BlockCheck.Result.class);
        Map<String, Integer> unfinished = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int total = 0;

        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState target = schematic.getBlockState(pos);
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (!target.isAir()) {
                            total++;
                        }
                        results.merge(result, 1, Integer::sum);
                        if (result != BlockCheck.Result.OK && result != BlockCheck.Result.EXTRA) {
                            unfinished.merge(result + " " + BuiltInRegistries.BLOCK.getKey(target.getBlock()).getPath(), 1, Integer::sum);
                            if (examples.size() < 30) {
                                examples.add(pos.toShortString() + " " + result + ": is " + mc.level.getBlockState(pos) + ", should be " + target);
                            }
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Farm test: ").append(AutoBuild.INSTANCE.isRunning() ? "TIMED OUT" : "auto build finished")
                .append(" after ").append(ticks / 20).append(" s game time (").append(seconds).append(" s real time)\n");
        sb.append("Schematic blocks (non-air): ").append(total).append('\n');
        sb.append("Results: ").append(results).append('\n');
        sb.append("Not finished, by type:\n");
        unfinished.forEach((k, v) -> sb.append("  ").append(v).append(' ').append(k).append('\n'));
        sb.append("Examples:\n");
        examples.forEach(e -> sb.append("  ").append(e).append('\n'));
        sb.append("Per layer:\n");
        AutoBuild.INSTANCE.stats.layers().forEach(l -> sb.append("  ").append(l).append('\n'));
        sb.append(AutoBuild.INSTANCE.stats.total()).append('\n');
        return sb.toString();
    }

    /** setblock commands (without block updates) for every block of the bottom layers of the schematic. */
    private static List<String> prebuildCommands(int layers) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        List<String> commands = new ArrayList<>();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y < area.minY() + layers && y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        BlockState target = schematic.getBlockState(new BlockPos(x, y, z));
                        if (!target.isAir() && target.getBlock() != net.minecraft.world.level.block.Blocks.MOVING_PISTON) {
                            commands.add("setblock " + x + " " + y + " " + z + " "
                                    + net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(target) + " strict");
                        }
                    }
                }
            }
        }
        return commands;
    }
}
