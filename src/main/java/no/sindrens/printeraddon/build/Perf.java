package no.sindrens.printeraddon.build;

import java.util.LinkedHashMap;
import java.util.Map;

/** Time spent per part of the add-on (render thread), for the once-a-minute log line. */
public final class Perf {
    private static final Map<String, long[]> TIMES = new LinkedHashMap<>();
    private static long ticks;

    private Perf() {
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void end(String part, long start) {
        long took = System.nanoTime() - start;
        long[] t = TIMES.computeIfAbsent(part, k -> new long[2]);
        t[0] += took;
        t[1] = Math.max(t[1], took);
    }

    public static void tick() {
        ticks++;
    }

    /** "part avg/max ms per tick, ..." since the last call, slowest first; then starts over. */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        long n = Math.max(1, ticks);
        TIMES.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> sb.append(e.getKey()).append(' ')
                        .append(String.format(java.util.Locale.ROOT, "%.2f", e.getValue()[0] / 1e6 / n)).append('/')
                        .append(String.format(java.util.Locale.ROOT, "%.1f", e.getValue()[1] / 1e6)).append(" ms, "));
        TIMES.clear();
        ticks = 0;
        return sb.isEmpty() ? "-" : sb.substring(0, sb.length() - 2);
    }
}
