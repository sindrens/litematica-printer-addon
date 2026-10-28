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

            // PRINTER_ADDON_WATCH=1: run on your own PC to watch it, with your own graphics settings
            boolean watch = System.getenv("PRINTER_ADDON_WATCH") != null;
            context.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                if (watch) {
                    Configs.Generic.SHOW_HUD.setBooleanValue(true);
                }
            });
            if (!watch) context.runOnClient(mc -> {
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
            });
            context.runOnClient(mc -> {
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

            // PRINTER_ADDON_GROWN=30: the cactus of the first layer still to build are in place already (the rest of that
            // layer not), and 30 % of them have grown 2 blocks up with a flower on top, like on the player's tower after
            // a stop between a cactus layer and its sand. Ticks run (no freeze), so a grown cactus falls once the sand
            // beside it goes in
            String grownEnv = System.getenv("PRINTER_ADDON_GROWN");
            if (grownEnv != null && !grownEnv.isBlank()) {
                int percent = Integer.parseInt(grownEnv.trim());
                server.runCommand("tick unfreeze");
                String area = context.computeOnClient(mc -> {
                    SchematicHelper.Area a = SchematicHelper.areas().getFirst();
                    return a.minX() + " " + a.minZ() + " " + a.maxX() + " " + a.maxZ();
                });
                server.runCommand("forceload add " + area);
                List<String> commands = context.computeOnClient(mc -> grownCommands(prebuilt, percent));
                for (String command : commands) {
                    server.runCommand(command);
                }
                System.out.println("FARM TEST: cactus of the first layer to build in place, " + percent + " % grown up ("
                        + commands.size() + " commands)");
                context.waitTicks(40);
            }

            // PRINTER_ADDON_WRONG=30: on 30 % of the string spots of the first layer to build, a cactus grown up from the
            // cactus layer below through its growth spot, into the string spot and the sand spot above it (wrong blocks,
            // and the layer's cactus beside them can't go in until they're broken), like on the player's tower
            String wrongEnv = System.getenv("PRINTER_ADDON_WRONG");
            if (wrongEnv != null && !wrongEnv.isBlank()) {
                int percent = Integer.parseInt(wrongEnv.trim());
                server.runCommand("tick unfreeze");
                List<String> commands = context.computeOnClient(mc -> wrongCommands(prebuilt, percent));
                for (String command : commands) {
                    server.runCommand(command);
                }
                System.out.println("FARM TEST: " + commands.size() / 3 + " wrong cactus columns (" + percent + " %)");
                context.waitTicks(40);
            }

            // A building standing in the middle of the farm (like on the player's server): it has to fly around it
            if (System.getenv("PRINTER_ADDON_OBSTACLE") != null) {
                server.runCommand("fill 60 -58 8 63 -41 11 stone_bricks");
            }
            // PRINTER_ADDON_SHOP=1: nothing is given, everything is bought in the fake /shop and kept in /pv 1 and 2
            // (to test buying in bulk into the vaults)
            boolean shop = System.getenv("PRINTER_ADDON_SHOP") != null;
            if (shop) {
                FakeShop.reset();
                TestCommands.VAULTS.clear();
                context.runOnClient(mc -> {
                    Configs.Shop.SHOP_COMMAND.setValueFromString("shop");
                    Configs.Shop.SHOP_SECTION.setValueFromString("minecraft:paper");
                    Configs.Shop.PROBE_BUY_SCREEN.setBooleanValue(true);
                    no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.start(mc);
                });
                int scanTicks = 0;
                while (scanTicks < 20 * 60 * 5 && context.computeOnClient(mc -> no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.isRunning())) {
                    context.waitTicks(10);
                    scanTicks += 10;
                }
                System.out.println("FARM TEST: shop scanned in " + scanTicks / 20 + " s");
            } else {
                restock(context, server);
            }
            // PRINTER_ADDON_JUNK=1: cactus flowers from breaking and blocks of the player's own that the build doesn't use
            // in the inventory, like on the player's tower: the flowers are thrown out, the blocks go into a vault on a
            // supply trip short of room
            if (System.getenv("PRINTER_ADDON_JUNK") != null) {
                for (String command : List.of("give @p cactus_flower 192", "give @p iron_bars 48", "give @p purple_concrete 49",
                        "give @p bamboo_sign 2")) {
                    server.runCommand(command);
                }
                System.out.println("FARM TEST: cactus flowers and blocks the build doesn't use in the inventory");
            }
            // PRINTER_ADDON_EXTRA=1: blocks that aren't the schematic's in its area (stone where it has air, dirt and
            // farmland where its floor goes), with "Break blocks not in the schematic" on: all broken, the floor put in
            boolean extra = System.getenv("PRINTER_ADDON_EXTRA") != null;
            if (extra) {
                for (String command : List.of("setblock 5 -60 5 stone", "setblock 9 -60 12 cobblestone", "setblock 7 -58 7 stone",
                        "setblock 14 -58 3 oak_planks", "setblock 12 -59 4 dirt", "setblock 4 -59 13 farmland", "fill 6 -59 6 11 -59 11 stone")) {
                    server.runCommand(command);
                }
                System.out.println("FARM TEST: 6 blocks that aren't the schematic's put in its area");
            }
            context.waitTicks(20);

            context.runOnClient(mc -> {
                Configs.Breaking.BREAK_NOT_IN_SCHEMATIC.setBooleanValue(extra);
                Configs.Generic.SPEED_PRESET.setOptionListValue(SpeedPreset.NORMAL);
                Configs.Movement.MOVE_MODE.setOptionListValue(no.sindrens.printeraddon.config.MoveMode.FLY);
                Configs.Generic.DEBUG_LOG.setBooleanValue(true);
                Configs.Generic.DETAILED_CHAT.setBooleanValue(true);
                Configs.Generic.STATUS_FILE.setBooleanValue(true);
                // The player's own Litematica Printer settings on their server: range 4.5, interval 12
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.setDoubleValue(4.5);
                // No /pv in singleplayer, except the test's own (PRINTER_ADDON_SHOP)
                Configs.Shop.VAULTS.setValueFromString(shop ? "1,2" : "");
                Configs.Shop.AUTO_BUY.setBooleanValue(shop || Configs.Shop.AUTO_BUY.getBooleanValue());
                // The player's interval on their server now (PRINTER_ADDON_INTERVAL to try another)
                String interval = System.getenv("PRINTER_ADDON_INTERVAL");
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.setIntegerValue(
                        interval == null || interval.isBlank() ? 3 : Integer.parseInt(interval.trim()));
                // PRINTER_ADDON_LANES=0: spot by spot only (to compare with flying in lanes)
                String lanes = System.getenv("PRINTER_ADDON_LANES");
                if (lanes != null) {
                    Configs.Movement.LANES.setBooleanValue(!lanes.equals("0"));
                }
                String keepMoving = System.getenv("PRINTER_ADDON_KEEP_MOVING");
                if (keepMoving != null) {
                    Configs.Movement.KEEP_MOVING.setBooleanValue(!keepMoving.equals("0"));
                }
                String minInterval = System.getenv("PRINTER_ADDON_MIN_INTERVAL");
                if (minInterval != null) {
                    Configs.Speed.AUTO_SPEED_MIN_INTERVAL.setIntegerValue(Integer.parseInt(minInterval));
                }
                // PRINTER_ADDON_TOGETHER=0: one layer at a time (two at once where the upper one stands on the lower one
                // is the default)
                String together = System.getenv("PRINTER_ADDON_TOGETHER");
                if (together != null) {
                    Configs.Layers.LAYERS_TOGETHER.setBooleanValue(!together.equals("0"));
                }
                // PRINTER_ADDON_OLD_ROUTE=1: as before 0.17.16 (route and clicks), to compare
                if (System.getenv("PRINTER_ADDON_OLD_ROUTE") != null) {
                    no.sindrens.printeraddon.config.Configs.Speed.CLICK_SEEN_FACE.setBooleanValue(false);
                }
                // PRINTER_ADDON_BREAK=1: the farm built (PRINTER_ADDON_PREBUILT_LAYERS=99) is broken again by Auto break
                if (System.getenv("PRINTER_ADDON_BREAK") != null) {
                    // PRINTER_ADDON_BREAK_SKIP=cactus,sand: only the string is broken (like the player's runs)
                    String skip = System.getenv("PRINTER_ADDON_BREAK_SKIP");
                    if (skip != null) {
                        Configs.Breaking.AUTO_BREAK_SKIP.setValueFromString(skip);
                    }
                    List<int[]> boxes = new java.util.ArrayList<>();
                    for (SchematicHelper.Area a : SchematicHelper.areas()) {
                        boxes.add(new int[]{a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()});
                    }
                    no.sindrens.printeraddon.build.AreaBreaker.INSTANCE.start(mc, boxes);
                } else {
                    AutoBuild.INSTANCE.start(mc);
                    // PRINTER_ADDON_STOP_AFTER_LAYER=1: "stop after this layer" pressed right away
                    if (System.getenv("PRINTER_ADDON_STOP_AFTER_LAYER") != null) {
                        AutoBuild.INSTANCE.toggleStopAfterLayer(mc);
                    }
                }
            });
            if (System.getenv("PRINTER_ADDON_BREAK") != null) {
                breakTest(context, reportFile);
                return;
            }

            long started = System.currentTimeMillis();
            int ticks = 0;
            int lastLayer = Integer.MIN_VALUE;
            int ticksOnLayer = 0;
            boolean stuck = false;
            // Short test rounds: PRINTER_ADDON_TEST_MINUTES=5 stops after that many minutes of building
            String minutesEnv = System.getenv("PRINTER_ADDON_TEST_MINUTES");
            int maxTicks = minutesEnv == null || minutesEnv.isBlank() ? MAX_TICKS : Integer.parseInt(minutesEnv.trim()) * 20 * 60;
            int buildingLayer = context.computeOnClient(mc -> AutoBuild.INSTANCE.layers.getLayer());
            int layerStartTicks = 0;
            while (ticks < maxTicks && context.computeOnClient(mc -> AutoBuild.INSTANCE.isRunning())) {
                context.waitTicks(SCREENSHOT_EVERY);
                ticks += SCREENSHOT_EVERY;
                // Each finished layer: its time, and a picture of it
                int nowLayer = context.computeOnClient(mc -> AutoBuild.INSTANCE.layers.getLayer());
                if (nowLayer != buildingLayer) {
                    System.out.println("FARM TEST: layer Y " + buildingLayer + " done after " + (ticks - layerStartTicks) / 20
                            + " s game time (" + ticks / 20 + " s in all, " + (System.currentTimeMillis() - started) / 1000 + " s real)");
                    context.takeScreenshot("layer-" + buildingLayer);
                    buildingLayer = nowLayer;
                    layerStartTicks = ticks;
                }
                // A picture every 3 seconds is plenty for the live view, and each one costs the slow test machine time
                if (ticks % SCREENSHOT_SAVE_EVERY == 0) {
                    context.takeScreenshot("rec");
                }
                if (ticks % RESTOCK_EVERY == 0 && !shop) {
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

    /** Auto break of the whole schematic area: how long it took, how many were broken and how many are left. */
    private static void breakTest(ClientGameTestContext context, java.nio.file.Path reportFile) throws IOException {
        long started = System.currentTimeMillis();
        String minutesEnv = System.getenv("PRINTER_ADDON_TEST_MINUTES");
        int maxTicks = minutesEnv == null || minutesEnv.isBlank() ? MAX_TICKS : Integer.parseInt(minutesEnv.trim()) * 20 * 60;
        int before = context.computeOnClient(FarmTest::blocksInArea);
        int ticks = 0;
        while (ticks < maxTicks && context.computeOnClient(mc -> no.sindrens.printeraddon.build.AreaBreaker.INSTANCE.isRunning())) {
            context.waitTicks(SCREENSHOT_EVERY);
            ticks += SCREENSHOT_EVERY;
            if (ticks % (20 * 20) == 0) {
                int left = context.computeOnClient(FarmTest::blocksInArea);
                System.out.println("FARM TEST: auto break: " + (before - left) + " of " + before + " gone after " + ticks / 20 + " s game time ("
                        + (System.currentTimeMillis() - started) / 1000 + " s real)");
            }
            if (ticks % SCREENSHOT_SAVE_EVERY == 0) {
                context.takeScreenshot("rec");
            }
        }
        context.runOnClient(mc -> no.sindrens.printeraddon.build.AreaBreaker.INSTANCE.stop(mc, "test time is up"));
        int left = context.computeOnClient(FarmTest::blocksInArea);
        int gameTicks = ticks;
        String report = "AUTO BREAK: " + before + " blocks, " + (before - left) + " gone, " + left + " left, " + gameTicks / 20 + " s game time, "
                + (System.currentTimeMillis() - started) / 1000 + " s real, "
                + Math.round((before - left) * 60.0 / Math.max(1, gameTicks / 20.0)) + " a minute | "
                + context.computeOnClient(mc -> AutoBuild.INSTANCE.breaker.summary()) + "\n";
        Files.writeString(reportFile, report);
        System.out.println("FARM TEST: " + report);
    }

    /** Blocks (not air, not liquid) in the schematic's area. */
    private static int blocksInArea(net.minecraft.client.Minecraft mc) {
        int count = 0;
        for (SchematicHelper.Area a : SchematicHelper.areas()) {
            for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ())) {
                net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
                if (!state.isAir() && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
                    count++;
                }
            }
        }
        return count;
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
                        // Water to come once the block is placed (waterlogged leaves): the bucket is ready before,
                        // like the player's infinite water bucket (else each lane passed its water with none)
                        if (result == BlockCheck.Result.MISSING && no.sindrens.printeraddon.build.PlacementOrder.holdsWater(target)
                                && no.sindrens.printeraddon.build.Fluids.bucketFor(target) != null) {
                            needed.merge(no.sindrens.printeraddon.build.Fluids.bucketFor(target), 1, Integer::sum);
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
    /**
     * The cactus of the first layer still to build (the one above the prebuilt ones), and on this share of them two more
     * cactus and a flower on top: grown up out of the layer.
     */
    private static List<String> grownCommands(int prebuilt, int percent) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        SchematicHelper.Area whole = SchematicHelper.areas().getFirst();
        int y = whole.minY() + prebuilt;
        List<String> commands = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = whole.minX(); x <= whole.maxX(); x++) {
            for (int z = whole.minZ(); z <= whole.maxZ(); z++) {
                if (!schematic.getBlockState(pos.set(x, y, z)).is(net.minecraft.world.level.block.Blocks.CACTUS)) {
                    continue;
                }
                commands.add("setblock " + x + " " + y + " " + z + " cactus");
                if (Math.floorMod(x * 73856093 ^ z * 19349663, 100) < percent) {
                    commands.add("setblock " + x + " " + (y + 1) + " " + z + " cactus");
                    commands.add("setblock " + x + " " + (y + 2) + " " + z + " cactus");
                    commands.add("setblock " + x + " " + (y + 3) + " " + z + " cactus_flower");
                }
            }
        }
        return commands;
    }

    /** Cactus standing in the string spots of the first layer to build and the sand spots above them (wrong blocks). */
    private static List<String> wrongCommands(int prebuilt, int percent) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        SchematicHelper.Area whole = SchematicHelper.areas().getFirst();
        int y = whole.minY() + prebuilt;
        List<String> commands = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = whole.minX(); x <= whole.maxX(); x++) {
            for (int z = whole.minZ(); z <= whole.maxZ(); z++) {
                if (!schematic.getBlockState(pos.set(x, y, z)).is(net.minecraft.world.level.block.Blocks.TRIPWIRE)
                        || !schematic.getBlockState(pos.set(x, y - 1, z)).isAir()
                        || !schematic.getBlockState(pos.set(x, y - 2, z)).is(net.minecraft.world.level.block.Blocks.CACTUS)
                        || Math.floorMod(x * 73856093 ^ z * 19349663, 100) >= percent) {
                    continue;
                }
                // On sand that went into the growth spot by a wrong click: next to the sand of its layer, a cactus
                // there would drop at once
                commands.add("setblock " + x + " " + (y - 1) + " " + z + " sand");
                commands.add("setblock " + x + " " + y + " " + z + " cactus");
                commands.add("setblock " + x + " " + (y + 1) + " " + z + " cactus");
            }
        }
        return commands;
    }

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
