package no.sindrens.printeraddon.build;

import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.util.ServerName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How far from the eye clicks still work on this server, learned from every click the printer makes (see
 * {@link Steps}). Kept per server between games.
 */
public final class ReachStats {
    private static Steps steps = new Steps();
    private static String server = "";
    private static int sinceSave;

    private ReachStats() {
    }

    /** A click this far from the eye placed something, or nothing. */
    public static void clicked(double distance, boolean worked) {
        load();
        steps.clicked(distance, worked);
        if (++sinceSave >= 500) {
            save();
        }
    }

    /** The farthest distance to plan with (at most {@code max}). */
    public static double reach(double max) {
        load();
        // Old numbers fade a little every minute, so a distance that stopped working can be tried again
        if (AutoBuild.clientTicks - lastFade >= 20 * 60) {
            lastFade = AutoBuild.clientTicks;
            steps.fade(0.9);
        }
        return steps.reach(max);
    }

    private static long lastFade;

    /** Why the reach is what it is, for the build log. */
    public static String explain(double max) {
        return steps.explain(max);
    }

    /**
     * How often a click placed something, per quarter block of distance from the eye (recent clicks count most:
     * about the last 200 per step). The planning reach stops below the first distance where clicks work clearly
     * less often than close by (3 points or more worse), so one unlucky miss changes nothing, a block that can't be
     * placed anyway (it fails close by too) isn't blamed on the distance, and it grows back by itself once clicks
     * there work again.
     */
    public static final class Steps {
        private static final double FROM = 2.0;
        private static final double STEP = 0.25;
        private static final int STEPS = 16;
        /** Older clicks fade: a click 200 clicks ago (in that step) counts about a third. */
        private static final double KEEP = 0.995;
        /** Clicks in a step before it counts. */
        private static final double ENOUGH = 20;
        /** Closer than this: what fails anyway, whatever the distance. */
        private static final double CLOSE = 3.5;
        /** How much worse than close by a distance may do before planning stays below it. */
        private static final double WORSE = 0.03;

        final double[] tries = new double[STEPS];
        final double[] placed = new double[STEPS];

        public void clicked(double distance, boolean worked) {
            int step = (int) Math.floor((distance - FROM) / STEP);
            if (step < 0 || step >= STEPS) {
                return;
            }
            tries[step] = tries[step] * KEEP + 1;
            placed[step] = placed[step] * KEEP + (worked ? 1 : 0);
        }

        /** The farthest distance to plan with (at most {@code max}): a step below the first one that works clearly worse. */
        public double reach(double max) {
            int bad = badStep(max);
            return bad < 0 ? max : Math.max(2.5, Math.min(max, FROM + bad * STEP - STEP));
        }

        public String explain(double max) {
            int bad = badStep(max);
            double close = closeRate();
            if (bad < 0) {
                return String.format(Locale.ROOT, "clicks up to %.2f away work as well as close by (%.0f%%)", max + STEP, close * 100);
            }
            return String.format(Locale.ROOT, "clicks %.2f to %.2f away place something %.0f%% of the time, close by %.0f%%",
                    FROM + bad * STEP, FROM + (bad + 1) * STEP, 100 * placed[bad] / tries[bad], close * 100);
        }

        private int badStep(double max) {
            double close = closeRate();
            for (int step = 0; step < STEPS; step++) {
                double low = FROM + step * STEP;
                if (low < CLOSE || low > max + STEP) {
                    continue;
                }
                // Clearly worse: even the best it could really be (95% sure) is below close by
                if (tries[step] >= ENOUGH && upper(placed[step], tries[step]) < close - WORSE) {
                    return step;
                }
            }
            return -1;
        }

        /** The highest the real success rate could be, 95% sure (Wilson score), from {@code n} clicks. */
        static double upper(double worked, double n) {
            double z = 1.96;
            double p = worked / n;
            double z2 = z * z;
            return (p + z2 / (2 * n) + z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))) / (1 + z2 / n);
        }

        /** Every number counts this much less (older clicks fade). */
        public void fade(double keep) {
            for (int i = 0; i < STEPS; i++) {
                tries[i] *= keep;
                placed[i] *= keep;
            }
        }

        /** How often clicks close by place something (1 until there are enough of them). */
        private double closeRate() {
            double t = 0;
            double p = 0;
            for (int step = 0; step < STEPS; step++) {
                if (FROM + (step + 1) * STEP <= CLOSE) {
                    t += tries[step];
                    p += placed[step];
                }
            }
            return t >= ENOUGH ? p / t : 1.0;
        }

        String write() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < STEPS; i++) {
                sb.append(String.format(Locale.ROOT, "%.2f %.2f%n", tries[i], placed[i]));
            }
            return sb.toString();
        }

        static Steps read(String text) {
            Steps steps = new Steps();
            String[] lines = text.split("\n");
            for (int i = 0; i < Math.min(STEPS, lines.length); i++) {
                String[] parts = lines[i].trim().split(" ");
                if (parts.length == 2) {
                    steps.tries[i] = Double.parseDouble(parts[0]);
                    steps.placed[i] = Math.min(steps.tries[i], Double.parseDouble(parts[1]));
                }
            }
            return steps;
        }
    }

    private static Path file(String server) {
        return FileUtils.getConfigDirectory().resolve(PrinterAddon.MOD_ID).resolve("reach")
                .resolve(server.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
    }

    /** This server's numbers, read when the server changes. */
    private static void load() {
        Minecraft mc = Minecraft.getInstance();
        String now = mc == null || mc.level == null ? server : ServerName.current(mc);
        if (now.equals(server)) {
            return;
        }
        save();
        server = now;
        steps = new Steps();
        try {
            Path file = file(server);
            if (Files.isRegularFile(file)) {
                steps = Steps.read(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            PrinterAddon.LOGGER.warn("Couldn't read the reach numbers for {}", server, e);
        }
    }

    /** Written down (every 500 clicks, and when auto build stops). */
    public static void save() {
        sinceSave = 0;
        if (server.isEmpty()) {
            return;
        }
        try {
            Path file = file(server);
            Files.createDirectories(file.getParent());
            Files.writeString(file, steps.write(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrinterAddon.LOGGER.warn("Couldn't save the reach numbers for {}", server, e);
        }
    }
}
