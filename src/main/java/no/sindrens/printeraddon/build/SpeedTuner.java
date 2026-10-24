package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the fastest printer interval that still places correctly, for every kind of block on its own (seeds can go
 * faster than pistons, which have to face the right way), and remembers it per server. Every click of the printer
 * is followed: once the server has answered, the block (and its neighbours, a block can land next to where it was
 * meant) is checked against the schematic. After enough right blocks of a kind its interval goes down, after a wrong
 * one it goes up again and stays above that for a while. Blocks that change by themselves (stems, other players)
 * aren't the printer's and aren't judged.
 * <p>
 * Switching between items (the printer takes another item for the next block) is followed too: which switches
 * came out wrong, at which interval. Such a switch is done slower from then on.
 * <p>
 * The interval is the printer's own setting: ticks/10 between its actions (a block is two: turn and take the item,
 * then click). It's set for each block right when the printer picks it.
 */
public class SpeedTuner {
    /** No speeding up for this long after a mistake (per kind of block). */
    private static final int CALM_AFTER_MISTAKE = 20 * 60;
    /** A click whose block doesn't show up within this long placed nothing. */
    private static final int PICK_TIMEOUT = 20 * 3;
    /** Right blocks of a kind since its last wrong one: every this many lifts the limit the wrong one set by a tick. */
    private static final int FORGIVE_AFTER = 300;
    /** Kept in memory per kind of block: the last clicks, to see when clicks stop getting through. */
    private static final int RECENT = 10;

    /** One click of the printer, waiting for its result: how the spot and its neighbours looked when it clicked. */
    private record Pick(BlockPos pos, @Nullable Item item, int interval, long tick, @Nullable Item before,
                        BlockState[] states) {
    }

    private enum Outcome { RIGHT, WRONG, LANDED_NEXT, NOTHING, PENDING }

    /** Per kind of item the printer places with. */
    private static final class ItemSpeed {
        int interval;
        /** Not below this: a block of this kind came out wrong one tick faster. */
        int floor = 1;
        int right;
        int wrong;
        int nothing;
        int inARow;
        int sinceMistake;
        long calmUntil;
        /** The last clicks: true if something was placed. */
        final boolean[] recent = new boolean[RECENT];
        int recentCount;
    }

    /** Switching from one item to another before a click. */
    private static final class Switch {
        int count;
        int wrong;
        /** Not faster than this right after the switch (it went wrong one tick faster). */
        int floor = 1;
        int minuteCount;
        int minuteWrong;
        /** Of those, the item wasn't on the hotbar and had to be swapped in. */
        int minuteSwaps;
    }

    private final List<Pick> picks = new ArrayList<>();
    private final Map<Item, ItemSpeed> items = new LinkedHashMap<>();
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private boolean loaded;
    /** Where every new kind of block starts (the player's own interval, or what the last build here got to). */
    private int interval = -1;
    @Nullable
    private Item current;
    private int currentInterval = -1;
    @Nullable
    private Item lastPicked;
    private int good;
    private int bad;
    private long lastSummary;
    private long lastSave;
    private int minuteEmptyHand;

    /** A new layer (other blocks): every kind keeps what it learned, only the table goes into the log. */
    public void newLayer() {
        BuildLog.line("SPEEDS", "layer done, per block: " + table());
    }

    public void reset() {
        picks.clear();
        items.clear();
        switches.clear();
        loaded = false;
        interval = -1;
        current = null;
        currentInterval = -1;
        lastPicked = null;
        good = 0;
        bad = 0;
        lastSummary = AutoBuild.clientTicks;
        lastSave = AutoBuild.clientTicks;
        minuteEmptyHand = 0;
    }

