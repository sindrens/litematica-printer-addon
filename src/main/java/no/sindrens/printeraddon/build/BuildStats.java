package no.sindrens.printeraddon.build;

import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How a build went, per layer: time, blocks the printer placed, how many of the printer's turns (one per
 * printing interval) were used, and where the rest of the time went (flying, waiting, breaking, buying...).
 * Said in chat and the log when a layer is done, and kept for the end report.
 */
public class BuildStats {
    /** What the player spends a tick on. */
    public enum Activity { PLACING, FLYING, WALKING, BREAKING, BUCKET, RAILS, BUYING, WAITING, IDLE }

    private final List<String> layerLines = new ArrayList<>();
    private final Map<Activity, Integer> layerTicks = new EnumMap<>(Activity.class);
    private final Map<Activity, Integer> totalTicks = new EnumMap<>(Activity.class);
    private long layerStartMillis;
    private long buildStartMillis;
    private int layerPlaced;
    private int totalPlaced;
    private int layerTickCount;
    private int totalTickCount;

    public void start() {
        layerLines.clear();
        layerTicks.clear();
        totalTicks.clear();
        layerStartMillis = buildStartMillis = System.currentTimeMillis();
        layerPlaced = totalPlaced = 0;
        recent.clear();
        layerTickCount = totalTickCount = 0;
    }

    /** Times of recent placements, for the blocks-per-minute on screen. */
    private final java.util.ArrayDeque<Long> recent = new java.util.ArrayDeque<>();

