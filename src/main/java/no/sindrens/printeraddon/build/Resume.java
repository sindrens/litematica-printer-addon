package no.sindrens.printeraddon.build;

import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;
import no.sindrens.printeraddon.util.ServerName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks auto build up again after logging out or losing the connection: while it runs, the server, the schematic
 * placement and where you are are written to a file; after rejoining the same server with the same placement it
 * starts again by itself (from the first unfinished layer), once you're back at the build: in the same dimension,
 * and near where it stopped or with the build's own blocks around. Until then the "commands back to the build" run
 * one by one (/server skyblock, /is go), again every minute, for half an hour. Stopping it on purpose (J+B, J+Q,
 * build finished) removes the file.
 */
public final class Resume {
    /** After joining a world: a moment for the chunks around to load, then back at the build or the command there. */
    private static final int WAIT_AFTER_JOIN = 20 * 5;
    private static final int GIVE_UP_AFTER = 20 * 60 * 30;
    /** Between the commands back to the build, and between rounds of them. */
    private static final int COMMAND_GAP = 20 * 8;
    private static final int ROUND_GAP = 20 * 60;
    /** Near where it stopped: back at the build. */
    private static final double NEAR_WHERE_IT_STOPPED = 64;
    private static int joinedTicks;
    /** The world joinedTicks counts in. */
    @javax.annotation.Nullable
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private static int saveTicks;
    /** The next command back to the build, and from when (client ticks, kept over a server switch). */
    private static int commandIndex;
    private static long nextCommandAt;
    /** Went on, or gave up: nothing more to do until the next time out of the world. */
    private static boolean gaveUp;
    private static long lastNote;

    private Resume() {
    }

    private static Path file() {
        return FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("resume.txt");
    }

    /** The server plus where the schematic blocks are (all placements auto build uses, selected or not). */
    private static String key(Minecraft mc) {
        String place = SchematicHelper.world() == null || SchematicHelper.areas().isEmpty() ? "-" : SchematicHelper.areas().toString();
        return ServerName.current(mc) + "|" + place;
    }

