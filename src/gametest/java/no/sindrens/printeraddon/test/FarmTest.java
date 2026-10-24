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
    private static final int SCREENSHOT_SAVE_EVERY = 60;
    private static final int RESTOCK_EVERY = 40;
    /** Stop and write the report if no layer has finished for this long. */
    private static final int STUCK_STOP_TICKS = 20 * 60 * 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestFilter.skip(getClass())) {
            return;
        }
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
            // Thousands of hoppers checking for items every tick slow the test server down a lot; nothing has to run
            // while building, so the world's ticking is frozen (placing blocks still works)
            server.runCommand("tick freeze");
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
                // Software rendering on the test machine takes the CPU from the game server: keep drawing cheap
                mc.options.renderDistance().set(3);
                // The see-through schematic blocks cost the most to draw; the coloured outlines stay
                fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.setBooleanValue(false);
                fi.dy.masa.litematica.config.Configs.Visuals.OVERLAY_REDUCED_INNER_SIDES.setBooleanValue(true);
                mc.options.applyGraphicsPreset(net.minecraft.client.GraphicsPreset.FAST);
                mc.options.framerateLimit().set(10);
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                mc.options.ambientOcclusion().set(false);
                mc.options.entityShadows().set(false);
                mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
                mc.options.biomeBlendRadius().set(0);
                mc.options.mipmapLevels().set(0);
                mc.options.chunkSectionFadeInTime().set(0.0);
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
            // Only build part of the farm (PRINTER_ADDON_PART=5: the most northern fifth), the rest is put in place
            String partEnv = System.getenv("PRINTER_ADDON_PART");
            int part = partEnv == null || partEnv.isBlank() ? 1 : Integer.parseInt(partEnv.trim());
            if (prebuilt > 0 || part > 1) {
                // Load the whole farm area, or commands far from the player do nothing
                String area = context.computeOnClient(mc -> {
                    SchematicHelper.Area a = SchematicHelper.areas().getFirst();
                    return a.minX() + " " + a.minZ() + " " + a.maxX() + " " + a.maxZ();
                });
                server.runCommand("forceload add " + area);
                List<String> commands = context.computeOnClient(mc -> prebuildCommands(prebuilt, part));
                for (String command : commands) {
                    server.runCommand(command);
                }
                System.out.println("FARM TEST: placed the bottom " + prebuilt + " layers" + (part > 1 ? " and all but the northern 1/" + part : "")
                        + " with " + commands.size() + " commands");
                context.waitTicks(40);
            }

            // A building standing in the middle of the farm (like on the player's server): it has to fly around it
            if (System.getenv("PRINTER_ADDON_OBSTACLE") != null) {
                server.runCommand("fill 60 -58 8 63 -41 11 stone_bricks");
            }
            restock(context, server);
            context.waitTicks(20);

            context.runOnClient(mc -> {
                Configs.Generic.SPEED_PRESET.setOptionListValue(SpeedPreset.FAST);
                Configs.Movement.MOVE_MODE.setOptionListValue(no.sindrens.printeraddon.config.MoveMode.FLY);
                Configs.Generic.DEBUG_LOG.setBooleanValue(true);
                Configs.Generic.DETAILED_CHAT.setBooleanValue(true);
                Configs.Generic.STATUS_FILE.setBooleanValue(true);
                // The player's own Litematica Printer settings on their server: range 4.5, interval 12
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.setDoubleValue(4.5);
                // No /pv in singleplayer
                Configs.Shop.VAULTS.setValueFromString("");
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.setIntegerValue(12);
                String keepMoving = System.getenv("PRINTER_ADDON_KEEP_MOVING");
                if (keepMoving != null) {
                    Configs.Movement.KEEP_MOVING.setBooleanValue(!keepMoving.equals("0"));
                }
                String minInterval = System.getenv("PRINTER_ADDON_MIN_INTERVAL");
                if (minInterval != null) {
                    Configs.Speed.AUTO_SPEED_MIN_INTERVAL.setIntegerValue(Integer.parseInt(minInterval));
                }
                AutoBuild.INSTANCE.start(mc);
            });

            long started = System.currentTimeMillis();
            int ticks = 0;
            int lastLayer = Integer.MIN_VALUE;
            int ticksOnLayer = 0;
            boolean stuck = false;
            // Short test rounds: PRINTER_ADDON_TEST_MINUTES=5 stops after that many minutes of building
            String minutesEnv = System.getenv("PRINTER_ADDON_TEST_MINUTES");
            int maxTicks = minutesEnv == null || minutesEnv.isBlank() ? MAX_TICKS : Integer.parseInt(minutesEnv.trim()) * 20 * 60;
            while (ticks < maxTicks && context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning())) {
                context.waitTicks(SCREENSHOT_EVERY);
                ticks += SCREENSHOT_EVERY;
                // A picture every 3 seconds is plenty for the live view, and each one costs the slow test machine time
                if (ticks % SCREENSHOT_SAVE_EVERY == 0) {
                    context.takeScreenshot("rec");
                }
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
            if (!stuck) {
                context.runOnClient(mc -> {
                    if (AutoBuild.INSTANCE.isRunning()) {
                        AutoBuild.INSTANCE.stop(mc, "test time is up");
                    }
                });
            }
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

        // Like the infinite water bucket players have: empty buckets go, full ones come back
        if (needed.containsKey(Items.WATER_BUCKET)) {
            commands.add("clear @p minecraft:bucket");
        }
        // Full, while something needed isn't there at all: make room like a player would (one stack of the most)
        java.util.Set<Item> current = AutoBuild.INSTANCE.layers.layerNeeds().keySet();
        if (freeSlots == 0 && needed.entrySet().stream().anyMatch(e -> e.getValue() > 0 && current.contains(e.getKey())
                && inventory.countItem(e.getKey()) < 64)) {
            Map<Item, Integer> stacks = new java.util.HashMap<>();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && !stack.isDamageableItem()) {
                    stacks.merge(stack.getItem(), 1, Integer::sum);
                }
            }
            stacks.entrySet().stream().filter(e -> e.getValue() >= 2 && !current.contains(e.getKey()))
                    .max(Map.Entry.comparingByValue()).ifPresent(e -> {
                commands.add("clear @p " + BuiltInRegistries.ITEM.getKey(e.getKey()) + " " + 2 * e.getKey().getDefaultMaxStackSize());
            });
            freeSlots = 2;
        }
        // Fewest first, so every item the layer needs gets a slot before the big ones fill the inventory
        List<Map.Entry<Item, Integer>> byCount = new ArrayList<>(needed.entrySet());
        // What the current layer needs first, then the rest; fewest first within each
        java.util.Set<Item> layerNeeds = AutoBuild.INSTANCE.layers.layerNeeds().keySet();
        byCount.sort(java.util.Comparator.<Map.Entry<Item, Integer>>comparingInt(e -> layerNeeds.contains(e.getKey()) ? 0 : 1)
                .thenComparing(Map.Entry.comparingByValue()));
        for (Map.Entry<Item, Integer> entry : byCount) {
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
                .append(", flying ").append(mc.player.getAbilities().flying)
                .append(", doing: ").append(AutoBuild.INSTANCE.doing()).append('\n');
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int shown = 0;
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos) || shown >= 40) {
                            continue;
                        }
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        if (result == BlockCheck.Result.OK || result == BlockCheck.Result.EXTRA) {
                            continue;
                        }
                        shown++;
                        sb.append("  ").append(no.sindrens.printeraddon.build.Diagnose.explain(mc, schematic, pos.immutable())).append('\n');
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
        // Exactly as Litematica compares (its blue/orange markers): every spot that isn't the identical block state
        Map<String, Integer> exact = new TreeMap<>();
        List<String> exactExamples = new ArrayList<>();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState target = schematic.getBlockState(pos);
                        BlockState current = mc.level.getBlockState(pos);
                        if (target.equals(current) || (target.isAir() && current.isAir())) {
                            continue;
                        }
                        String why;
                        if (target.getBlock() == net.minecraft.world.level.block.Blocks.MOVING_PISTON) {
                            why = "moving piston in the schematic (saved mid-push, not a placeable block)";
                        } else if (target.getBlock() != current.getBlock()) {
                            why = BuiltInRegistries.BLOCK.getKey(target.getBlock()).getPath() + " is "
                                    + BuiltInRegistries.BLOCK.getKey(current.getBlock()).getPath();
                        } else {
                            List<String> props = new ArrayList<>();
                            for (var property : target.getProperties()) {
                                if (!target.getValue(property).equals(current.getValue(property))) {
                                    props.add(property.getName());
                                }
                            }
                            why = BuiltInRegistries.BLOCK.getKey(target.getBlock()).getPath() + " differs in " + props;
                        }
                        exact.merge(why, 1, Integer::sum);
                        if (exactExamples.size() < 15) {
                            exactExamples.add(pos.toShortString() + ": " + current + " vs " + target);
                        }
                    }
                }
            }
        }
        sb.append("Exact differences (what Litematica marks):\n");
        exact.forEach((k, v) -> sb.append("  ").append(v).append(' ').append(k).append('\n'));
        exactExamples.forEach(e -> sb.append("    ").append(e).append('\n'));
        sb.append("Per layer:\n");
        AutoBuild.INSTANCE.stats.layers().forEach(l -> sb.append("  ").append(l).append('\n'));
        sb.append(AutoBuild.INSTANCE.stats.total()).append('\n');
        return sb.toString();
    }

    /**
     * setblock commands (without block updates) for every block of the bottom layers of the schematic, and (with
     * part > 1) of everything south of the northern 1/part of it. Read from the schematic itself: Litematica's
     * schematic world only has the chunks near the player.
     */
    private static List<String> prebuildCommands(int layers, int part) {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().getFirst();
        LitematicaSchematic schematic = placement.getSchematic();
        BlockPos origin = placement.getOrigin();
        SchematicHelper.Area whole = SchematicHelper.areas().getFirst();
        int keepUntilZ = whole.minZ() + (whole.maxZ() - whole.minZ() + 1) / part;
        List<String> commands = new ArrayList<>();
        for (String region : schematic.getAreas().keySet()) {
            BlockPos at = schematic.getSubRegionPosition(region);
            BlockPos size = schematic.getAreaSize(region);
            BlockPos min = origin.offset(at.getX() + Math.min(0, size.getX() + 1), at.getY() + Math.min(0, size.getY() + 1),
                    at.getZ() + Math.min(0, size.getZ() + 1));
            var container = schematic.getSubRegionContainer(region);
            net.minecraft.core.Vec3i dims = container.getSize();
            for (int dy = 0; dy < dims.getY(); dy++) {
                for (int dx = 0; dx < dims.getX(); dx++) {
                    for (int dz = 0; dz < dims.getZ(); dz++) {
                        int x = min.getX() + dx, y = min.getY() + dy, z = min.getZ() + dz;
                        if (y >= whole.minY() + layers && z < keepUntilZ) {
                            continue;
                        }
                        BlockState target = container.get(dx, dy, dz);
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
