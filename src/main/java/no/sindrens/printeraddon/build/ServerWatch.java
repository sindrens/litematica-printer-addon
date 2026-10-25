package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.Map;

/**
 * Watches how the server keeps up with the printer's clicks, and holds placing back before it gets into trouble: the
 * disconnects ("timed out") came right after clicks had gone unanswered for seconds. A click without an answer for 2 s
 * stops placing until the answer comes, then it places at most 8 blocks a second, after 30 s 12, then 16, then no
 * limit again. Answers coming slowly on average (well over the ping) set a limit too. Every 10 s while building, a line
 * in the build log says what it saw: blocks a second, how fast clicks were answered, the server's ticks per second, the
 * ping and the packets sent a second, by kind.
 */
public final class ServerWatch {
    /** One report this often (ticks). */
    private static final int WINDOW = 20 * 10;
    /** A click without an answer this long (ticks, or 3 pings and a half a second if that's longer): placing waits. */
    private static final int STALL_TICKS = 40;
    /** The limits after trouble, blocks a second, one step up every CAP_STEP ticks without new trouble. */
    private static final int[] CAPS = {8, 12, 16};
    private static final int CAP_STEP = 20 * 30;
    /** Answers over the last ones taking this many times the ping (plus a little) on average: slow. */
    private static final double SLOW_FACTOR = 3;
    private static final int SLOW_SAMPLES = 20;

    /** Sent packets by kind in this report's time (some are sent from the network thread: keep-alives). */
    private static final Map<String, Integer> packets = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger packetsTotal = new java.util.concurrent.atomic.AtomicInteger();
    private static int answers;
    private static long answerTicks;
    private static int answerMax;
    private static final int[] lastAnswers = new int[SLOW_SAMPLES];
    private static int lastAnswerCount;
    private static int placed;
    private static long windowStart = -1;
    private static int stalls;
    private static long stalledFor;

    /** -1: no limit, else the step in CAPS. */
    private static int capLevel = -1;
    private static long capUntil;
    private static boolean stalled;
    private static long stallStart;
    private static String capWhy = "";

    private ServerWatch() {
    }

    public static void reset() {
        packets.clear();
        packetsTotal.set(0);
        answers = 0;
        answerTicks = 0;
        answerMax = 0;
        lastAnswerCount = 0;
        placed = 0;
        windowStart = -1;
        stalls = 0;
        stalledFor = 0;
        capLevel = -1;
        stalled = false;
        capWhy = "";
    }

    /** Every packet the client sends (counted by kind for the report; cheap). */
    public static void onPacketOut(Packet<?> packet) {
        packetsTotal.incrementAndGet();
        String kind;
        try {
            kind = packet.type().id().getPath();
        } catch (RuntimeException e) {
            kind = packet.getClass().getSimpleName();
        }
        packets.merge(kind, 1, Integer::sum);
    }

