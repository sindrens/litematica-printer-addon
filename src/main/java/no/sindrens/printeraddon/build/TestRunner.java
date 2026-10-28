package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.IConfigValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test builds, run in game from the settings (Tests tab): each build of the library (in the jar) is placed next to
 * you in a singleplayer world, built by auto build (or broken by Auto break) with the items given as it goes, and the
 * result written to chat and config/printer_addon/test-results.txt. Needs a singleplayer world with cheats on (it uses
 * /fill, /give and /gamemode); never runs on a server.
 */
public final class TestRunner {
    public static final TestRunner INSTANCE = new TestRunner();

    /** One build of the library: its file in the jar, and how high over the ground it goes (floating builds). */
    public record TestBuild(String file, String title, int lift) {
    }

    public static final List<TestBuild> BUILDS = List.of(
            new TestBuild("01-stone-wall.litematic", "Stone wall", 0),
            new TestBuild("02-floating-floor.litematic", "Floating floor", 4),
            new TestBuild("03-small-house.litematic", "Small house", 0),
            new TestBuild("04-redstone-line.litematic", "Redstone line", 0),
            new TestBuild("05-hopper-chain.litematic", "Hopper chain", 0),
            new TestBuild("06-cactus-farm.litematic", "Cactus farm", 0),
            new TestBuild("07-sugar-cane.litematic", "Sugar cane", 0),
            new TestBuild("08-melon-farm.litematic", "Melon farm", 0),
            new TestBuild("09-rail-line.litematic", "Rail line", 0),
            new TestBuild("10-tall-tower.litematic", "Tall tower", 0),
            new TestBuild("11-big-flat.litematic", "Big flat 200x200", 0),
            new TestBuild("12-logs-and-fences.litematic", "Logs and fences", 0),
            new TestBuild("13-islands.litematic", "Islands", 4),
            new TestBuild("14-crops.litematic", "Crops", 0));
    /** The last test: the stone wall built with /fill, broken again by Auto break. */
    public static final String AUTO_BREAK_TEST = "Auto break";

    private static final int MAX_TICKS = 20 * 60 * 15;
    private static final int STUCK_TICKS = 20 * 60 * 3;
    private static final int RESTOCK_EVERY = 40;
    private static final int GAP = 12;

    private enum Phase { IDLE, CLEAR, LOAD, START, RUN, NEXT }

    private final List<String> queue = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private Phase phase = Phase.IDLE;
    private int wait;
    private int ticks;
    private int lastDone = -1;
    private int ticksSinceProgress;
    private int total;
    private long startedAt;
    @Nullable
    private String current;
    @Nullable
    private SchematicPlacement placement;
    private BlockPos origin = BlockPos.ZERO;
    private BlockPos size = BlockPos.ZERO;
    private BlockPos nextOrigin;
    private final Map<IConfigValue, String> saved = new LinkedHashMap<>();
    private String status = "";

