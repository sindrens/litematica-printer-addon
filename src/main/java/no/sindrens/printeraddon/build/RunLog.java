package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The run in fixed key=value lines, so every run is documented in full and runs can be compared without working
 * anything out by hand: a line every second (SEC), a summary at the end of each lane (LANE-END), layer (LAYER-END),
 * flight (TRIP), trip for items (SUPPLY-END) and of the whole run (RUN-END, with the time thieves in seconds and how it
 * went against the last run on the same server). Every tick of the run counts in exactly one category, so the times
 * add up to the run's time. Times are in seconds (s) or ticks (t). Next to the build log, a .jsonl file has every line
 * as JSON, for reading by a program.
 */
public final class RunLog {
    /** What a tick went to: the first that applies, in this order. */
    public enum Cat {
        BUYING("buying"), VAULT("vault"), CRAFTING("crafting"), BREAKING("breaking"), PLACING("placing"),
        SERVER_WAIT("server-wait"), GUARD_THROTTLE("guard-throttle"), ITEM_SETTLE("item-settle"), LANE_FLYING("lane-flying"),
        TRANSIT("transit"), STUCK("stuck"), IDLE_NO_WORK("idle-no-work");

        final String key;

        Cat(String key) {
            this.key = key;
        }
    }

    private static final Cat[] CATS = Cat.values();
    /** Holds shorter than this aren't written (most blocks wait a few ticks for the one under them). */
    private static final int HOLD_LOG_TICKS = 20;
    private static final int LEFT_LISTED = 12;

    /** A stretch of time by category, with the blocks placed in it. */
    private static final class Span {
        final long[] ticks = new long[CATS.length];
        long start;
        int placed;

        void reset(long now) {
            Arrays.fill(ticks, 0);
            start = now;
            placed = 0;
        }

        long total() {
            long sum = 0;
            for (long t : ticks) {
                sum += t;
            }
            return sum;
        }

        /** "placing=12.3s lane-flying=3.0s ..." (only the ones with time). */
        String breakdown() {
            StringBuilder sb = new StringBuilder();
            for (Cat cat : CATS) {
                if (ticks[cat.ordinal()] > 0) {
                    sb.append(sb.length() == 0 ? "" : " ").append(cat.key).append('=').append(seconds(ticks[cat.ordinal()]));
                }
            }
            return sb.length() == 0 ? "-" : sb.toString();
        }

        /** The biggest categories other than placing, in seconds: "buying=52.1s vault=12.3s". */
        String top(int n) {
            List<Cat> cats = new ArrayList<>(Arrays.asList(CATS));
            cats.remove(Cat.PLACING);
            cats.sort((a, b) -> Long.compare(ticks[b.ordinal()], ticks[a.ordinal()]));
            StringBuilder sb = new StringBuilder();
            for (Cat cat : cats.subList(0, Math.min(n, cats.size()))) {
                if (ticks[cat.ordinal()] > 0) {
                    sb.append(sb.length() == 0 ? "" : " ").append(cat.key).append('=').append(seconds(ticks[cat.ordinal()]));
                }
            }
            return sb.length() == 0 ? "-" : sb.toString();
        }
    }

    private static boolean active;
    private static String runId = "-";
    private static String version = "?";
    private static String server = "?";
    @Nullable
    private static BufferedWriter json;

    private static final Span run = new Span();
    private static final Span layer = new Span();
    private static final Span lane = new Span();
    private static final Span second = new Span();
    private static int lastPlacedCount;
    private static long lastTick = -1;
    private static long runStartMillis;
    private static double spentAtStart;

    // The second
    private static int secClicks;
    private static int secFails;
    private static final int[] lastSeconds = new int[10];
    private static int secondCount;

    // The layer
    private static String layerName = "-";
    private static final Map<String, Integer> layerItems = new TreeMap<>();
    private static int layerLanePlaced;
    private static long layerLaneTicks;
    private static int layerLeftoverPlaced;
    private static long layerLeftoverTicks;
    private static int layerLeftovers = -1;
    private static int layerTrips;
    private static double layerTravel;
    private static boolean lanesDone;

    // The lane
    private static boolean laneOn;
    private static String laneName = "-";
    private static int lanePlanned;
    private static List<BlockPos> laneBlocks = List.of();
    private static int laneTop;
    private static double feetMin;
    private static double feetMax;
    private static double speedSum;
    private static int speedTicks;

    // The flight
    private static boolean tripOn;
    private static String tripReason = "";
    private static Vec3 tripFrom = Vec3.ZERO;
    private static long tripStart;
    private static double tripMaxY;
    private static int tripPlacedAtStart;
    private static double tripDistance;
    @Nullable
    private static Vec3 tripLast;

    // The trip for items
    private static boolean supplyOn;
    private static long supplyStart;
    private static String supplyTrigger = "";
    private static String supplyWhere = "";
    private static int supplyInvFree;
    private static int supplyVaultFree;
    private static double supplySpent;
    private static int supplyQuiet;
    private static int supplyPurchases;
    private static final Map<String, Integer> supplyBought = new TreeMap<>();

    // Hotbar, per minute
    private static final Map<String, Integer> switchPairs = new TreeMap<>();
    private static int minuteSwitches;
    private static int minuteClicks;
    private static int minuteClicksAfterSwitch;
    private static int minuteMissAfterSwitch;
    private static long minuteSettleTicks;
    private static long minuteStart;
    private static long lastSwitchTick = -100000;

    // The whole run
    private static int runClicks;
    private static int runMissed;
    private static int runWrong;
    private static final Map<String, Integer> missCauses = new TreeMap<>();
    private static final Map<String, Integer> giveUps = new TreeMap<>();
    private static int runTrips;
    private static double runTravel;
    private static int runPurchases;
    /** Times the player rose up away from a cactus (see Mover#keepClearOfHarm), and times one really hurt. */
    private static int runHarmLifts;
    private static int runHarmTouches;
    /** Junk thrown out (cactus flowers from breaking) and blocks the build doesn't use put in a vault, in items. */
    private static int runJunkThrown;
    private static int runStraysStored;

    // Clicks waiting for their result: what the click was like
    private record ClickInfo(long tick, String face, Vec3 hit, float yaw, float pitch, double speed, double eye, double serverEye,
                             long sinceSwitch, String against, boolean supportFresh, boolean belowFresh) {
    }

    private static final Map<BlockPos, ClickInfo> clicks = new HashMap<>();

    // Blocks held back from the printer: since when, and why
    private record Hold(String reason, long since, long seen, boolean logged) {
    }

    private static final Map<BlockPos, Hold> holds = new HashMap<>();
    private static String lastSettings = "";

    private RunLog() {
    }

    public static boolean isActive() {
        return active;
    }

    // ---- start and end of a run

