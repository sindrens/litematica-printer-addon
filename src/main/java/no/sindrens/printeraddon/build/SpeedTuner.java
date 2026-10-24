package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds the fastest printer interval that still places correctly. It watches every block that appears near the
 * player and checks it against the schematic: after enough correct blocks in a row the interval goes one tick down,
 * after a wrong one it goes two ticks up again (and stays there for a while).
 */
public class SpeedTuner {
    private static final int CHECK_EVERY = 10;
    private static final int RADIUS = 6;
    /** A block is judged at the next check after it appeared (before the add-on breaks a wrong one). */
    private static final int JUDGE_AFTER = 0;
    /** No speeding up for this long after a mistake. */
    private static final int CALM_AFTER_MISTAKE = 20 * 60;

    private final Map<BlockPos, BlockState> seen = new HashMap<>();
    private final Map<BlockPos, Long> appeared = new HashMap<>();
    private int interval = -1;
    /** The fastest interval that hasn't placed a wrong block in this build. */
    private int fastestSafe = 1;
    /** Right blocks since the last wrong one; every this many lifts the limits a wrong block set by one tick. */
    private int goodSinceMistake;
    private static final int FORGIVE_AFTER = 300;
    private int goodInARow;
    private int good;
    private int bad;
    private long tick;
    private long calmUntil;
    private long lastPenalty = -1;
    /** Printer attempts and blocks that appeared since the last throughput check. */
    private int attempts;
    private int appearedCount;
    private static final int THROUGHPUT_WINDOW = 200;

    /** The printer is about to place a block. */
    public void attempted() {
        attempts++;
    }

    /** A new layer (other blocks): find the fastest safe speed for it again. */
    public void newLayer() {
        if (Configs.Speed.AUTO_SPEED.getBooleanValue() && fastestSafe > 1) {
            Chat.info("Speed: new layer, trying faster again (was " + interval() + ")");
        }
        fastestSafe = 1;
        goodInARow = 0;
        calmUntil = 0;
    }

    public void reset() {
        seen.clear();
        appeared.clear();
        interval = -1;
        fastestSafe = 1;
        goodInARow = 0;
        good = 0;
        bad = 0;
        calmUntil = 0;
    }

