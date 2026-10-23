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
        layerTickCount = totalTickCount = 0;
    }

    /** The printer queued a placement. */
    public void placed() {
        layerPlaced++;
        totalPlaced++;
    }

    public void tick(Activity activity) {
        layerTicks.merge(activity, 1, Integer::sum);
        totalTicks.merge(activity, 1, Integer::sum);
        layerTickCount++;
        totalTickCount++;
    }

    /** A layer (or the final pass) is done: report it and start counting the next one. */
    public void layerDone(String layer) {
        String line = layer + ": " + line(layerPlaced, layerTickCount, System.currentTimeMillis() - layerStartMillis, layerTicks);
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
                .append(String.format(" (%.0f/min, printer used %d%% of its turns)", placed * 60_000.0 / Math.max(1, millis),
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