    public static void start(Minecraft mc) {
        // A new run: the question about the last one's log isn't asked any more
        StopPrompt.cancel();
        active = true;
        what = "-";
        runId = Long.toHexString(Double.doubleToLongBits(Math.random()) ^ System.nanoTime()).substring(0, 6);
        version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(PrinterAddon.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        server = no.sindrens.printeraddon.util.ServerName.current(mc);
        long now = AutoBuild.clientTicks;
        run.reset(now);
        layer.reset(now);
        lane.reset(now);
        second.reset(now);
        lastPlacedCount = AutoBuild.INSTANCE.stats.totalPlaced();
        lastTick = now;
        runStartMillis = System.currentTimeMillis();
        spentAtStart = AutoBuild.INSTANCE.spentTotal();
        secClicks = secFails = 0;
        Arrays.fill(lastSeconds, 0);
        secondCount = 0;
        resetLayer(now, "-");
        laneOn = false;
        tripOn = false;
        supplyOn = false;
        switchPairs.clear();
        minuteSwitches = minuteClicks = minuteClicksAfterSwitch = minuteMissAfterSwitch = 0;
        minuteSettleTicks = 0;
        minuteStart = now;
        runClicks = runMissed = runWrong = runTrips = runPurchases = runHarmLifts = runHarmTouches = 0;
        runJunkThrown = runStraysStored = 0;
        runLeftoverTicks = 0;
        lastSupplySlots = "";
        runTravel = 0;
        missCauses.clear();
        giveUps.clear();
        clicks.clear();
        holds.clear();
        lastSettings = BuildLog.settings();
        openJson();
        // Noted until the run ends: its log still goes when the game crashes or is killed during it (see LogShare)
        LogShare.runStarted(json != null ? currentFile : null, version);
        emit("RUN-START", kv("run", runId, "version", version, "server", server, "player",
                mc.player == null ? "-" : pos(mc.player.position()), "printerInterval",
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_INTERVAL.getIntegerValue(), "printerRange",
                me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue(),
                "units", "s=seconds t=ticks (20 a second)"));
    }

    /**
     * The run is over: the report, the comparison with the last run on this server, and the record for the next.
     * Its log goes to the developer by itself with "Always send logs" (see LogShare); else, when {@code ask} (not a
     * disconnect, the game closing, a start with nothing to build or a stop from Discord), the player is asked.
     */
    public static void end(Minecraft mc, String why, boolean ask) {
        if (!active) {
            return;
        }
        laneEnd("run-end");
        tripEnd(mc, "run-end");
        supplyEnd(mc);
        long ticks = Math.max(1, run.total());
        double secs = ticks / 20.0;
        double spent = AutoBuild.INSTANCE.spentTotal() - spentAtStart;
        Map<String, Object> report = kv("run", runId, "version", version, "why", why, "time", seconds(ticks), "placed", run.placed,
                "placedPerMin", round(run.placed * 60 / secs), "placedPerSec", round(run.placed / secs),
                "top5", run.top(5), "clicks", runClicks, "missedClicks", runMissed, "missCauses", missCauses.toString(),
                "wrongBlocks", runWrong, "givenUp", giveUps.values().stream().mapToInt(Integer::intValue).sum(),
                "givenUpBy", giveUps.toString(), "trips", runTrips, "travel", round(runTravel), "purchases", runPurchases,
                "spent", Math.round(spent), "spentPerBlock", run.placed == 0 ? 0 : round(spent / run.placed),
                "cactusTouches", runHarmTouches, "cactusLifts", runHarmLifts, "clickAnswers", ServerAcks.answerResults(),
                "breaking", AutoBuild.INSTANCE.breaker.summary(), "junkThrown", runJunkThrown, "straysStored", runStraysStored,
                "guard", ServerWatch.summary(), "times", run.breakdown());
        emit("RUN-END", report);
        no.sindrens.printeraddon.discord.DiscordLink.runEnded(why, run.placed, secs, spent, what);
        String adviceText = advice(ticks);
        emit("RUN-ADVICE", kv("top3", adviceText));
        compareWithLast();
        saveRun(spent);
        active = false;
        closeJson();
        // For sharing the log (see LogShare): what ran and how it went, in a few lines
        lastFile = currentFile;
        lastSummary = String.join("\n",
                "**Ran:** " + what,
                "**Ended:** " + why + " after " + seconds(ticks),
                "**Placed:** " + run.placed + " (" + round(run.placed * 60 / secs) + " a minute) | clicks " + runClicks + ", missed "
                        + runMissed + ", wrong " + runWrong + ", given up " + giveUps.values().stream().mapToInt(Integer::intValue).sum(),
                "**Time went to:** " + run.top(5),
                "**Biggest costs:** " + (adviceText.isEmpty() ? "-" : adviceText),
                "**Trips:** " + runTrips + ", purchases " + runPurchases + ", spent $" + Math.round(spent),
                "**Breaking:** " + AutoBuild.INSTANCE.breaker.summary());
        LogShare.afterRun(mc, why, ask);
    }

    /** What the run is ("Auto build: farm, Y -56 to 223"), for the shared log's summary. */
    private static String what = "-";
    /** The .jsonl being written, and the one of the last run that ended (see LogShare). */
    @Nullable
    private static Path currentFile;
    @Nullable
    private static Path lastFile;
    private static String lastSummary = "";

    public static void what(String text) {
        what = text;
    }

    @Nullable
    public static Path lastFile() {
        return lastFile;
    }

    /** What the run going on is, or the last one was ("Auto build: farm, Y -56 to 223"); "-" before the first. */
    static String describeRun() {
        return what;
    }

    /** The .jsonl of the run going on now, written out up to this moment; null when none is going on. */
    @Nullable
    static Path runningFile() {
        if (!active || currentFile == null) {
            return null;
        }
        flushJson();
        return currentFile;
    }

    public static String lastSummary() {
        return lastSummary;
    }

    /**
     * The three biggest costs of the run, each with what's behind it as far as the numbers tell (buying 18% - 38 trips,
     * the last with 6 slots of junk): where to look first.
     */
    private static String advice(long ticks) {
        List<Object[]> costs = new ArrayList<>();
        double total = Math.max(1, ticks);
        double buying = (run.ticks[Cat.BUYING.ordinal()] + run.ticks[Cat.VAULT.ordinal()]) / total;
        costs.add(new Object[]{buying, "buying " + percent(buying) + " - " + runTrips + " trips for items, " + runPurchases
                + " purchases; the inventory at the last one: " + (lastSupplySlots.isEmpty() ? "-" : lastSupplySlots)});
        long leftoverTicks = runLeftoverTicks + layerLeftoverTicks;
        double leftovers = leftoverTicks / total;
        costs.add(new Object[]{leftovers, "leftovers after the lanes " + percent(leftovers) + " - see LEFTOVER-PLAN and the TRIP lines"});
        double breaking = run.ticks[Cat.BREAKING.ordinal()] / total;
        costs.add(new Object[]{breaking, "breaking " + percent(breaking) + " - " + AutoBuild.INSTANCE.breaker.summary()
                + " (standing breaks are about 3 times faster)"});
        double server = (run.ticks[Cat.SERVER_WAIT.ordinal()] + run.ticks[Cat.GUARD_THROTTLE.ordinal()]) / total;
        costs.add(new Object[]{server, "waiting for the server " + percent(server) + " - lag or slow answers (see SERVER and GUARD-END)"});
        double transit = run.ticks[Cat.TRANSIT.ordinal()] / total;
        costs.add(new Object[]{transit, "flying between lanes and spots " + percent(transit)});
        double stuck = (run.ticks[Cat.STUCK.ordinal()] + run.ticks[Cat.IDLE_NO_WORK.ordinal()]) / total;
        costs.add(new Object[]{stuck, "nothing to place " + percent(stuck) + " - see the HOLD and STUCK lines"});
        double missed = runClicks == 0 ? 0 : (double) (runMissed + runWrong) / runClicks;
        String topCause = missCauses.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
        // A missed click costs about a click's time again, so it's weighed by the placing time
        costs.add(new Object[]{missed * run.ticks[Cat.PLACING.ordinal()] / total, "missed clicks " + percent(missed)
                + " of all - mostly " + topCause});
        costs.sort((a, b) -> Double.compare((double) b[0], (double) a[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, costs.size()); i++) {
            if ((double) costs.get(i)[0] < 0.02) {
                break;
            }
            sb.append(sb.length() == 0 ? "" : " | ").append(i + 1).append(". ").append(costs.get(i)[1]);
        }
        return sb.length() == 0 ? "nothing stands out" : sb.toString();
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    /** The leftovers' time of the layers done (see LAYER-END), and the inventory at the last trip for items. */
    private static long runLeftoverTicks;
    private static String lastSupplySlots = "";

    // ---- every tick

    public static void tick(Minecraft mc) {
        if (!active || mc.player == null) {
            return;
        }
        long now = AutoBuild.clientTicks;
        if (now == lastTick) {
            return;
        }
        lastTick = now;
        AutoBuild build = AutoBuild.INSTANCE;
        int placedNow = build.stats.totalPlaced();
        int placed = Math.max(0, placedNow - lastPlacedCount);
        lastPlacedCount = placedNow;
        Cat cat = classify(mc, placed);
        for (Span span : new Span[]{run, layer, lane, second}) {
            span.ticks[cat.ordinal()]++;
            span.placed += placed;
        }
        if (cat == Cat.ITEM_SETTLE) {
            minuteSettleTicks++;
        }
        // Lanes against what's left after them
        if (laneOn) {
            layerLanePlaced += placed;
            layerLaneTicks++;
            LocalPlayer player = mc.player;
            double feet = player.getY() - (laneTop + 1);
            feetMin = Math.min(feetMin, feet);
            feetMax = Math.max(feetMax, feet);
            Vec3 motion = player.getDeltaMovement();
            speedSum += Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            speedTicks++;
        } else if (lanesDone) {
            layerLeftoverPlaced += placed;
            layerLeftoverTicks++;
        }
        if (tripOn) {
            Vec3 at = mc.player.position();
            if (tripLast != null) {
                tripDistance += tripLast.distanceTo(at);
            }
            tripLast = at;
            tripMaxY = Math.max(tripMaxY, at.y);
        }
        if (supplyOn) {
            boolean busy = build.fetcher.isBusy() || no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning()
                    || build.crafter.isBusy() || build.tripGoingOn();
            supplyQuiet = busy ? 0 : supplyQuiet + 1;
            if (supplyQuiet > 10) {
                supplyEnd(mc);
            }
        }
        if (now - second.start >= 20) {
            secondLine(mc, cat);
        }
        if (now - minuteStart >= 20 * 60) {
            hotbarMinute();
        }
    }

    private static Cat classify(Minecraft mc, int placed) {
        AutoBuild build = AutoBuild.INSTANCE;
        if (no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.isRunning() || no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.isRunning()) {
            return Cat.BUYING;
        }
        if (build.fetcher.isBusy()) {
            return Cat.VAULT;
        }
        if (build.crafter.isBusy()) {
            return Cat.CRAFTING;
        }
        if (build.breaker.isBusy()) {
            return Cat.BREAKING;
        }
        if (placed > 0) {
            return Cat.PLACING;
        }
        String paused = build.pausedThisTick();
        if (ServerWatch.stalled() || "server lag".equals(paused) || "server slow to answer".equals(paused)) {
            return Cat.SERVER_WAIT;
        }
        if ("speed guard limit".equals(paused) || "placement cap".equals(paused)) {
            return Cat.GUARD_THROTTLE;
        }
        if ("item swap settling".equals(paused)) {
            return Cat.ITEM_SETTLE;
        }
        if (build.mover.onLane()) {
            return Cat.LANE_FLYING;
        }
        // To the next lane's start too: it was "stuck" for half a second at every lane, 18 s of a test run
        if (build.mover.isTravelling(mc.player) || build.mover.laneApproaching()) {
            return Cat.TRANSIT;
        }
        if (build.mover.printerReachCount() > 0 || build.lastUnfinishedCount > 0) {
            return Cat.STUCK;
        }
        return Cat.IDLE_NO_WORK;
    }

    private static void secondLine(Minecraft mc, Cat lastCat) {
        AutoBuild build = AutoBuild.INSTANCE;
        LocalPlayer player = mc.player;
        lastSeconds[secondCount++ % lastSeconds.length] = second.placed;
        int known = Math.min(secondCount, lastSeconds.length);
        double perSec10 = 0;
        for (int i = 0; i < known; i++) {
            perSec10 += lastSeconds[i];
        }
        perSec10 /= Math.max(1, known);
        // The category most of this second went to
        Cat most = lastCat;
        for (Cat cat : CATS) {
            if (second.ticks[cat.ordinal()] > second.ticks[most.ordinal()]) {
                most = cat;
            }
        }
        Vec3 motion = player.getDeltaMovement();
        int ping = mc.getConnection() == null || mc.getConnection().getPlayerInfo(player.getUUID()) == null
                ? -1 : mc.getConnection().getPlayerInfo(player.getUUID()).getLatency();
        String pause = build.pausedThisTick();
        // One line every 10 s (it was every second, next to four other status lines): sums over the 10 s, the rest as now
        for (Cat cat : CATS) {
            beat.ticks[cat.ordinal()] += second.ticks[cat.ordinal()];
        }
        beat.placed += second.placed;
        beatClicks += secClicks;
        beatFails += secFails;
        if (secondCount % 10 == 0) {
            Cat main = most;
            for (Cat cat : CATS) {
                if (beat.ticks[cat.ordinal()] > beat.ticks[main.ordinal()]) {
                    main = cat;
                }
            }
            emit("BEAT", kv("placed10s", beat.placed, "clicks10s", beatClicks, "missed10s", beatFails, "rate10s", round(perSec10) + "/s",
                    "activity", main.key, "times", beat.breakdown(), "lane", build.mover.laneName(), "pos", pos(player.position()),
                    "speedBlocksPerTick", round(Math.sqrt(motion.x * motion.x + motion.z * motion.z)),
                    "feetOverLayerTop", signed(player.getY() - (build.layers.getLayerTop() + 1)),
                    "inReachMover", build.mover.printerReachCount(), "printerUnfinished", build.lastUnfinishedCount,
                    "printerHeld", build.lastHeldCount, "printerNotReady", build.lastNotReadyCount, "printerOffered", build.lastOfferedCount,
                    "pause", pause == null ? "-" : pause.replace(' ', '-'), "tps", round(ServerLag.tps()), "pingMs", ping, "fps", mc.getFps(),
                    "free", InventoryCleaner.freeSlots(player.getInventory()), "items", layerItemsHeld(player)));
            beat.reset(AutoBuild.clientTicks);
            beatClicks = 0;
            beatFails = 0;
        }
        second.reset(AutoBuild.clientTicks);
        secClicks = 0;
        secFails = 0;
        // A setting changed while it runs: the player's doing, noted as such
        if (secondCount % 5 == 0) {
            String settings = BuildLog.settings();
            if (!settings.equals(lastSettings)) {
                user("setting", changes(lastSettings, settings));
                lastSettings = settings;
            }
        }
        // Holds not seen for a while are over
        holds.values().removeIf(hold -> AutoBuild.clientTicks - hold.seen() > 10);
    }

    /** The 10 s a BEAT line sums up. */
    private static final Span beat = new Span();
    private static int beatClicks;
    private static int beatFails;

    /** How many of each of the layer's items the inventory holds, "sand=385 string=133". */
    private static String layerItemsHeld(LocalPlayer player) {
        StringBuilder sb = new StringBuilder();
        for (Item item : AutoBuild.INSTANCE.layers.layerNeeds().keySet()) {
            if (Tools.isMarker(item)) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : " ").append(itemName(item)).append('=').append(player.getInventory().countItem(item));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String changes(String before, String after) {
        Map<String, String> old = new HashMap<>();
        for (String part : before.split(", ")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                old.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String part : after.split(", ")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String name = part.substring(0, eq);
                String value = part.substring(eq + 1);
                if (!value.equals(old.get(name))) {
                    sb.append(sb.length() == 0 ? "" : " ").append(name).append('=').append(old.getOrDefault(name, "?")).append("->").append(value);
                }
            }
        }
        return sb.toString();
    }

    // ---- the printer's clicks

    /** A click goes out (InteractActionMixin): what it's like, for the line with its result. */
    public static void click(Minecraft mc, BlockPos clicked, Direction face, Vec3 hit) {
        if (!active || mc.player == null) {
            return;
        }
        LocalPlayer player = mc.player;
        BlockPos target = clicked.relative(face);
        // The block placed is next to the one clicked, unless that one is replaced (air place: the spot itself). A
        // tool works on the block clicked itself (a hoe tilling dirt): that's the spot (its misses had no face, hit or
        // distance in the log, the click was kept for the spot above)
        boolean tool = Tools.kindOf(player.getMainHandItem()) != null;
        BlockPos at = mc.level != null && (tool || mc.level.getBlockState(clicked).canBeReplaced()) ? clicked : target;
        Vec3 motion = player.getDeltaMovement();
        Vec3 eye = player.getEyePosition();
        // Where the server has the player: about half a round trip and a tick behind
        double lag = ServerLag.pingTicks(mc) / 2.0 + 1;
        Vec3 serverEye = eye.subtract(motion.scale(lag));
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(clicked);
        // What was clicked against (the block itself for an air place)
        String against = mc.level == null ? "?" : tool ? "itself(" + LayerManager.blockName(mc.level.getBlockState(clicked)) + ")"
                : at.equals(clicked) ? "air-place" : LayerManager.blockName(mc.level.getBlockState(clicked)) + "(" + side(at, clicked) + ")";
        // Clicked against a block (or onto one, for sand) the server may not have had yet: for the miss's cause
        boolean supportFresh = !at.equals(clicked) && ServerAcks.unconfirmed(clicked, 10);
        boolean belowFresh = ServerAcks.unconfirmed(at.below(), 10);
        clicks.put(at.immutable(), new ClickInfo(AutoBuild.clientTicks, face.getSerializedName(), hit, player.getYRot(), player.getXRot(),
                Math.sqrt(motion.x * motion.x + motion.z * motion.z), Math.sqrt(box.distanceToSqr(eye)), Math.sqrt(box.distanceToSqr(serverEye)),
                AutoBuild.clientTicks - lastSwitchTick, against, supportFresh, belowFresh));
        laneClicks++;
        if (clicks.size() > 512) {
            clicks.values().removeIf(c -> AutoBuild.clientTicks - c.tick() > 200);
        }
        secClicks++;
        runClicks++;
        minuteClicks++;
        if (AutoBuild.clientTicks - lastSwitchTick <= 3) {
            minuteClicksAfterSwitch++;
        }
    }

    /**
     * How the click on this spot went, for the PLACED / NOCHANGE / WRONG line: the side clicked, where, the head's
     * turn, the speed, the distance from the eye (here and as the server has the player), ticks since the last item
     * switch and the server's answer time. Empty when the click isn't known.
     */
    public static String clickInfo(BlockPos pos) {
        ClickInfo info = clicks.get(pos);
        if (info == null) {
            return "";
        }
        int answer = ServerAcks.answerTicks(pos);
        // Ticks since the last item switch only when it can matter (sinceSwitch=8470t was noise)
        return String.format(Locale.ROOT, " | face=%s hit=%.2f,%.2f,%.2f against=%s yaw=%.1f pitch=%.1f speed=%.2f eyeDist=%.2f serverEyeDist=%.2f%s answer=%s",
                info.face(), info.hit().x, info.hit().y, info.hit().z, info.against(), info.yaw(), info.pitch(), info.speed(), info.eye(),
                info.serverEye(), info.sinceSwitch() <= 20 ? " sinceSwitch=" + info.sinceSwitch() + "t" : "", answer < 0 ? "none" : answer + "t");
    }

    /** "D" when {@code other} is below {@code pos}, "U" above, "N", "S", "W", "E" beside it. */
    private static String side(BlockPos pos, BlockPos other) {
        for (Direction direction : Direction.values()) {
            if (pos.relative(direction).equals(other)) {
                return direction.getSerializedName().substring(0, 1).toUpperCase(Locale.ROOT);
            }
        }
        return "?";
    }

    /** The six blocks around this spot now, for the line of a click that placed nothing or the wrong block. */
    public static String neighbours(Minecraft mc, BlockPos pos) {
        if (mc.level == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" nb=");
        for (Direction direction : Direction.values()) {
            sb.append(direction.getSerializedName().substring(0, 1).toUpperCase(Locale.ROOT)).append(':')
                    .append(LayerManager.blockName(mc.level.getBlockState(pos.relative(direction)))).append(direction == Direction.EAST ? "" : ",");
        }
        return sb.toString();
    }

    /** The most likely reason a click placed nothing or the wrong block. */
    public static String cause(Minecraft mc, BlockPos pos, boolean wrong) {
        ClickInfo info = clicks.get(pos);
        String cause;
        if (info != null && ServerAcks.answerTicks(pos) < 0) {
            cause = "no-answer(dropped-by-server)";
        } else if (info != null && info.serverEye() > mc.player.blockInteractionRange() + 0.9) {
            cause = "reach(server-side " + String.format(Locale.ROOT, "%.2f", info.serverEye()) + ")";
        } else if (mc.level != null && SchematicHelper.world() != null
                && SchematicHelper.world().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                && net.minecraft.world.level.block.FallingBlock.isFree(mc.level.getBlockState(pos.below()))) {
            cause = "fell(nothing under it now)";
        } else if (mc.level != null && !mc.level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).isEmpty()) {
            cause = "in-the-way(" + mc.level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).getFirst().getType().toShortString() + ")";
        } else if (wrong) {
            cause = "placed-against-another-block";
        } else if (info == null) {
            cause = "refused-other";
        } else if (info.sinceSwitch() == 0) {
            // Refused, split by what was likely behind it (99 % of a log's misses were all "refused-by-server")
            cause = "refused-same-tick-as-switch";
        } else if (ServerLag.tps() < 12 || ServerLag.ticksSinceTimeUpdate() > 30) {
            cause = "refused-during-lag";
        } else if (info.belowFresh() && SchematicHelper.world() != null
                && SchematicHelper.world().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
            cause = "refused-falling-block-on-new-block";
        } else if (info.supportFresh()) {
            cause = "refused-against-new-block";
        } else if (info.speed() > 0.2 && info.serverEye() > 4.0) {
            cause = "refused-moving-far";
        } else {
            cause = "refused-other";
        }
        String key = cause.contains("(") ? cause.substring(0, cause.indexOf('(')) : cause;
        missCauses.merge(key, 1, Integer::sum);
        if (wrong) {
            runWrong++;
        } else {
            runMissed++;
        }
        secFails++;
        laneMisses++;
        layerMisses++;
        if (info != null && info.sinceSwitch() <= 3) {
            minuteMissAfterSwitch++;
        }
        return cause;
    }

    /** Clicks held for a tick because the switch to their item was in the same tick (see InteractActionMixin). */
    private static int minuteSwitchWaits;

    public static void switchWait() {
        if (active) {
            minuteSwitchWaits++;
        }
    }

    /** The held item was switched for the next block. */
    public static void hotbarSwitch(@Nullable Item from, @Nullable Item to) {
        if (!active) {
            return;
        }
        lastSwitchTick = AutoBuild.clientTicks;
        minuteSwitches++;
        laneSwitches++;
        switchPairs.merge(itemName(from) + ">" + itemName(to), 1, Integer::sum);
    }

    private static void hotbarMinute() {
        if (minuteSwitches > 0 || minuteClicks > 0) {
            emit("HOTBAR-MIN", kv("switches", minuteSwitches, "clicks", minuteClicks, "switchesPerClick",
                    minuteClicks == 0 ? 0 : round(minuteSwitches / (double) minuteClicks), "pairs", switchPairs.toString(),
                    "clicksRightAfterSwitch", minuteClicksAfterSwitch, "missedRightAfterSwitch", minuteMissAfterSwitch,
                    "missShareAfterSwitch", minuteClicksAfterSwitch == 0 ? "0%" : Math.round(minuteMissAfterSwitch * 100.0 / minuteClicksAfterSwitch) + "%",
                    "itemSettleWait", seconds(minuteSettleTicks), "switchWaits", minuteSwitchWaits));
        }
        switchPairs.clear();
        minuteSwitches = minuteClicks = minuteClicksAfterSwitch = minuteMissAfterSwitch = 0;
        minuteSettleTicks = 0;
        minuteSwitchWaits = 0;
        minuteStart = AutoBuild.clientTicks;
    }

    /** A block was given up on (clicked again and again without a change, or no way to it). */
    public static void gaveUp(String why) {
        giveUps.merge(why, 1, Integer::sum);
    }

    /** The printer queued a block of this item. */
    public static void placedItem(@Nullable Item item) {
        if (active) {
            layerItems.merge(itemName(item), 1, Integer::sum);
        }
    }

    // ---- blocks held back from the printer

    /** This block was held back from the printer for this reason (called on every offer: cheap). */
    public static void hold(BlockPos pos, String reason) {
        if (!active) {
            return;
        }
        long now = AutoBuild.clientTicks;
        Hold hold = holds.get(pos);
        if (hold == null || !hold.reason().equals(reason)) {
            holds.put(pos.immutable(), new Hold(reason, now, now, false));
            return;
        }
        // Not the ordinary ones (blocks at the edge of the reach, what waits for the block under it): those were 417 of
        // a log's 634 lines
        boolean log = !hold.logged() && now - hold.since() >= HOLD_LOG_TICKS && !reason.equals("beyond-learned-reach")
                && !reason.equals("cant-stand-there-yet");
        holds.put(pos, new Hold(reason, hold.since(), now, hold.logged() || log));
        if (log) {
            BuildLog.line("HOLD", pos.toShortString().replace(" ", "") + " reason=" + reason + " since=" + (now - hold.since()) + "t");
        }
    }

    /** Why the printer isn't given this block now, if it's held back; else null. */
    @Nullable
    public static String holdReason(BlockPos pos) {
        Hold hold = holds.get(pos);
        return hold == null || AutoBuild.clientTicks - hold.seen() > 10 ? null : hold.reason();
    }

    // ---- lanes, layers, flights

    /** A lane begins: its name ("7/15"), its blocks, the top of the layers it's for. */
    public static void laneStart(String name, List<BlockPos> blocks, int top) {
        if (!active) {
            return;
        }
        laneEnd("next-lane");
        laneOn = true;
        laneName = name;
        lanePlanned = blocks.size();
        laneBlocks = blocks;
        laneTop = top;
        feetMin = Double.MAX_VALUE;
        feetMax = -Double.MAX_VALUE;
        speedSum = 0;
        speedTicks = 0;
        lane.reset(AutoBuild.clientTicks);
        laneClicks = laneMisses = laneSwitches = 0;
        laneHeldBehind = 0;
    }

    /** Ticks the lane stood still for a block at the back of the reach (see Mover#holdForBehind). */
    private static int laneHeldBehind;

    public static void heldForBehind() {
        if (active && laneOn) {
            laneHeldBehind++;
        }
    }

    /** What the layers that just finished leave for the end (see LayerManager#leftAtDone): written in LAYER-DONE. */
    private static int doneLeft;
    private static int doneUntilled;
    private static int doneCrops;
    private static String doneLeftList = "";
    private static int layerTilledStart;

    public static void layerLeft(int left, int untilled, int crops, String list) {
        doneLeft = left;
        doneUntilled = untilled;
        doneCrops = crops;
        doneLeftList = list;
    }

    /** Misses during the layer (see LAYER-DONE). */
    private static int layerMisses;
    /** Clicks, misses and item switches during the lane (see LANE-END). */
    private static int laneClicks;
    private static int laneMisses;
    private static int laneSwitches;

    /** The lane is over (the next begins, all are flown, the layer changed): what it did and what it left, and why. */
    public static void laneEnd(String why) {
        if (!active || !laneOn) {
            return;
        }
        laneOn = false;
        Minecraft mc = Minecraft.getInstance();
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        Map<String, Integer> reasons = new TreeMap<>();
        List<String> listed = new ArrayList<>();
        int left = 0;
        if (mc.level != null && schematic != null) {
            for (BlockPos pos : laneBlocks) {
                BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
                if (result == BlockCheck.Result.OK) {
                    continue;
                }
                left++;
                String reason = leftReason(mc, schematic, pos, result);
                reasons.merge(reason.contains("(") ? reason.substring(0, reason.indexOf('(')) : reason, 1, Integer::sum);
                if (listed.size() < LEFT_LISTED) {
                    listed.add(pos.toShortString().replace(" ", "") + ":" + reason);
                }
            }
        }
        long ticks = Math.max(1, lane.total());
        long paused = ticks - lane.ticks[Cat.PLACING.ordinal()] - lane.ticks[Cat.LANE_FLYING.ordinal()];
        Span pausedOnly = new Span();
        System.arraycopy(lane.ticks, 0, pausedOnly.ticks, 0, CATS.length);
        pausedOnly.ticks[Cat.PLACING.ordinal()] = 0;
        pausedOnly.ticks[Cat.LANE_FLYING.ordinal()] = 0;
        emit("LANE-END", kv("lane", laneName, "end", why, "planned", lanePlanned, "doneOfPlanned", lanePlanned - left, "left", left,
                "placedAll", lane.placed, "time", seconds(ticks), "placedPerSec", round(lane.placed / (ticks / 20.0)), "paused", seconds(Math.max(0, paused)),
                "pausedBy", pausedOnly.breakdown(), "feetOverTopMin", feetMin == Double.MAX_VALUE ? "-" : signed(feetMin),
                "feetOverTopMax", feetMax == -Double.MAX_VALUE ? "-" : signed(feetMax),
                "avgSpeedBlocksPerTick", round(speedTicks == 0 ? 0 : speedSum / speedTicks), "clicks", laneClicks, "missed", laneMisses,
                "switches", laneSwitches, "heldForBehind", seconds(laneHeldBehind), "leftWhy", reasons.toString()));
        if (!listed.isEmpty()) {
            BuildLog.line("LANE-LEFT", "lane=" + laneName + " " + String.join(" ", listed) + (left > listed.size() ? " (+" + (left - listed.size()) + " more)" : ""));
        }
        laneBlocks = List.of();
    }

    private static String leftReason(Minecraft mc, fi.dy.masa.litematica.world.WorldSchematic schematic, BlockPos pos, BlockCheck.Result result) {
        AutoBuild build = AutoBuild.INSTANCE;
        if (result.needsBreaking() && build.isConflict(pos, result, mc.level.getBlockState(pos))) {
            return "conflict(" + LayerManager.blockName(mc.level.getBlockState(pos)) + ")";
        }
        if (result.needsBreaking() || result == BlockCheck.Result.EXTRA) {
            return "wrong-block(" + LayerManager.blockName(mc.level.getBlockState(pos)) + ")";
        }
        if (build.givenUpNow(pos)) {
            return "given-up";
        }
        if (mc.player != null && !mc.player.getAbilities().instabuild) {
            net.minecraft.world.item.ItemStack needed = StateRules.itemFor(schematic.getBlockState(pos));
            if (!needed.isEmpty() && mc.player.getInventory().countItem(needed.getItem()) == 0) {
                return "no-item(" + itemName(needed.getItem()) + ")";
            }
        }
        if (Tiller.untilled(mc.level, schematic, pos)) {
            // Dirt where farmland goes, the hoe's click missed or never came
            return "untilled";
        }
        String notReady = PlacementOrder.whyNotReady(mc.level, schematic, pos, schematic.getBlockState(pos));
        if (notReady != null) {
            // A stem on dirt that should have been tilled: its own cause (it was "not-ready" with the rest)
            return Tiller.untilled(mc.level, schematic, pos.below()) ? "needs-tilling" : "not-ready";
        }
        // Sand with nothing under it yet (its string isn't in: out of string), a cactus that can't stand there yet:
        // these were all "out-of-reach-when-passing", 600 of a layer flown with no string, which hid the real cause
        net.minecraft.world.level.block.state.BlockState want = schematic.getBlockState(pos);
        if (want.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && mc.level.getBlockState(pos.below()).canBeReplaced()) {
            return "nothing-under-it(" + LayerManager.blockName(schematic.getBlockState(pos.below())) + ")";
        }
        if (!want.canSurvive(mc.level, pos)) {
            return "cant-stand-there-yet";
        }
        String held = holdReason(pos);
        if (held != null) {
            return "held(" + held + ")";
        }
        if (clicks.containsKey(pos)) {
            return "clicked-no-result";
        }
        return "out-of-reach-when-passing";
    }

    /** All lanes of the layer are flown: what's left is done spot by spot. */
    public static void lanesDone(int left) {
        if (!active) {
            return;
        }
        laneEnd("all-lanes-flown");
        lanesDone = true;
        layerLeftovers = left;
    }

    /** A layer (or layers built together) is done. */
    public static void layerEnd(String name) {
        if (!active) {
            return;
        }
        laneEnd("layer-done");
        long ticks = Math.max(1, layer.total());
        emit("LAYER-END", kv("layer", name, "time", seconds(ticks), "placed", layer.placed, "placedPerSec",
                round(layer.placed / (ticks / 20.0)), "items", layerItems.toString(),
                "lanePlaced", layerLanePlaced, "laneTime", seconds(layerLaneTicks), "lanePlacedPerSec",
                round(layerLaneTicks == 0 ? 0 : layerLanePlaced / (layerLaneTicks / 20.0)),
                "leftAfterLanes", layerLeftovers < 0 ? "-" : layerLeftovers, "leftoverPlaced", layerLeftoverPlaced,
                "leftoverTime", seconds(layerLeftoverTicks), "leftoverPlacedPerSec",
                round(layerLeftoverTicks == 0 ? 0 : layerLeftoverPlaced / (layerLeftoverTicks / 20.0)),
                "trips", layerTrips, "travel", round(layerTravel), "top3", layer.top(3), "times", layer.breakdown()));
        // The short line to compare layers and runs by
        emit("LAYER-DONE", kv("layer", name.replace("Layers ", "").replace("Layer ", "").replace(" ", ""), "time", seconds(ticks),
                "placed", layer.placed, "rate", Math.round(layer.placed * 60.0 / (ticks / 20.0)) + "/m", "lanePlaced", layerLanePlaced,
                "leftovers", layerLeftovers < 0 ? "-" : layerLeftovers, "leftoverTime", seconds(layerLeftoverTicks),
                "buy", seconds(layer.ticks[Cat.BUYING.ordinal()] + layer.ticks[Cat.VAULT.ordinal()]), "missed", layerMisses,
                "left", doneLeft, "untilled", doneUntilled, "stemsSkipped", doneCrops, "leftList", doneLeftList.isEmpty() ? "-" : doneLeftList,
                "tilledByAddon", AutoBuild.INSTANCE.tiller.tilledCount() - layerTilledStart));
        doneLeft = doneUntilled = doneCrops = 0;
        doneLeftList = "";
        resetLayer(AutoBuild.clientTicks, name);
    }

    private static void resetLayer(long now, String name) {
        runLeftoverTicks += layerLeftoverTicks;
        layerTilledStart = AutoBuild.INSTANCE.tiller.tilledCount();
        layer.reset(now);
        layerName = name;
        layerItems.clear();
        layerLanePlaced = 0;
        layerLaneTicks = 0;
        layerLeftoverPlaced = 0;
        layerLeftoverTicks = 0;
        layerLeftovers = -1;
        layerTrips = 0;
        layerTravel = 0;
        layerMisses = 0;
        lanesDone = false;
    }

    /** A flight begins that isn't along a lane: to a lane, a spot, a chest... */
    public static void tripStart(Minecraft mc, String reason) {
        if (!active || mc.player == null) {
            return;
        }
        if (tripOn) {
            tripEnd(mc, "replanned");
        }
        tripOn = true;
        tripReason = reason;
        tripFrom = mc.player.position();
        tripLast = tripFrom;
        tripStart = AutoBuild.clientTicks;
        tripMaxY = tripFrom.y;
        tripDistance = 0;
        tripPlacedAtStart = AutoBuild.INSTANCE.stats.totalPlaced();
    }

    /** The flight is over (arrived, or something else came up). */
    public static void tripEnd(Minecraft mc, String end) {
        if (!active || !tripOn || mc.player == null) {
            return;
        }
        tripOn = false;
        Vec3 to = mc.player.position();
        layerTrips++;
        runTrips++;
        layerTravel += tripDistance;
        runTravel += tripDistance;
        emit("TRIP", kv("reason", tripReason, "end", end, "from", pos(tripFrom), "to", pos(to), "dist", round(tripDistance),
                "straightDist", round(tripFrom.distanceTo(to)), "time", seconds(AutoBuild.clientTicks - tripStart),
                "maxY", round(tripMaxY), "placedOnWay", AutoBuild.INSTANCE.stats.totalPlaced() - tripPlacedAtStart,
                "placeableOnArrival", AutoBuild.INSTANCE.mover.printerReachCount()));
    }

    // ---- trips for items

    /** A trip for items begins (something ran low). */
    public static void supplyStart(Minecraft mc, String trigger) {
        if (!active || supplyOn || mc.player == null) {
            return;
        }
        supplyOn = true;
        supplyStart = AutoBuild.clientTicks;
        supplyTrigger = trigger;
        supplyWhere = AutoBuild.INSTANCE.mover.laneName() + (laneOn && lanePlanned > 0
                ? " laneDone=" + Math.round(laneDoneNow(mc) * 100.0 / lanePlanned) + "%" : "");
        supplyInvFree = InventoryCleaner.freeSlots(mc.player.getInventory());
        supplyVaultFree = AutoBuild.INSTANCE.fetcher.knownFreeSlots();
        supplySpent = AutoBuild.INSTANCE.spentTotal();
        supplyQuiet = 0;
        supplyPurchases = 0;
        supplyBought.clear();
        lastSupplySlots = slotUse(mc.player);
        emit("SUPPLY-START", kv("trigger", trigger, "lane", supplyWhere, "invFree", supplyInvFree, "vaultFree", supplyVaultFree,
                "slots", lastSupplySlots));
    }

    /**
     * How many of the lane's own blocks are done by now (not all blocks placed since it began: those of the rows beside
     * it as well had "laneDone=197%").
     */
    private static int laneDoneNow(Minecraft mc) {
        fi.dy.masa.litematica.world.WorldSchematic schematic = SchematicHelper.world();
        if (mc.level == null || schematic == null) {
            return 0;
        }
        int done = 0;
        for (BlockPos pos : laneBlocks) {
            if (BlockCheck.check(mc.level, schematic, pos) == BlockCheck.Result.OK) {
                done++;
            }
        }
        return done;
    }

    private static void supplyEnd(Minecraft mc) {
        if (!supplyOn) {
            return;
        }
        supplyOn = false;
        emit("SUPPLY-END", kv("trigger", supplyTrigger, "time", seconds(AutoBuild.clientTicks - supplyStart), "purchases", supplyPurchases,
                "bought", supplyBought.toString(), "spent", Math.round(AutoBuild.INSTANCE.spentTotal() - supplySpent),
                "invFreeBefore", supplyInvFree, "invFreeAfter", mc.player == null ? -1 : InventoryCleaner.freeSlots(mc.player.getInventory()),
                "vaultFreeBefore", supplyVaultFree, "vaultFreeAfter", AutoBuild.INSTANCE.fetcher.knownFreeSlots(), "lane", supplyWhere,
                "slots", mc.player == null ? "-" : slotUse(mc.player)));
    }

    /**
     * What the inventory's 36 slots hold, for the supply lines: the layer's items (and how many of those stacks aren't
     * full), junk, blocks the build doesn't use, tools and the rest. Each slot not free is a stack less in a purchase.
     */
    private static String slotUse(LocalPlayer player) {
        java.util.Set<Item> layerItems = AutoBuild.INSTANCE.layers.layerNeeds().keySet();
        int build = 0;
        int partial = 0;
        int junk = 0;
        int strays = 0;
        int tools = 0;
        int other = 0;
        int free = 0;
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                free++;
            } else if (layerItems.contains(stack.getItem())) {
                build++;
                if (stack.getCount() < stack.getMaxStackSize()) {
                    partial++;
                }
            } else if (InventoryCleaner.isJunk(stack)) {
                junk++;
            } else if (stack.isDamageableItem()) {
                tools++;
            } else if (AutoBuild.INSTANCE.cleaner.isStray(stack)) {
                strays++;
            } else {
                other++;
            }
        }
        return "free=" + free + " build=" + build + " buildPartial=" + partial + " junk=" + junk + " notForBuild=" + strays
                + " tools=" + tools + " other=" + other;
    }

    /** A purchase is done: what, how many, the price, the clicks and how long. */
    public static void purchase(Item item, int count, double price, int clicks, long ticks) {
        if (!active) {
            return;
        }
        runPurchases++;
        supplyPurchases++;
        supplyBought.merge(itemName(item), count, Integer::sum);
        emit("BUY", kv("item", itemName(item), "count", count, "price", Math.round(price), "clicks", clicks, "time", seconds(ticks)));
    }

    // ---- events

    /** The player rose up away from a cactus (or anything else that hurts) before touching it. */
    public static void harmLift() {
        runHarmLifts++;
    }

    /** A cactus (or berry bush) of the build really hurt the player. */
    public static void harmTouch() {
        runHarmTouches++;
    }

    /** Junk thrown out (see InventoryCleaner#isJunk). */
    public static void junkThrown(int items) {
        runJunkThrown += items;
    }

    /** Blocks the build doesn't use put in a vault (see InventoryCleaner#isStray). */
    public static void straysStored(int items) {
        runStraysStored += items;
    }

    /** Something happened: damage, a disconnect, a reconnect... */
    public static void event(String type, String fields) {
        if (active) {
            BuildLog.line("EVENT", "type=" + type + " " + fields);
        } else {
            BuildLog.lineOrKeep("EVENT", "type=" + type + " " + fields);
        }
    }

    /** Something the player did while it ran (a setting, the layer, stopping). */
    public static void user(String what, String fields) {
        BuildLog.line("USER", "action=" + what + (fields.isEmpty() ? "" : " " + fields));
    }

    // ---- the record of runs, per server, to compare with

    private static Path runsFile() {
        return fi.dy.masa.malilib.util.FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("runs.txt");
    }

    private static void saveRun(double spent) {
        // Only builds are compared (an Auto break run places nothing, and a short one says little)
        if (run.placed < 100) {
            return;
        }
        try {
            StringBuilder cats = new StringBuilder();
            for (Cat cat : CATS) {
                cats.append(cats.length() == 0 ? "" : ",").append(cat.key).append('=').append(run.ticks[cat.ordinal()]);
            }
            String line = server + "|" + runId + "|" + version + "|" + System.currentTimeMillis() + "|" + run.placed + "|" + run.total()
                    + "|" + cats + "|" + runClicks + "," + runMissed + "," + runWrong + "," + giveUps.values().stream().mapToInt(Integer::intValue).sum()
                    + "," + Math.round(spent);
            Files.createDirectories(runsFile().getParent());
            List<String> lines = Files.isRegularFile(runsFile()) ? new ArrayList<>(Files.readAllLines(runsFile())) : new ArrayList<>();
            lines.add(line);
            // The last 200 runs are enough
            if (lines.size() > 200) {
                lines = lines.subList(lines.size() - 200, lines.size());
            }
            Files.write(runsFile(), lines);
        } catch (Exception e) {
            PrinterAddon.debug("Couldn't save the run: {}", e.toString());
        }
    }

    /**
     * Against the last run on this server with at least 500 blocks: blocks a minute, and each category in seconds per
     * 1000 blocks placed (runs differ in length), what got better and what got worse.
     */
    private static void compareWithLast() {
        try {
            if (!Files.isRegularFile(runsFile()) || run.placed < 100) {
                return;
            }
            String[] last = null;
            for (String line : Files.readAllLines(runsFile())) {
                String[] parts = line.split("\\|");
                if (parts.length >= 7 && parts[0].equals(server) && Integer.parseInt(parts[4]) >= 500) {
                    last = parts;
                }
            }
            if (last == null) {
                emit("RUN-COMPARE", kv("previous", "none on this server yet"));
                return;
            }
            int placedBefore = Integer.parseInt(last[4]);
            long ticksBefore = Math.max(1, Long.parseLong(last[5]));
            Map<String, Long> before = new HashMap<>();
            for (String part : last[6].split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    before.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
                }
            }
            double perMinBefore = placedBefore * 1200.0 / ticksBefore;
            double perMinNow = run.placed * 1200.0 / Math.max(1, run.total());
            StringBuilder better = new StringBuilder();
            StringBuilder worse = new StringBuilder();
            for (Cat cat : CATS) {
                if (cat == Cat.PLACING) {
                    continue;
                }
                double was = before.getOrDefault(cat.key, 0L) / 20.0 * 1000 / Math.max(1, placedBefore);
                double now = run.ticks[cat.ordinal()] / 20.0 * 1000 / Math.max(1, run.placed);
                double change = now - was;
                if (Math.abs(change) < 1) {
                    continue;
                }
                StringBuilder to = change < 0 ? better : worse;
                to.append(to.length() == 0 ? "" : " ").append(cat.key).append('=').append(String.format(Locale.ROOT, "%+.1fs", change));
            }
            emit("RUN-COMPARE", kv("previous", last[1], "previousVersion", last[2], "placedPerMin",
                    String.format(Locale.ROOT, "%.0f->%.0f(%+.0f%%)", perMinBefore, perMinNow, perMinBefore == 0 ? 0 : (perMinNow / perMinBefore - 1) * 100),
                    "per1000Blocks", "seconds of each per 1000 blocks placed", "better", better.length() == 0 ? "-" : better.toString(),
                    "worse", worse.length() == 0 ? "-" : worse.toString()));
        } catch (Exception e) {
            PrinterAddon.debug("Couldn't compare with the last run: {}", e.toString());
        }
    }

    // ---- writing

    private static void openJson() {
        closeJson();
        Path file = BuildLog.file();
        if (file == null) {
            return;
        }
        try {
            String name = file.getFileName().toString();
            currentFile = file.resolveSibling((name.endsWith(".log") ? name.substring(0, name.length() - 4) : name) + ".jsonl");
            json = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8);
            // What happened since the last build (joining again, getting back to the build) comes first
            for (String[] line : BuildLog.takePending()) {
                json.write("{\"run\":\"" + runId + "\",\"time\":\"" + line[0] + "\",\"t\":" + line[1] + ",\"type\":" + quote(line[2])
                        + ",\"text\":" + quote(line[3]) + "}");
                json.newLine();
            }
        } catch (IOException e) {
            json = null;
        }
    }

    private static void closeJson() {
        if (json != null) {
            try {
                json.close();
            } catch (IOException ignored) {
            }
            json = null;
        }
    }

    /** Every build log line also into the .jsonl file (its kind and text; the run's own lines field by field). */
    static void mirror(String time, long tick, String kind, String text) {
        BufferedWriter out = json;
        if (out == null || pendingFields != null) {
            return;
        }
        try {
            out.write("{\"run\":\"" + runId + "\",\"time\":\"" + time + "\",\"t\":" + tick + ",\"type\":" + quote(kind) + ",\"text\":" + quote(text) + "}");
            out.newLine();
        } catch (IOException e) {
            closeJson();
        }
    }

    static boolean jsonOpen() {
        return json != null;
    }

    static void flushJson() {
        if (json != null) {
            try {
                json.flush();
            } catch (IOException e) {
                closeJson();
            }
        }
    }

    @Nullable
    private static Map<String, Object> pendingFields;

    /** A line of fields: "KIND key=value key=value" in the build log, and the fields one by one in the .jsonl file. */
    private static void emit(String kind, Map<String, Object> fields) {
        StringBuilder text = new StringBuilder();
        fields.forEach((key, value) -> {
            String v = String.valueOf(value);
            text.append(text.length() == 0 ? "" : " ").append(key).append('=')
                    .append(v.contains(" ") && !v.startsWith("{") ? "\"" + v + "\"" : v);
        });
        pendingFields = fields;
        BuildLog.line(kind, text.toString());
        pendingFields = null;
        BufferedWriter out = json;
        if (out != null) {
            try {
                StringBuilder sb = new StringBuilder("{\"run\":\"").append(runId).append("\",\"v\":\"").append(version)
                        .append("\",\"t\":").append(AutoBuild.clientTicks).append(",\"type\":").append(quote(kind));
                fields.forEach((key, value) -> sb.append(',').append(quote(key)).append(':')
                        .append(value instanceof Number ? value.toString() : quote(String.valueOf(value))));
                out.write(sb.append('}').toString());
                out.newLine();
            } catch (IOException e) {
                closeJson();
            }
        }
    }

    private static Map<String, Object> kv(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    private static String pos(Vec3 at) {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", at.x, at.y, at.z);
    }

    private static String itemName(@Nullable Item item) {
        return item == null ? "nothing" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