    /** The interval to use now (the starting one until it's tuned). */
    public int interval() {
        // Start at the player's own Litematica Printer interval
        int start = Math.max(1, PrinterBridge.userInterval());
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue()) {
            return start;
        }
        if (interval < 0) {
            interval = start;
            // Start a little above what the last build on this server got to, instead of from the top again
            Integer remembered = remembered();
            if (remembered != null && remembered + START_ABOVE_REMEMBERED < start) {
                interval = Math.max(Configs.Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue(), remembered + START_ABOVE_REMEMBERED);
                Chat.info("Speed: starting at interval " + interval + " (the last build here got to " + remembered + ")");
            }
        }
        return interval;
    }

    /** Per kind of block: the fastest interval it came out right at (it came out wrong once faster). */
    private final java.util.Map<net.minecraft.world.item.Item, Integer> itemFloors = new java.util.HashMap<>();
    private boolean itemFloorsLoaded;

    /** The slowest of the remembered floors of the blocks the current layer still needs. */
    private int floorForLayer() {
        loadItemFloors();
        int floor = 1;
        for (net.minecraft.world.item.Item item : AutoBuild.INSTANCE.layers.layerNeeds().keySet()) {
            floor = Math.max(floor, itemFloors.getOrDefault(item, 1));
        }
        return floor;
    }

    private static java.nio.file.Path itemFile() {
        return fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("speed-blocks.txt");
    }

    /** Lines "server item floor", for this server. */
    private void loadItemFloors() {
        if (itemFloorsLoaded) {
            return;
        }
        itemFloorsLoaded = true;
        try {
            if (!java.nio.file.Files.isRegularFile(itemFile())) {
                return;
            }
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            for (String line : java.nio.file.Files.readAllLines(itemFile())) {
                String[] parts = line.trim().split(" ");
                if (parts.length == 3 && parts[0].equals(server)) {
                    net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(parts[1]);
                    if (id != null) {
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id)
                                .ifPresent(item -> itemFloors.merge(item, Integer.parseInt(parts[2]), Math::max));
                    }
                }
            }
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't read the block speeds", e);
        }
    }

    private void saveItemFloors() {
        if (itemFloors.isEmpty()) {
            return;
        }
        try {
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (java.nio.file.Files.isRegularFile(itemFile())) {
                for (String line : java.nio.file.Files.readAllLines(itemFile())) {
                    if (!line.startsWith(server + " ")) {
                        lines.add(line);
                    }
                }
            }
            itemFloors.forEach((item, floor) -> lines.add(server + " "
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item) + " " + floor));
            java.nio.file.Files.createDirectories(itemFile().getParent());
            java.nio.file.Files.write(itemFile(), lines);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't save the block speeds", e);
        }
    }

    /** How far above the remembered interval a new build starts. */
    private static final int START_ABOVE_REMEMBERED = 0;

    private static java.nio.file.Path file() {
        return fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("speed.txt");
    }

    /** The interval the last build on this server ended at, if it was without mistakes for a while. */
    @javax.annotation.Nullable
    private static Integer remembered() {
        try {
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            for (String line : java.nio.file.Files.readAllLines(file())) {
                String[] parts = line.split("=");
                if (parts.length == 2 && parts[0].equals(server)) {
                    return Integer.parseInt(parts[1].trim());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** At the end of a build: remember the interval it got to on this server (only if it got anywhere). */
    public void remember() {
        saveItemFloors();
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue() || interval < 0 || good < 50) {
            return;
        }
        try {
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (java.nio.file.Files.isRegularFile(file())) {
                for (String line : java.nio.file.Files.readAllLines(file())) {
                    if (!line.startsWith(server + "=")) {
                        lines.add(line);
                    }
                }
            }
            lines.add(server + "=" + Math.max(interval, fastestSafe));
            java.nio.file.Files.createDirectories(file().getParent());
            java.nio.file.Files.write(file(), lines);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't save the speed", e);
        }
    }

    public String describe() {
        return Configs.Speed.AUTO_SPEED.getBooleanValue()
                ? "interval " + interval() + " (auto: " + good + " right, " + bad + " wrong, fastest safe " + Math.max(fastestSafe,
                        Configs.Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue()) + ")"
                : "interval " + interval();
    }

    public void tick(Minecraft mc) {
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue() || mc.player == null || mc.level == null || ++tick % CHECK_EVERY != 0) {
            return;
        }
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return;
        }

        BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -RADIUS, -RADIUS), center.offset(RADIUS, RADIUS, RADIUS))) {
            if (schematic.getBlockState(pos).isAir()) {
                continue;
            }
            BlockState now = mc.level.getBlockState(pos);
            BlockState before = seen.put(pos.immutable(), now);
            // A block that appeared, or one that turned into another (dirt tilled to farmland counts as placed too)
            if (before != null && before.getBlock() != now.getBlock() && !now.isAir() && !now.canBeReplaced()) {
                appeared.put(pos.immutable(), tick);
                appearedCount++;
            }
        }

        // Too fast can also mean the printer's clicks don't get through at all: blocks just don't appear
        if (tick % THROUGHPUT_WINDOW == 0) {
            if (attempts >= 8 && appearedCount * 2 < attempts) {
                slowDown(mc, attempts - appearedCount + " of " + attempts + " printer attempts placed nothing");
            }
            attempts = 0;
            appearedCount = 0;
        }

        appeared.entrySet().removeIf(entry -> {
            if (tick - entry.getValue() < JUDGE_AFTER) {
                return false;
            }
            judge(mc, schematic, entry.getKey());
            return true;
        });
        if (seen.size() > 20000) {
            seen.clear();
        }
    }

    private void slowDown(Minecraft mc, String why) {
        goodInARow = 0;
        calmUntil = tick + CALM_AFTER_MISTAKE;
        // Not this fast again for a while (see the decay in judge)
        fastestSafe = Math.max(fastestSafe, interval() + 1);
        goodSinceMistake = 0;
        int slower = Math.min(Math.max(Configs.Speed.printerInterval(), PrinterBridge.userInterval()), interval() + 2);
        if (slower != interval) {
            interval = slower;
            Chat.warn("Speed: printer interval " + interval + " (" + why + "; not below " + fastestSafe + " for the next "
                    + FORGIVE_AFTER + " blocks)");
        }
    }

    private void judge(Minecraft mc, WorldSchematic schematic, BlockPos pos) {
        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
        switch (result) {
            case OK, BUCKET, PRINTER_FIXES -> {
                good++;
                // A wrong block now and then isn't always the speed (a block placed against one the server didn't
                // have yet): after enough right blocks, the limit it set is lifted step by step
                if (++goodSinceMistake >= FORGIVE_AFTER) {
                    goodSinceMistake = 0;
                    if (fastestSafe > 1) {
                        fastestSafe--;
                        BuildLog.line("SPEED", FORGIVE_AFTER + " right blocks since the last wrong one: may go down to interval "
                                + Math.max(fastestSafe, Configs.Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue()) + " again");
                    }
                    itemFloors.replaceAll((item, floor) -> Math.max(1, floor - 1));
                    itemFloors.values().removeIf(floor -> floor <= 1);
                }
                BuildLog.line("PLACED", pos.toShortString() + " " + AutoBuild.shortState(mc.level.getBlockState(pos))
                        + (result == BlockCheck.Result.OK ? "" : " (" + result + " still to do)") + " | interval " + interval());
                int min = Math.max(Math.max(fastestSafe, floorForLayer()), Configs.Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue());
                // Far from the fastest known safe speed: fewer blocks per step and big steps (half the way at once);
                // close to it: the set number of blocks and one tick at a time
                int needed = interval() - min > 2 ? Math.min(8, Configs.Speed.AUTO_SPEED_STEP_BLOCKS.getIntegerValue())
                        : Configs.Speed.AUTO_SPEED_STEP_BLOCKS.getIntegerValue();
                if (++goodInARow >= needed && tick > calmUntil) {
                    goodInARow = 0;
                    if (interval() > min) {
                        interval -= Math.max(1, (interval() - min) / 2);
                        Chat.info("Speed: printer interval " + interval + " (the last " + needed + " blocks were all right)");
                    }
                }
            }
            case WRONG_BLOCK, WRONG_STATE -> {
                bad++;
                goodInARow = 0;
                BuildLog.line("WRONG", pos.toShortString() + " is " + AutoBuild.shortState(mc.level.getBlockState(pos)) + ", should be "
                        + AutoBuild.shortState(schematic.getBlockState(pos)) + " | interval " + interval());
                // Several wrong blocks found in one check were placed at the same speed: slow down once
                if (lastPenalty == tick) {
                    return;
                }
                lastPenalty = tick;
                // This kind of block came out wrong at this speed: remember it for later layers and builds too
                net.minecraft.world.item.ItemStack wrongItem = StateRules.itemFor(schematic.getBlockState(pos));
                if (!wrongItem.isEmpty()) {
                    itemFloors.merge(wrongItem.getItem(), interval() + 1, Math::max);
                }
                PrinterAddon.debug("Wrong block at {} at interval {}: {} (should be {}; {})", pos.toShortString(), interval(),
                        mc.level.getBlockState(pos), schematic.getBlockState(pos), AutoBuild.INSTANCE.inventory.describe(mc));
                slowDown(mc, "a block came out wrong at " + pos.toShortString());
            }
            default -> {
            }
        }
    }
}