    /** While auto build runs: remember what's being built, and where you are. */
    public static void whileRunning(Minecraft mc) {
        gaveUp = false;
        commandIndex = 0;
        nextCommandAt = 0;
        // Counted from when it stops
        joinedTicks = 0;
        world = mc.level;
        if (++saveTicks % 100 != 1 || mc.player == null || mc.level == null) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            // Line 1: what's being built; line 2: the player's own layer view, to put back at the end
            // Line 3: the spending limit and what was spent (resuming doesn't ask again)
            // Line 4: the dimension and where you are (to know you're back at the build)
            Files.writeString(file(), key(mc) + "\n" + AutoBuild.INSTANCE.layers.savedRange() + "\n"
                    + AutoBuild.INSTANCE.spendingText() + "\n"
                    + String.format(Locale.ROOT, "%s %.1f %.1f %.1f", mc.level.dimension(), mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't save the resume file", e);
        }
    }

    /** A build to go on with after joining again (going on after a rejoin is on). */
    public static boolean pending() {
        return Configs.Generic.RESUME_AFTER_REJOIN.getBooleanValue() && Files.isRegularFile(file());
    }

    /** J+Q while not building: no going back to the build later. */
    public static void cancel() {
        if (Files.isRegularFile(file()) && !AutoBuild.INSTANCE.isRunning()) {
            forget();
            gaveUp = true;
            BuildLog.lineOrKeep("RESUME", "cancelled (J+Q): not going back to the build");
        }
    }

    /** Stopped on purpose: don't resume. */
    public static void forget() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException ignored) {
        }
    }

    /**
     * A server message while a build waits to go on (the network moving the player: "You timed out", "sent to the
     * lobby"): into the build log, to see later why it left the build.
     */
    public static void serverMessage(String text) {
        if (!AutoBuild.INSTANCE.isRunning() && pending() && !gaveUp && text.toLowerCase(Locale.ROOT)
                .matches("(?s).*(timed out|timeout|kick|lobby|hub|fallback|disconnect|restart|went down|could not connect|unable to connect).*")) {
            BuildLog.lineOrKeep("SERVER", text.replace('\n', ' '));
        }
    }

    /** Out of the world (disconnected, or the server moving you to another of its servers). */
    public static void onDisconnect() {
        joinedTicks = 0;
        saveTicks = 0;
        gaveUp = false;
    }

    /** In game and not building: start again if this is what was being built before, once back at the build. */
    public static void whileIdle(Minecraft mc) {
        if (mc.level != world) {
            // Another world (moved to another server of the network): counted from now
            world = mc.level;
            joinedTicks = 0;
        }
        joinedTicks++;
        if (!Configs.Generic.RESUME_AFTER_REJOIN.getBooleanValue() || gaveUp || joinedTicks < WAIT_AFTER_JOIN
                || joinedTicks % 20 != 0 || mc.player == null || mc.level == null || !Files.isRegularFile(file())) {
            return;
        }
        try {
            String[] saved = Files.readString(file(), StandardCharsets.UTF_8).split("\n");
            // The same server (address); the network's lobby has no schematic loaded: that's away from the build, not
            // another build. It waited there for good: after "timed out" the network moved the player to its lobby,
            // and nothing happened until they typed /server Skyblock themselves
            if (!saved[0].trim().split("\\|", 2)[0].equals(ServerName.current(mc))) {
                return;
            }
            boolean placed = SchematicHelper.world() != null && !SchematicHelper.areas().isEmpty();
            if (placed && !saved[0].trim().equals(key(mc))) {
                // Another build is placed here now: not this one to go on with
                return;
            }
            String away = !placed ? "the build isn't here (no schematic loaded: another of the network's servers, a lobby?)"
                    : saved.length > 3 ? awayFromBuild(mc, saved[3].trim()) : null;
            if (away == null) {
                gaveUp = true;
                BuildLog.lineOrKeep("RESUME", "back at the build: going on");
                Chat.info("Picking auto build up again where it stopped (J+Q to stop).");
                if (saved.length > 2) {
                    AutoBuild.INSTANCE.restoreSpending(saved[2]);
                }
                AutoBuild.INSTANCE.start(mc);
                if (saved.length > 1) {
                    AutoBuild.INSTANCE.layers.useSavedRange(saved[1].trim());
                }
                return;
            }
            long now = AutoBuild.clientTicks;
            if (now - lastNote >= 20 * 10) {
                lastNote = now;
                BuildLog.lineOrKeep("RESUME", "not back at the build yet: " + away);
            }
            if (joinedTicks > GIVE_UP_AFTER) {
                // Forgotten, so it doesn't pull you back later either
                gaveUp = true;
                forget();
                BuildLog.lineOrKeep("RESUME", "gave up: not back at the build after 30 minutes (" + away + ")");
                Chat.warn("Not back at the build after 30 minutes: auto build didn't go on. Go there and start it (J+B).");
                return;
            }
            sendNextCommand(mc);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't read the resume file", e);
        }
    }

    /** The next "command back to the build", when it's time (a few seconds into a world, 8 s after the last one). */
    private static void sendNextCommand(Minecraft mc) {
        List<String> commands = new ArrayList<>();
        for (String part : Configs.Generic.BACK_COMMANDS.getStringValue().split("[,;]")) {
            String command = part.trim();
            if (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        long now = AutoBuild.clientTicks;
        if (commands.isEmpty() || now < nextCommandAt) {
            return;
        }
        if (commandIndex >= commands.size()) {
            commandIndex = 0;
        }
        if (commandIndex == 0) {
            Chat.info("Not back at the build yet: going there with /" + String.join(", /", commands) + " (J+Q cancels).");
        }
        String command = commands.get(commandIndex++);
        boolean lastOfRound = commandIndex >= commands.size();
        nextCommandAt = now + (lastOfRound ? ROUND_GAP : COMMAND_GAP);
        BuildLog.lineOrKeep("RESUME", "running /" + command + " to get back to the build");
        mc.player.connection.sendCommand(command);
    }

    /**
     * Null when you're back at the build: the same dimension as when it stopped, and near where it stopped or with
     * the build's own blocks around (a hub or another server at the same address has neither). Else why not.
     */
    @javax.annotation.Nullable
    private static String awayFromBuild(Minecraft mc, String where) {
        String[] parts = where.split(" ");
        if (parts.length < 4) {
            return null;
        }
        String dimension = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 3));
        if (!mc.level.dimension().toString().equals(dimension)) {
            return "in " + mc.level.dimension() + ", not " + dimension;
        }
        try {
            double x = Double.parseDouble(parts[parts.length - 3]);
            double y = Double.parseDouble(parts[parts.length - 2]);
            double z = Double.parseDouble(parts[parts.length - 1]);
            double distance = Math.sqrt(mc.player.distanceToSqr(x, y, z));
            if (distance <= NEAR_WHERE_IT_STOPPED) {
                return null;
            }
            int[] match = buildBlocksAround(mc);
            if (match[1] >= 16 && match[0] * 10 >= match[1] * 7) {
                return null;
            }
            return String.format(Locale.ROOT, "%.0f blocks from where it stopped, %d of %d build blocks in sight match", distance, match[0], match[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Of the build's blocks in loaded chunks that something is placed at (every 3rd block, at most 400), how many
     * are what the schematic has there: {matching, looked at}.
     */
    private static int[] buildBlocksAround(Minecraft mc) {
        var schematic = SchematicHelper.world();
        if (schematic == null) {
            return new int[]{0, 0};
        }
        int matching = 0;
        int looked = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicHelper.Area area : SchematicHelper.areas()) {
            for (int y = area.minY(); y <= area.maxY() && looked < 400; y++) {
                for (int x = area.minX(); x <= area.maxX() && looked < 400; x += 3) {
                    for (int z = area.minZ(); z <= area.maxZ() && looked < 400; z += 3) {
                        pos.set(x, y, z);
                        if (!mc.level.isLoaded(pos)) {
                            continue;
                        }
                        BlockState want = schematic.getBlockState(pos);
                        BlockState there = mc.level.getBlockState(pos);
                        if (want.isAir() || there.isAir()) {
                            continue;
                        }
                        looked++;
                        if (there.getBlock() == want.getBlock()) {
                            matching++;
                        }
                    }
                }
            }
        }
        return new int[]{matching, looked};
    }
}