    /** The interval to use now: the one of the block the printer works on (the starting one until it's tuned). */
    public int interval() {
        int start = Math.max(1, PrinterBridge.userInterval());
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue()) {
            return start;
        }
        return currentInterval > 0 ? currentInterval : startInterval();
    }

    /** Client ticks one block takes the printer at the current interval (two actions, ten per tick at interval 1). */
    public int ticksPerBlock() {
        return Math.max(1, (int) Math.ceil(2 * interval() / 10.0));
    }

    private int startInterval() {
        if (interval < 0) {
            int start = Math.max(1, PrinterBridge.userInterval());
            interval = start;
            // Start a little above what the last build on this server got to, instead of from the top again
            Integer remembered = remembered();
            if (remembered != null && remembered < start) {
                interval = Math.max(minInterval(), remembered);
                Chat.info("Speed: new kinds of blocks start at interval " + interval + " (the last build here got to " + remembered + ")");
            }
        }
        return interval;
    }

    private static int minInterval() {
        return Math.max(1, Configs.Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue());
    }

    /** The slowest an item is ever set to: the player's own interval (or what a mistake needs, if that's slower). */
    private static int slowest() {
        return Math.max(Math.max(1, PrinterBridge.userInterval()), Configs.Speed.printerInterval());
    }

    private ItemSpeed speedOf(@Nullable Item item) {
        loadItems();
        return items.computeIfAbsent(item, i -> {
            ItemSpeed s = new ItemSpeed();
            s.interval = startInterval();
            return s;
        });
    }

    /**
     * The printer has picked a block and is about to turn, take the item and click: its interval is set right away,
     * so these clicks go at the pace of this kind of block (slower right after a switch that went wrong before).
     */
    public void picked(Minecraft mc, BlockPos pos, @Nullable Item item) {
        if (mc.level == null) {
            return;
        }
        Item before = lastPicked;
        lastPicked = item;
        ItemSpeed s = speedOf(item);
        int pace = s.interval;
        Switch change = null;
        if (item != null && before != null && before != item) {
            change = switches.computeIfAbsent(switchKey(before, item), k -> new Switch());
            change.count++;
            change.minuteCount++;
            pace = Math.max(pace, change.floor);
        }
        pace = Math.max(pace, Math.max(s.floor, minInterval()));
        current = item;
        currentInterval = Configs.Speed.AUTO_SPEED.getBooleanValue() ? pace : -1;
        if (Configs.Speed.AUTO_SPEED.getBooleanValue()) {
            PrinterBridge.applyInterval(pace);
        }
        if (change != null) {
            // Where the new item is: a hotbar slot is only selected; from the inventory it's swapped in (a click in
            // the inventory, which the server can still be busy with when the block is clicked)
            net.minecraft.world.entity.player.Inventory inventory = mc.player.getInventory();
            int hotbar = -1;
            int main = -1;
            for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE && hotbar < 0; slot++) {
                if (inventory.getItem(slot).is(item)) {
                    if (slot < net.minecraft.world.entity.player.Inventory.getSelectionSize()) {
                        hotbar = slot;
                    } else if (main < 0) {
                        main = slot;
                    }
                }
            }
            if (hotbar < 0 && main >= 0) {
                change.minuteSwaps++;
            }
            BuildLog.line("HOTBAR", "switch " + name(before) + " -> " + name(item) + " for " + pos.toShortString()
                    + " | from slot " + (inventory.getSelectedSlot() + 1) + " to "
                    + (hotbar >= 0 ? "hotbar slot " + (hotbar + 1) : main >= 0 ? "slot " + (main + 1) + " (not on the hotbar: swapped in)" : "nothing (not in the inventory)")
                    + " | interval " + interval()
                    + (change.wrong > 0 ? " (this switch came out wrong " + change.wrong + " of " + change.count
                    + " times, not faster than " + change.floor + " after it)" : ""));
        }
        BlockState[] states = new BlockState[7];
        states[0] = mc.level.getBlockState(pos);
        for (Direction d : Direction.values()) {
            states[1 + d.ordinal()] = mc.level.getBlockState(pos.relative(d));
        }
        picks.add(new Pick(pos.immutable(), item, interval(), AutoBuild.clientTicks, before, states));
    }

    /** The printer's click was skipped: the hand was empty (the stack ran out between taking it and clicking). */
    public void emptyHand(BlockPos pos) {
        minuteEmptyHand++;
        BuildLog.line("HOTBAR", "click at " + pos.toShortString() + " skipped: empty hand (the stack in that slot ran out"
                + (lastPicked != null ? ", it was " + name(lastPicked) : "") + ")");
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return;
        }
        long now = AutoBuild.clientTicks;
        // The server's answer (or its correction of what the client showed at once) is in after a round trip
        long settle = Math.max(6, 2L * ServerLag.pingTicks(mc) + 4);
        for (Iterator<Pick> it = picks.iterator(); it.hasNext(); ) {
            Pick pick = it.next();
            long age = now - pick.tick();
            if (age < settle) {
                continue;
            }
            Outcome outcome = outcome(mc, schematic, pick);
            if (outcome == Outcome.PENDING && age < PICK_TIMEOUT) {
                continue;
            }
            it.remove();
            judge(mc, schematic, pick, outcome == Outcome.PENDING ? Outcome.NOTHING : outcome, age);
        }
        if (now - lastSummary >= 20 * 60) {
            lastSummary = now;
            summary();
        }
        if (now - lastSave >= 20 * 60 * 5) {
            lastSave = now;
            saveItems();
        }
    }

    /** What the click did: the spot itself, else a neighbour that got the block instead, else nothing (yet). */
    private Outcome outcome(Minecraft mc, WorldSchematic schematic, Pick pick) {
        BlockState now = mc.level.getBlockState(pick.pos());
        if (now != pick.states()[0]) {
            return switch (BlockCheck.check(mc.level, schematic, pick.pos())) {
                case OK, PRINTER_FIXES, BUCKET -> Outcome.RIGHT;
                case WRONG_BLOCK, WRONG_STATE, EXTRA, PROTECTED -> Outcome.WRONG;
                default -> Outcome.PENDING;
            };
        }
        // The server put the block into the neighbour it was clicked against (it didn't have that one yet)
        for (Direction d : Direction.values()) {
            BlockPos next = pick.pos().relative(d);
            BlockState before = pick.states()[1 + d.ordinal()];
            BlockState after = mc.level.getBlockState(next);
            if (after != before && before.canBeReplaced() && !after.canBeReplaced() && !pickedRecently(next, pick)
                    && pick.item() != null && StateRules.itemFor(after).is(pick.item())) {
                return Outcome.LANDED_NEXT;
            }
        }
        return Outcome.PENDING;
    }

    /** True if the printer clicked this spot itself not long before or after this pick. */
    private boolean pickedRecently(BlockPos pos, Pick other) {
        for (Pick pick : picks) {
            if (pick != other && pick.pos().equals(pos)) {
                return true;
            }
        }
        return AutoBuild.INSTANCE.triedRecently(pos, PICK_TIMEOUT);
    }

    private void judge(Minecraft mc, WorldSchematic schematic, Pick pick, Outcome outcome, long age) {
        ItemSpeed s = speedOf(pick.item());
        boolean switched = pick.before() != null && pick.item() != null && pick.before() != pick.item();
        String how = name(pick.item()) + " at interval " + pick.interval() + (switched ? ", right after switching from " + name(pick.before()) : "")
                + ", " + age + " ticks after the click";
        s.recent[s.recentCount++ % RECENT] = outcome != Outcome.NOTHING;
        switch (outcome) {
            case RIGHT -> {
                good++;
                s.right++;
                s.inARow++;
                BuildLog.line("PLACED", pick.pos().toShortString() + " " + AutoBuild.shortState(mc.level.getBlockState(pick.pos()))
                        + " | " + how);
                // A wrong block now and then isn't always the speed (a block placed against one the server didn't
                // have yet): after enough right blocks, the limit it set is lifted step by step
                if (++s.sinceMistake >= FORGIVE_AFTER) {
                    s.sinceMistake = 0;
                    if (s.floor > 1) {
                        s.floor--;
                        BuildLog.line("SPEED", name(pick.item()) + ": " + FORGIVE_AFTER + " right since the last wrong one, may go down to "
                                + Math.max(s.floor, minInterval()) + " again");
                    }
                    for (Switch change : switches.values()) {
                        change.floor = Math.max(1, change.floor - 1);
                    }
                }
                speedUp(pick.item(), s);
            }
            case WRONG, LANDED_NEXT -> {
                bad++;
                s.wrong++;
                s.inARow = 0;
                s.sinceMistake = 0;
                String where = outcome == Outcome.LANDED_NEXT ? "landed in a neighbouring spot instead" : "is "
                        + AutoBuild.shortState(mc.level.getBlockState(pick.pos())) + ", should be " + AutoBuild.shortState(schematic.getBlockState(pick.pos()));
                BuildLog.line("WRONG", pick.pos().toShortString() + " " + where + " | " + how + " | "
                        + AutoBuild.INSTANCE.inventory.describe(mc));
                if (switched) {
                    Switch change = switches.computeIfAbsent(switchKey(pick.before(), pick.item()), k -> new Switch());
                    change.wrong++;
                    change.minuteWrong++;
                    change.floor = Math.max(change.floor, Math.min(slowest(), pick.interval() + 2));
                }
                if (Configs.Speed.AUTO_SPEED.getBooleanValue()) {
                    // This kind came out wrong at this speed: not this fast again for a while (remembered for later
                    // layers and builds), and a step back up right away
                    s.floor = Math.max(s.floor, pick.interval() + 1);
                    s.calmUntil = AutoBuild.clientTicks + CALM_AFTER_MISTAKE;
                    int slower = Math.max(s.floor, Math.min(slowest(), pick.interval() + 2));
                    if (slower != s.interval) {
                        s.interval = slower;
                        Chat.warn("Speed: " + name(pick.item()) + " at interval " + s.interval + " (a block came out wrong at "
                                + pick.pos().toShortString() + (switched ? " right after switching from " + name(pick.before()) : "")
                                + "; not below " + s.floor + " for it for the next " + FORGIVE_AFTER + " right ones)");
                    }
                }
            }
            case NOTHING -> {
                s.nothing++;
                BuildLog.line("NOCHANGE", pick.pos().toShortString() + " is still " + AutoBuild.shortState(mc.level.getBlockState(pick.pos()))
                        + " (wants " + AutoBuild.shortState(schematic.getBlockState(pick.pos())) + ") | " + how);
                // Too fast can also mean the clicks don't get through at all: most of the last ones placed nothing
                int missed = 0;
                int known = Math.min(RECENT, s.recentCount);
                for (int i = 0; i < known; i++) {
                    if (!s.recent[i]) {
                        missed++;
                    }
                }
                if (Configs.Speed.AUTO_SPEED.getBooleanValue() && known == RECENT && missed > RECENT / 2 && s.interval < slowest()) {
                    s.interval = Math.min(slowest(), s.interval + 2);
                    s.calmUntil = AutoBuild.clientTicks + CALM_AFTER_MISTAKE;
                    java.util.Arrays.fill(s.recent, true);
                    Chat.warn("Speed: " + name(pick.item()) + " at interval " + s.interval + " (" + missed + " of its last " + RECENT
                            + " clicks placed nothing)");
                }
            }
            default -> {
            }
        }
    }

    /** After enough right blocks in a row: faster, in big steps while far from the fastest known safe speed. */
    private void speedUp(@Nullable Item item, ItemSpeed s) {
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue()) {
            return;
        }
        int min = Math.max(s.floor, minInterval());
        int needed = s.interval - min > 2 ? Math.min(8, Configs.Speed.AUTO_SPEED_STEP_BLOCKS.getIntegerValue())
                : Configs.Speed.AUTO_SPEED_STEP_BLOCKS.getIntegerValue();
        if (s.inARow >= needed && AutoBuild.clientTicks > s.calmUntil && s.interval > min) {
            s.inARow = 0;
            int before = s.interval;
            s.interval -= Math.max(1, (s.interval - min) / 2);
            BuildLog.line("SPEED", name(item) + ": interval " + before + " -> " + s.interval + " (the last " + needed + " were all right)");
            Chat.info("Speed: " + name(item) + " at interval " + s.interval + " (the last " + needed + " were all right)");
        }
    }

    /** Once a minute in the build log: every kind of block's speed, and how the item switches went. */
    private void summary() {
        if (!items.isEmpty()) {
            BuildLog.line("SPEEDS", table());
        }
        int count = 0;
        int wrong = 0;
        int swaps = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Switch> entry : switches.entrySet()) {
            Switch change = entry.getValue();
            if (change.minuteCount > 0) {
                count += change.minuteCount;
                wrong += change.minuteWrong;
                swaps += change.minuteSwaps;
                sb.append(entry.getKey().replace(">", " -> ")).append(' ').append(change.minuteCount)
                        .append(change.minuteWrong > 0 ? " (" + change.minuteWrong + " wrong)" : "").append(", ");
                change.minuteCount = 0;
                change.minuteWrong = 0;
                change.minuteSwaps = 0;
            }
        }
        if (count > 0 || minuteEmptyHand > 0) {
            BuildLog.line("HOTBAR", "last minute: " + count + " item switches" + (sb.isEmpty() ? "" : " (" + sb.substring(0, sb.length() - 2) + ")")
                    + ", " + swaps + " of them swapped in from the inventory, " + wrong + " wrong blocks right after a switch, "
                    + minuteEmptyHand + " clicks with an empty hand");
        }
        minuteEmptyHand = 0;
    }

    private String table() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, ItemSpeed> entry : items.entrySet()) {
            ItemSpeed s = entry.getValue();
            if (s.right + s.wrong + s.nothing == 0) {
                continue;
            }
            sb.append(name(entry.getKey())).append(' ').append(s.interval)
                    .append(" (").append(s.right).append(" right, ").append(s.wrong).append(" wrong, ").append(s.nothing)
                    .append(" placed nothing").append(s.floor > 1 ? ", not below " + s.floor : "").append("), ");
        }
        return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "-";
    }

    public String describe() {
        return Configs.Speed.AUTO_SPEED.getBooleanValue()
                ? "interval " + interval() + (current != null ? " for " + name(current) : "") + " (auto: " + good + " right, " + bad + " wrong)"
                : "interval " + interval();
    }

    private static String switchKey(Item from, Item to) {
        return name(from) + ">" + name(to);
    }

    private static String name(@Nullable Item item) {
        return item == null ? "nothing" : BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    // Remembered per server: config/printer_addon/speed-blocks.txt, lines
    //   <server> block <item> <interval> <floor> <right> <wrong> <placed nothing>
    //   <server> switch <from item> <to item> <floor> <switches> <wrong>
    // (an older line "<server> <item> <floor>" only had the floor)

    private static java.nio.file.Path itemFile() {
        return fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("speed-blocks.txt");
    }

    private void loadItems() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (!java.nio.file.Files.isRegularFile(itemFile())) {
                return;
            }
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            for (String line : java.nio.file.Files.readAllLines(itemFile())) {
                String[] parts = line.trim().split(" ");
                if (parts.length < 3 || !parts[0].equals(server)) {
                    continue;
                }
                if (parts.length == 3) {
                    Item item = item(parts[1]);
                    if (item != null) {
                        ItemSpeed s = newSpeed(item);
                        s.floor = Math.max(s.floor, Integer.parseInt(parts[2]));
                        s.interval = Math.max(s.interval, s.floor);
                    }
                } else if (parts[1].equals("block") && parts.length >= 8) {
                    Item item = item(parts[2]);
                    if (item != null) {
                        ItemSpeed s = newSpeed(item);
                        s.interval = Math.max(1, Integer.parseInt(parts[3]));
                        s.floor = Math.max(1, Integer.parseInt(parts[4]));
                        s.right = Integer.parseInt(parts[5]);
                        s.wrong = Integer.parseInt(parts[6]);
                        s.nothing = Integer.parseInt(parts[7]);
                    }
                } else if (parts[1].equals("switch") && parts.length >= 7) {
                    Item from = item(parts[2]);
                    Item to = item(parts[3]);
                    if (from != null && to != null) {
                        Switch change = switches.computeIfAbsent(switchKey(from, to), k -> new Switch());
                        change.floor = Math.max(1, Integer.parseInt(parts[4]));
                        change.count = Integer.parseInt(parts[5]);
                        change.wrong = Integer.parseInt(parts[6]);
                    }
                }
            }
            if (!items.isEmpty()) {
                BuildLog.line("SPEEDS", "remembered for this server: " + rememberedTable());
            }
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't read the block speeds", e);
        }
    }

    private ItemSpeed newSpeed(Item item) {
        return items.computeIfAbsent(item, i -> {
            ItemSpeed s = new ItemSpeed();
            s.interval = startInterval();
            return s;
        });
    }

    private String rememberedTable() {
        StringBuilder sb = new StringBuilder();
        items.forEach((item, s) -> sb.append(name(item)).append(' ').append(s.interval)
                .append(s.floor > 1 ? " (not below " + s.floor + ")" : "").append(", "));
        return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "-";
    }

    @Nullable
    private static Item item(String id) {
        net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
        return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    private void saveItems() {
        if (items.isEmpty() && switches.isEmpty()) {
            return;
        }
        try {
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            List<String> lines = new ArrayList<>();
            if (java.nio.file.Files.isRegularFile(itemFile())) {
                for (String line : java.nio.file.Files.readAllLines(itemFile())) {
                    if (!line.startsWith(server + " ")) {
                        lines.add(line);
                    }
                }
            }
            items.forEach((item, s) -> {
                if (item != null) {
                    lines.add(server + " block " + BuiltInRegistries.ITEM.getKey(item) + " " + s.interval + " " + s.floor + " "
                            + s.right + " " + s.wrong + " " + s.nothing);
                }
            });
            switches.forEach((key, change) -> {
                String[] names = key.split(">");
                lines.add(server + " switch minecraft:" + names[0] + " minecraft:" + names[1] + " " + change.floor + " "
                        + change.count + " " + change.wrong);
            });
            java.nio.file.Files.createDirectories(itemFile().getParent());
            java.nio.file.Files.write(itemFile(), lines);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't save the block speeds", e);
        }
    }

    private static java.nio.file.Path file() {
        return fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("speed.txt");
    }

    /** The interval the last build on this server ended at, if it was without mistakes for a while. */
    @Nullable
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

    /** At the end of a build: remember every kind's speed, and where new kinds start, on this server. */
    public void remember() {
        saveItems();
        BuildLog.line("SPEEDS", "saved for this server: " + table());
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue() || good < 50 || items.isEmpty()) {
            return;
        }
        // New kinds of blocks start at the slowest of the kinds placed in this build (safe for anything)
        int start = 1;
        for (ItemSpeed s : items.values()) {
            if (s.right > 0) {
                start = Math.max(start, s.interval);
            }
        }
        try {
            String server = no.sindrens.printeraddon.util.ServerName.current(Minecraft.getInstance());
            List<String> lines = new ArrayList<>();
            if (java.nio.file.Files.isRegularFile(file())) {
                for (String line : java.nio.file.Files.readAllLines(file())) {
                    if (!line.startsWith(server + "=")) {
                        lines.add(line);
                    }
                }
            }
            lines.add(server + "=" + start);
            java.nio.file.Files.createDirectories(file().getParent());
            java.nio.file.Files.write(file(), lines);
        } catch (Exception e) {
            PrinterAddon.LOGGER.warn("Couldn't save the speed", e);
        }
    }

    /** For tests and the log: the interval a kind of block is at now (-1 if none yet). */
    public int intervalOf(Item item) {
        ItemSpeed s = items.get(item);
        return s == null ? -1 : s.interval;
    }

    /** Clicks still waiting for their result (for the HUD/log). */
    public int pending() {
        return picks.size();
    }
}