    /** The server answered a click after this many ticks. */
    public static void onAnswer(long took) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, took);
        answers++;
        answerTicks += ticks;
        answerMax = Math.max(answerMax, ticks);
        lastAnswers[lastAnswerCount++ % SLOW_SAMPLES] = ticks;
    }

    /** The printer queued a block. */
    public static void onPlaced() {
        placed++;
    }

    /** At most this many blocks a second for now (0: no limit from the server's side). */
    public static int cap() {
        return capLevel < 0 ? 0 : CAPS[capLevel];
    }

    /** A click has gone unanswered for long: nothing is placed until the server answers. */
    public static boolean stalled() {
        return stalled;
    }

    /** For the on-screen display: the limit now and why, or null when there's none. */
    public static String describe() {
        if (stalled) {
            return "waiting for the server to answer";
        }
        return capLevel < 0 ? null : "at most " + cap() + " blocks a second (" + capWhy + ")";
    }

    public static void tick(Minecraft mc) {
        long now = AutoBuild.clientTicks;
        if (windowStart < 0) {
            windowStart = now;
        }
        // Not in singleplayer (the test world lags with the game itself), unless a test asks for it
        if (!Configs.Speed.SPEED_GUARD.getBooleanValue() || mc.hasSingleplayerServer() && System.getenv("PRINTER_ADDON_GUARD") == null) {
            stalled = false;
            capLevel = -1;
            report(mc, now);
            return;
        }
        int ping = ServerLag.pingTicks(mc);
        int threshold = Math.max(STALL_TICKS, 3 * ping + 10);
        int oldest = ServerAcks.oldestWaiting();
        if (!stalled && oldest >= threshold) {
            stalled = true;
            stallStart = now - oldest;
            stalls++;
            limit(0, "the server took over " + seconds(threshold) + " to answer a click");
            BuildLog.line("GUARD", "a click has had no answer for " + seconds(oldest) + " (ping " + ping * 50 + " ms, "
                    + ServerAcks.waitingCount() + " clicks waiting, " + ServerLag.describe() + "): placing waits for the answer,"
                    + " then at most " + CAPS[0] + " blocks a second");
            Chat.quiet("The server is slow to answer: placing waits a moment, then goes on slower.");
        } else if (stalled && oldest < threshold / 2) {
            stalled = false;
            stalledFor += now - stallStart;
            BuildLog.line("GUARD", "the server answered again after " + seconds(now - stallStart) + ": at most " + cap()
                    + " blocks a second for " + seconds(CAP_STEP) + ", then more step by step");
        }
        // Answers slow on average (the server falls behind with the clicks): a limit before it gets worse
        if (!stalled && lastAnswerCount >= SLOW_SAMPLES) {
            double average = 0;
            for (int ticks : lastAnswers) {
                average += ticks;
            }
            average /= SLOW_SAMPLES;
            if (average > SLOW_FACTOR * ping + 6) {
                int level = capLevel < 0 ? CAPS.length - 1 : Math.max(0, capLevel - 1);
                if (capLevel < 0 || level < capLevel || now >= capUntil - CAP_STEP / 2) {
                    limit(level, String.format(java.util.Locale.ROOT, "the last %d clicks were answered in %.1f ticks on average, the ping is %d",
                            SLOW_SAMPLES, average, ping));
                    BuildLog.line("GUARD", capWhy + ": at most " + cap() + " blocks a second for " + seconds(CAP_STEP));
                }
                lastAnswerCount = 0;
            }
        }
        // No trouble for a while: a step faster, and no limit at the end
        if (capLevel >= 0 && !stalled && now >= capUntil) {
            capLevel++;
            capUntil = now + CAP_STEP;
            if (capLevel >= CAPS.length) {
                capLevel = -1;
                BuildLog.line("GUARD", "no trouble from the server for " + seconds(CAP_STEP) + ": full speed again");
            } else {
                BuildLog.line("GUARD", "no trouble from the server for " + seconds(CAP_STEP) + ": at most " + cap() + " blocks a second now");
            }
        }
        report(mc, now);
    }

    private static void limit(int level, String why) {
        capLevel = level;
        capUntil = AutoBuild.clientTicks + CAP_STEP;
        capWhy = why;
    }

    /** The line in the build log every 10 s while building. */
    private static void report(Minecraft mc, long now) {
        if (now - windowStart < WINDOW) {
            return;
        }
        double secs = (now - windowStart) / 20.0;
        if (AutoBuild.INSTANCE.isRunning()) {
            StringBuilder kinds = new StringBuilder();
            packets.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(6)
                    .forEach(e -> kinds.append(kinds.length() == 0 ? "" : ", ").append(e.getKey()).append(' ')
                            .append(String.format(java.util.Locale.ROOT, "%.1f", e.getValue() / secs)));
            int ping = mc.getConnection() == null || mc.player == null || mc.getConnection().getPlayerInfo(mc.player.getUUID()) == null
                    ? -1 : mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
            BuildLog.line("SERVER", String.format(java.util.Locale.ROOT,
                    "%.0f s: %.1f blocks/s | clicks answered in %s | %d waiting, oldest %s | %.1f ticks/s | ping %d ms | packets out %.0f/s (%s)%s%s",
                    secs, placed / secs,
                    answers == 0 ? "- (none)" : String.format(java.util.Locale.ROOT, "%.1f ticks on average, %d at most", answerTicks / (double) answers, answerMax),
                    ServerAcks.waitingCount(), seconds(ServerAcks.oldestWaiting()), ServerLag.tps(), ping, packetsTotal.get() / secs, kinds,
                    stalls > 0 ? " | " + stalls + " stall(s), " + seconds(stalledFor) + " waited" : "",
                    describe() != null ? " | " + describe() : ""));
        }
        packets.clear();
        packetsTotal.set(0);
        answers = 0;
        answerTicks = 0;
        answerMax = 0;
        placed = 0;
        stalls = 0;
        stalledFor = 0;
        windowStart = now;
    }

    private static String seconds(long ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f s", ticks / 20.0);
    }
}