    private TestRunner() {
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public String status() {
        return status;
    }

    public List<String> results() {
        return results;
    }

    /** Runs these tests one after the other (titles from BUILDS, or AUTO_BREAK_TEST). */
    public void start(Minecraft mc, List<String> titles) {
        if (isRunning()) {
            Chat.info("Tests are running already: " + status);
            return;
        }
        if (mc.player == null || !mc.hasSingleplayerServer()) {
            Chat.warn("The tests only run in a singleplayer world (with cheats on): they place and clear blocks with commands.");
            return;
        }
        if (AutoBuild.INSTANCE.isRunning() || AreaBreaker.INSTANCE.isRunning()) {
            Chat.warn("Stop auto build / Auto break first.");
            return;
        }
        queue.clear();
        queue.addAll(titles);
        results.clear();
        // Tests use what a singleplayer world has: no shop, no vaults, no server commands (given back after)
        saved.clear();
        for (IConfigValue c : List.<IConfigValue>of(Configs.Shop.AUTO_BUY, Configs.Shop.VAULTS, Configs.Shop.FETCH_FROM_CHESTS,
                Configs.Shop.PUT_AWAY_ON_STOP, Configs.Shop.SELL_LEFTOVERS, Configs.Generic.BACK_COMMANDS, Configs.Generic.RECONNECT,
                Configs.Generic.RESUME_AFTER_REJOIN, Configs.Movement.FLY_COMMAND, Configs.Safety.USE_FEED_COMMAND,
                Configs.Shop.USE_CRAFT_COMMAND, Configs.Safety.STOP_NEAR_PLAYERS, Configs.Generic.AUTO_BUILD)) {
            saved.put(c, c.getStringValue());
        }
        Configs.Shop.AUTO_BUY.setBooleanValue(false);
        Configs.Shop.VAULTS.setValueFromString("");
        Configs.Shop.FETCH_FROM_CHESTS.setBooleanValue(false);
        Configs.Shop.PUT_AWAY_ON_STOP.setBooleanValue(false);
        Configs.Shop.SELL_LEFTOVERS.setBooleanValue(false);
        Configs.Generic.BACK_COMMANDS.setValueFromString("");
        Configs.Generic.RECONNECT.setBooleanValue(false);
        Configs.Generic.RESUME_AFTER_REJOIN.setBooleanValue(false);
        Configs.Movement.FLY_COMMAND.setValueFromString("");
        Configs.Safety.USE_FEED_COMMAND.setBooleanValue(false);
        Configs.Shop.USE_CRAFT_COMMAND.setBooleanValue(false);
        Configs.Safety.STOP_NEAR_PLAYERS.setBooleanValue(false);
        LocalPlayer p = mc.player;
        nextOrigin = new BlockPos(p.getBlockX() + 6, p.getBlockY(), p.getBlockZ() + 6);
        command(mc, "gamemode creative");
        command(mc, "difficulty peaceful");
        command(mc, "gamerule random_tick_speed 0");
        command(mc, "time set noon");
        command(mc, "gamerule advance_time false");
        for (String tool : List.of("diamond_pickaxe", "diamond_axe", "diamond_shovel", "diamond_hoe")) {
            command(mc, "give @s " + tool);
        }
        startedAt = System.currentTimeMillis();
        line("=== Test run " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ", Printer Addon "
                + no.sindrens.printeraddon.compat.Compat.version(PrinterAddon.MOD_ID) + " (" + no.sindrens.printeraddon.Edition.name()
                + " build), " + titles.size() + " test" + (titles.size() == 1 ? "" : "s"));
        Chat.info("Tests started: " + titles.size() + ". Stand on flat open ground; the builds go next to you, in a row east."
                + " Stop them from the Tests tab.");
        nextTest(mc);
    }

    public void stop(Minecraft mc, String why) {
        if (!isRunning()) {
            return;
        }
        if (current != null) {
            result(mc, "STOPPED", why);
        }
        if (AutoBuild.INSTANCE.isRunning()) {
            AutoBuild.INSTANCE.stop(mc, "tests stopped");
        }
        if (AreaBreaker.INSTANCE.isRunning()) {
            AreaBreaker.INSTANCE.stop(mc, "tests stopped");
        }
        queue.clear();
        finish(mc);
    }

    private void finish(Minecraft mc) {
        removePlacement();
        for (Map.Entry<IConfigValue, String> e : saved.entrySet()) {
            e.getKey().setValueFromString(e.getValue());
        }
        saved.clear();
        phase = Phase.IDLE;
        current = null;
        long passed = results.stream().filter(r -> r.contains(" PASS ")).count();
        String summary = "Tests done: " + passed + " of " + results.size() + " passed, "
                + (System.currentTimeMillis() - startedAt) / 60_000 + " min. Results: config/printer_addon/test-results.txt";
        line(summary);
        status = summary;
        Chat.announce("Tests done", passed + " of " + results.size() + " passed", passed == results.size());
        Chat.info(summary);
    }

    private void nextTest(Minecraft mc) {
        removePlacement();
        if (queue.isEmpty()) {
            finish(mc);
            return;
        }
        current = queue.removeFirst();
        ticks = 0;
        lastDone = -1;
        ticksSinceProgress = 0;
        phase = Phase.CLEAR;
        wait = 0;
        status = "Test " + (results.size() + 1) + ": " + current;
        Chat.info("§bTest: " + current);
    }

    /** Every client tick. */
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            queue.clear();
            current = null;
            finish(mc);
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            switch (phase) {
                case CLEAR -> clear(mc);
                case LOAD -> load(mc);
                case START -> begin(mc);
                case RUN -> watch(mc);
                case NEXT -> nextTest(mc);
                default -> {
                }
            }
        } catch (RuntimeException | LinkageError e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] test {}", current, e);
            result(mc, "ERROR", e.toString());
            phase = Phase.NEXT;
            wait = 20;
        }
    }

    private void clear(Minecraft mc) {
        TestBuild build = build(current);
        if (build == null) {
            // Auto break: a 16 x 8 stone wall put there with /fill
            size = new BlockPos(16, 8, 1);
        } else {
            LitematicaSchematic schematic = schematic(build);
            if (schematic == null) {
                result(mc, "ERROR", "couldn't load " + build.file());
                phase = Phase.NEXT;
                return;
            }
            net.minecraft.core.Vec3i s = schematic.getTotalSize();
            size = new BlockPos(Math.abs(s.getX()), Math.abs(s.getY()), Math.abs(s.getZ()));
        }
        origin = nextOrigin.above(build == null ? 0 : build.lift());
        nextOrigin = nextOrigin.east(size.getX() + GAP);
        // Room for it: air from the ground up (and a little around), in pieces /fill takes
        BlockPos from = origin.offset(-2, 0, -2);
        BlockPos to = origin.offset(size.getX() + 1, size.getY() + 3, size.getZ() + 1);
        command(mc, "tp @s " + (origin.getX() - 3) + " " + (origin.getY() + 2) + " " + (origin.getZ() - 3));
        for (int x = from.getX(); x <= to.getX(); x += 32) {
            for (int z = from.getZ(); z <= to.getZ(); z += 32) {
                command(mc, "fill " + x + " " + from.getY() + " " + z + " " + Math.min(to.getX(), x + 31) + " " + to.getY() + " "
                        + Math.min(to.getZ(), z + 31) + " air");
            }
        }
        if (build == null) {
            command(mc, "fill " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " " + (origin.getX() + 15) + " "
                    + (origin.getY() + 7) + " " + origin.getZ() + " stone_bricks");
        }
        phase = Phase.LOAD;
        wait = 20;
    }

    private void load(Minecraft mc) {
        TestBuild build = build(current);
        if (build != null) {
            LitematicaSchematic schematic = schematic(build);
            if (schematic == null) {
                result(mc, "ERROR", "couldn't load " + build.file());
                phase = Phase.NEXT;
                return;
            }
            placement = SchematicPlacement.createFor(schematic, origin, "test: " + build.title(), true, true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        }
        phase = Phase.START;
        wait = 40;
    }

    private void begin(Minecraft mc) {
        startedTestAt = System.currentTimeMillis();
        if (build(current) == null) {
            total = 16 * 8;
            List<int[]> boxes = List.of(new int[]{origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 15, origin.getY() + 7, origin.getZ()});
            if (!AreaBreaker.INSTANCE.start(mc, boxes)) {
                result(mc, "FAIL", "Auto break didn't start");
                phase = Phase.NEXT;
                return;
            }
        } else {
            total = countDone(mc, true);
            restock(mc);
            AutoBuild.INSTANCE.start(mc);
            if (!AutoBuild.INSTANCE.isRunning()) {
                result(mc, "FAIL", "auto build didn't start: " + AutoBuild.lastStopWhy);
                phase = Phase.NEXT;
                return;
            }
        }
        phase = Phase.RUN;
    }

    private long startedTestAt;

    private void watch(Minecraft mc) {
        ticks++;
        boolean breakTest = build(current) == null;
        boolean running = breakTest ? AreaBreaker.INSTANCE.isRunning() : AutoBuild.INSTANCE.isRunning();
        if (ticks % 20 == 0 || !running) {
            int done = breakTest ? total - blocksLeft(mc) : countDone(mc, false);
            status = "Test " + (results.size() + 1) + ": " + current + ", " + done + " / " + total + ", " + ticks / 20 + " s";
            if (done != lastDone) {
                lastDone = done;
                ticksSinceProgress = 0;
            } else {
                ticksSinceProgress += 20;
            }
            if (done >= total) {
                if (running) {
                    if (breakTest) {
                        AreaBreaker.INSTANCE.stop(mc, "test done");
                    } else {
                        AutoBuild.INSTANCE.stop(mc, "test done");
                    }
                }
                result(mc, "PASS", "");
                phase = Phase.NEXT;
                wait = 40;
                return;
            }
            if (!running) {
                result(mc, "FAIL", "stopped at " + done + " / " + total + ": " + AutoBuild.lastStopWhy);
                phase = Phase.NEXT;
                wait = 40;
                return;
            }
            if (ticksSinceProgress > STUCK_TICKS || ticks > MAX_TICKS) {
                String why = ticks > MAX_TICKS ? "over 15 minutes" : "nothing done for 3 minutes (" + AutoBuild.INSTANCE.doing() + ")";
                if (breakTest) {
                    AreaBreaker.INSTANCE.stop(mc, "test: " + why);
                } else {
                    AutoBuild.INSTANCE.stop(mc, "test: " + why);
                }
                result(mc, "FAIL", why + " at " + done + " / " + total);
                phase = Phase.NEXT;
                wait = 40;
                return;
            }
        }
        if (!breakTest && ticks % RESTOCK_EVERY == 0) {
            restock(mc);
        }
    }

    private void result(Minecraft mc, String verdict, String why) {
        double minutes = Math.max(1, ticks) / 20.0 / 60.0;
        int done = Math.max(0, lastDone);
        String text = String.format(java.util.Locale.ROOT, "%-22s %s %d/%d blocks, %d s, %.0f blocks/min%s", current, " " + verdict + " ",
                done, total, ticks / 20, done / minutes, why.isEmpty() ? "" : " | " + why);
        results.add(text);
        line(text);
        String color = verdict.equals("PASS") ? "§a" : "§c";
        Chat.info(color + verdict + "§r " + current + ": " + done + "/" + total + (why.isEmpty() ? "" : " (" + why + ")"));
        current = null;
    }

    private void removePlacement() {
        if (placement != null) {
            try {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
            } catch (RuntimeException e) {
                PrinterAddon.LOGGER.warn("[Printer Addon] test placement", e);
            }
            placement = null;
        }
    }

    @Nullable
    private static TestBuild build(@Nullable String title) {
        for (TestBuild b : BUILDS) {
            if (b.title().equals(title)) {
                return b;
            }
        }
        return null;
    }

    /** The build out of the jar into config/printer_addon/testbuilds, loaded by Litematica. */
    @Nullable
    private static LitematicaSchematic schematic(TestBuild build) {
        Path dir = fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve("printer_addon").resolve("testbuilds");
        try (InputStream in = TestRunner.class.getResourceAsStream("/printer_addon/testbuilds/" + build.file())) {
            if (in == null) {
                return null;
            }
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve(build.file()), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] test build {}", build.file(), e);
            return null;
        }
        return LitematicaSchematic.createFromFile(dir, build.file());
    }

    private static void command(Minecraft mc, String command) {
        if (mc.player != null) {
            mc.player.connection.sendCommand(command);
        }
    }

    private static void line(String text) {
        try {
            Path file = fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve("printer_addon").resolve("test-results.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] test results file", e);
        }
    }

    /** Blocks of the test placement that are right ({@code all}: every block that isn't air in the schematic). */
    private static int countDone(Minecraft mc, boolean all) {
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return 0;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int count = 0;
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (all) {
                            if (!schematic.getBlockState(pos).isAir()) {
                                count++;
                            }
                        } else if (!schematic.getBlockState(pos).isAir() && BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.OK) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private int blocksLeft(Minecraft mc) {
        int left = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(15, 7, 0))) {
            if (!mc.level.getBlockState(pos).isAir()) {
                left++;
            }
        }
        return left;
    }

    /** Gives what the build still needs, as much as fits (like the game test: a stand-in for the shop). */
    private static void restock(Minecraft mc) {
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null || mc.player == null) {
            return;
        }
        Map<Item, Integer> needed = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                        BlockState target = schematic.getBlockState(pos);
                        if (result == BlockCheck.Result.BUCKET && Fluids.bucketFor(target) != null) {
                            needed.merge(Fluids.bucketFor(target), 1, Integer::sum);
                            continue;
                        }
                        if (result != BlockCheck.Result.MISSING && result != BlockCheck.Result.PRINTER_FIXES) {
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
        for (Item bucket : new Item[]{Items.WATER_BUCKET, Items.LAVA_BUCKET}) {
            needed.computeIfPresent(bucket, (item, count) -> Math.min(count, 4));
        }
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
        if (needed.containsKey(Items.WATER_BUCKET)) {
            command(mc, "clear @s minecraft:bucket");
        }
        java.util.Set<Item> layerNeeds = AutoBuild.INSTANCE.layers.layerNeeds().keySet();
        List<Map.Entry<Item, Integer>> byCount = new ArrayList<>(needed.entrySet());
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
            command(mc, "give @s " + BuiltInRegistries.ITEM.getKey(entry.getKey()) + " " + Math.min(missing, stacks * max));
        }
    }
}