    /** Placements in the last second (for the optional cap). */
    public int placedInLastSecond() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (java.util.Iterator<Long> it = recent.descendingIterator(); it.hasNext(); ) {
            if (now - it.next() > 1000) {
                break;
            }
            count++;
        }
        return count;
    }

    /** Blocks placed per real minute over the last minute (or since the start, if shorter). */
    public double recentRate() {
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) {
            recent.pollFirst();
        }
        long span = Math.min(60_000, Math.max(5_000, now - buildStartMillis));
        return recent.size() * 60_000.0 / span;
    }

    /** The printer queued a placement. */
    public void placed() {
        recent.addLast(System.currentTimeMillis());
        layerPlaced++;
        totalPlaced++;
    }

    /** What the printer did with a free turn (nothing queued, looking for the next block). */
    public enum Turn { PLACED, PAUSED, NOTHING_IN_REACH, NONE_READY, CANT_PLACE_YET, SKIPPED }

    private final int[] minuteTurns = new int[Turn.values().length];
    private final int[] recentTurns = new int[Turn.values().length];
    private long minuteStart = -1;
    private int minutePlaced;

    /**
     * Counts the printer's free turns. Once a minute in the log: how many were used for a block, and why the others
     * weren't (paused by the add-on, nothing unfinished in reach, none ready, none a printer guide can place yet).
     */
    /** Paused turns of the last minute by reason. */
    private final java.util.Map<String, Integer> minutePauses = new java.util.TreeMap<>();

    public void printerTurn(Turn turn) {
        if (turn == Turn.PAUSED) {
            minutePauses.merge(AutoBuild.INSTANCE.pauseReason, 1, Integer::sum);
        }
        minuteTurns[turn.ordinal()]++;
        recentTurns[turn.ordinal()]++;
        if (minuteStart < 0) {
            minuteStart = AutoBuild.clientTicks;
        }
        if (AutoBuild.clientTicks - minuteStart >= 20 * 60) {
            int all = Math.max(1, java.util.Arrays.stream(minuteTurns).sum());
            StringBuilder sb = new StringBuilder();
            for (Turn t : Turn.values()) {
                sb.append(", ").append(t.name().toLowerCase().replace('_', ' ')).append(' ')
                        .append(minuteTurns[t.ordinal()] * 100 / all).append('%');
            }
            PrinterAddon.debug("[rate] last minute: {} placed; printer's free turns{}; {}", totalPlaced - minutePlaced,
                    sb, AutoBuild.INSTANCE.speed.describe());
            if (!minutePauses.isEmpty()) {
                StringBuilder pauses = new StringBuilder();
                minutePauses.forEach((why, n) -> pauses.append(why).append(' ').append(n * 100 / all).append("%, "));
                PrinterAddon.debug("[rate] paused because: {}", pauses.substring(0, pauses.length() - 2));
                minutePauses.clear();
            }
            String perf = Perf.report();
            BuildLog.line("PERF", "last minute, avg/max per tick: " + perf);
            PrinterAddon.trace("[perf] {}", perf);
            java.util.Arrays.fill(minuteTurns, 0);
            minuteStart = AutoBuild.clientTicks;
            minutePlaced = totalPlaced;
        }
    }

    /** Share of the printer's free turns (recently) that placed a block, for the on-screen display. */
    public int printerBusyPercent() {
        int all = java.util.Arrays.stream(recentTurns).sum();
        int percent = all == 0 ? 0 : recentTurns[Turn.PLACED.ordinal()] * 100 / all;
        if (all > 400) {
            java.util.Arrays.fill(recentTurns, 0);
        }
        return percent;
    }

    /** The most common reason the printer had nothing to do, recently. */
    public String printerIdleReason() {
        Turn worst = null;
        for (Turn t : Turn.values()) {
            if (t != Turn.PLACED && (worst == null || recentTurns[t.ordinal()] > recentTurns[worst.ordinal()])) {
                worst = t;
            }
        }
        return worst == null || recentTurns[worst.ordinal()] == 0 ? "-" : worst.name().toLowerCase().replace('_', ' ');
    }

    public void tick(Activity activity) {
        layerTicks.merge(activity, 1, Integer::sum);
        totalTicks.merge(activity, 1, Integer::sum);
        layerTickCount++;
        totalTickCount++;
    }

    /** A layer (or the final pass) is done: report it and start counting the next one. */
    public void layerDone(String layer) {
        String line = layer + " [" + AutoBuild.INSTANCE.speed.describe() + "]: " + line(layerPlaced, layerTickCount, System.currentTimeMillis() - layerStartMillis, layerTicks);
        layerLines.add(line);
        PrinterAddon.LOGGER.info("[stats] {}", line);
        if (Configs.Generic.DETAILED_CHAT.getBooleanValue()) {
            Chat.detail(line);
        }
        layerTicks.clear();
        layerPlaced = 0;
        layerTickCount = 0;
        layerStartMillis = System.currentTimeMillis();
    }

    public int totalPlaced() {
        return totalPlaced;
    }

    public String total() {
        return "Whole build: " + line(totalPlaced, totalTickCount, System.currentTimeMillis() - buildStartMillis, totalTicks);
    }

    public List<String> layers() {
        return layerLines;
    }

    private static String line(int placed, int ticks, long millis, Map<Activity, Integer> spent) {
        int interval = Math.max(1, Configs.Speed.printerInterval());
        int turns = Math.max(1, ticks / interval);
        StringBuilder sb = new StringBuilder();
        sb.append(placed).append(" placed in ").append(time(millis))
                .append(String.format(" (%.0f per game minute, %.0f per real minute, %d%% of the interval turns used)",
                        placed * 1200.0 / Math.max(1, ticks), placed * 60_000.0 / Math.max(1, millis),
                        Math.min(100, placed * 100 / turns)));
        if (ticks > 0) {
            sb.append(" | time:");
            for (Map.Entry<Activity, Integer> entry : spent.entrySet()) {
                int percent = entry.getValue() * 100 / ticks;
                if (percent > 0) {
                    sb.append(' ').append(entry.getKey().name().toLowerCase()).append(' ').append(percent).append('%');
                }
            }
        }
        return sb.toString();
    }

    public static String time(long millis) {
        long s = millis / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }
}
