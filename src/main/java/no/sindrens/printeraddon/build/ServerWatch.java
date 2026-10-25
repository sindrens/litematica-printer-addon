package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Watches how the server keeps up with the printer's clicks, and holds placing back before it gets into trouble: the
 * disconnects ("timed out") came right after clicks had gone unanswered for seconds. A click without an answer for 2 s
 * while the server goes on as usual (its time update every second) was dropped by the server: it's forgotten and
 * placing goes on. Only when the server goes quiet too does placing wait; once it answers again, at most 8 blocks a
 * second until its answers come as fast as before (8 in a row), else a step up every 5 s. On the player's server the
 * old rule held placing 5 to 15 s for one dropped click, then limited it for 90 s: 1.5 to 2 minutes lost in 10.
 * Every 10 s while building, a line in the build log says what it saw: blocks a second, how fast clicks were answered
 * (p50, p95, max), the server's ticks per second, the ping and the packets sent a second, by kind.
 */
public final class ServerWatch {
    /** One report this often (ticks). */
    private static final int WINDOW = 20 * 10;
    /** A click without an answer this long (ticks, or 3 pings and half a second if that's longer): dropped, or a stall. */
    private static final int STALL_TICKS = 40;
    /** No time update from the server for this long (ticks; they come every second): it has gone quiet. */
    private static final int SILENT_TICKS = 40;
    /** The limits after trouble, blocks a second, one step up every CAP_STEP ticks without new trouble. */
    private static final int[] CAPS = {8, 12, 16};
    private static final int CAP_STEP = 20 * 5;
    /** This many answers in a row at the usual speed after the trouble: no limit any more. */
    private static final int NORMAL_SAMPLES = 8;
    /** The last answers taking this many times the ping (plus a little) in the middle (median): slow. */
    private static final double SLOW_FACTOR = 3;
    private static final int SLOW_SAMPLES = 20;
    /** Placements over the last ticks, for what the trouble cost. */
    private static final int RATE_TICKS = 20 * 10;

    /** Sent packets by kind in this report's time (some are sent from the network thread: keep-alives). */
    private static final Map<String, Integer> packets = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger packetsTotal = new java.util.concurrent.atomic.AtomicInteger();
    /** Answer times (ticks) in this report's time. */
    private static int[] windowAnswers = new int[64];
    private static int windowAnswerCount;
    private static final int[] lastAnswers = new int[SLOW_SAMPLES];
    private static int lastAnswerCount;
    private static int placed;
    private static int droppedInWindow;
    private static long windowStart = -1;
    private static int stalls;
    private static long stalledFor;

    private static final int[] placedPerTick = new int[RATE_TICKS];
    private static int placedThisTick;

    /** -1: no limit, else the step in CAPS. */
    private static int capLevel = -1;
    private static long capUntil;
    private static boolean stalled;
    private static long stallStart;
    private static String capWhy = "";
    /** Answers since the limit was set, to lift it as soon as they're quick again. */
    private static final int[] sinceCap = new int[NORMAL_SAMPLES];
    private static int sinceCapCount;

    /** The trouble being followed, from its start to full speed again (for its GUARD-END line). */
    private static long eventStart = -1;
    private static String eventTrigger = "";
    private static long eventSlowTicks;
    private static long eventCapStart = -1;
    private static int eventPlacedWhileCapped;
    private static double eventBaseline;
    private static int events;
    private static double lostBlocksTotal;
    private static double lostSecondsTotal;

    private ServerWatch() {
    }

    public static void reset() {
        packets.clear();
        packetsTotal.set(0);
        windowAnswerCount = 0;
        lastAnswerCount = 0;
        placed = 0;
        droppedInWindow = 0;
        windowStart = -1;
        stalls = 0;
        stalledFor = 0;
        Arrays.fill(placedPerTick, 0);
        placedThisTick = 0;
        capLevel = -1;
        stalled = false;
        capWhy = "";
        sinceCapCount = 0;
        eventStart = -1;
        eventCapStart = -1;
        events = 0;
        lostBlocksTotal = 0;
        lostSecondsTotal = 0;
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
        if (windowAnswerCount == windowAnswers.length) {
            windowAnswers = Arrays.copyOf(windowAnswers, windowAnswers.length * 2);
        }
        windowAnswers[windowAnswerCount++] = ticks;
        lastAnswers[lastAnswerCount++ % SLOW_SAMPLES] = ticks;
        if (capLevel >= 0) {
            sinceCap[sinceCapCount++ % NORMAL_SAMPLES] = ticks;
        }
    }

    /** The printer queued a block. */
    public static void onPlaced() {
        placed++;
        placedThisTick++;
        if (capLevel >= 0) {
            eventPlacedWhileCapped++;
        }
    }

    /** At most this many blocks a second for now (0: no limit from the server's side). */
    public static int cap() {
        return capLevel < 0 ? 0 : CAPS[capLevel];
    }

    /** A click has gone unanswered for long and the server is quiet: nothing is placed until it answers. */
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

    /** Trouble events, blocks and seconds they cost since the start (for the end of the build). */
    public static String summary() {
        return String.format(Locale.ROOT, "events=%d lostBlocks=%.0f lostTime=%.1fs droppedClicks=%d", events, lostBlocksTotal,
                lostSecondsTotal, ServerAcks.droppedCount());
    }

    public static double lostSeconds() {
        return lostSecondsTotal;
    }

    public static void tick(Minecraft mc) {
        long now = AutoBuild.clientTicks;
        if (windowStart < 0) {
            windowStart = now;
        }
        placedPerTick[(int) (now % RATE_TICKS)] = placedThisTick;
        placedThisTick = 0;
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
        int quietFor = ServerLag.ticksSinceTimeUpdate();
        boolean quiet = quietFor >= SILENT_TICKS;

        // No answer while the server goes on as usual: the server dropped the click. Forget it (waiting for it held
        // placing until the next click's answer covered it, and there was none while placing waited)
        if (oldest >= threshold && !quiet) {
            List<BlockPos> spots = ServerAcks.dropOlderThan(threshold);
            if (!spots.isEmpty()) {
                droppedInWindow += spots.size();
                BuildLog.line("GUARD", String.format(Locale.ROOT,
                        "dropped clicks=%d at %s: no answer in %s while the server went on as usual (time update %s ago, %.1f ticks/s, ping %d ms): not waited for",
                        spots.size(), spots.subList(0, Math.min(3, spots.size())).stream().map(BlockPos::toShortString).toList(),
                        seconds(threshold), seconds(quietFor), ServerLag.tps(), pingMs(mc)));
            }
            oldest = ServerAcks.oldestWaiting();
            if (stalled) {
                endStall(now, "the server goes on as usual, the clicks it didn't answer are forgotten");
            }
        }
        if (!stalled && oldest >= threshold && quiet) {
            stalled = true;
            stallStart = now - oldest;
            stalls++;
            startEvent(now - oldest, "no answer for " + seconds(oldest) + " and no time update for " + seconds(quietFor));
            BuildLog.line("GUARD", "a click has had no answer for " + seconds(oldest) + " and the server sent nothing for " + seconds(quietFor)
                    + " (ping " + pingMs(mc) + " ms, " + ServerAcks.waitingCount() + " clicks waiting, " + ServerLag.describe()
                    + "): placing waits for the server, then at most " + CAPS[0] + " blocks a second until it answers as fast as before");
            Chat.quiet("The server has gone quiet: placing waits a moment, then goes on slower until it keeps up.");
        } else if (stalled && oldest < threshold / 2) {
            endStall(now, "the server answered again");
        }

        // Answers slow in the middle (the server falls behind with the clicks): a limit before it gets worse. The
        // median, not the average: one dropped click counted as a 15 s answer made 20 quick ones look slow
        if (!stalled && lastAnswerCount >= SLOW_SAMPLES) {
            int median = median(lastAnswers, SLOW_SAMPLES);
            if (median > SLOW_FACTOR * ping + 6) {
                int level = capLevel < 0 ? CAPS.length - 1 : Math.max(0, capLevel - 1);
                if (capLevel < 0 || level < capLevel) {
                    if (eventStart < 0) {
                        startEvent(now, "slow answers (median " + median + " ticks of the last " + SLOW_SAMPLES + ", ping " + ping + " ticks)");
                    }
                    limit(level, String.format(Locale.ROOT, "the last %d clicks were answered in %d ticks in the middle, the ping is %d", SLOW_SAMPLES, median, ping));
                    BuildLog.line("GUARD", capWhy + ": at most " + cap() + " blocks a second until the answers are quick again");
                }
            }
            lastAnswerCount = 0;
        }

        // The answers quick again: no limit at once; else a step faster every few seconds without new trouble
        if (capLevel >= 0 && !stalled) {
            int normal = 2 * ping + 4;
            if (sinceCapCount >= NORMAL_SAMPLES && median(sinceCap, NORMAL_SAMPLES) <= normal) {
                capLevel = -1;
                BuildLog.line("GUARD", "the last " + NORMAL_SAMPLES + " clicks were answered in " + median(sinceCap, NORMAL_SAMPLES)
                        + " ticks in the middle (" + normal + " is usual): full speed again");
                endEvent(now);
            } else if (now >= capUntil) {
                capLevel++;
                capUntil = now + CAP_STEP;
                sinceCapCount = 0;
                if (capLevel >= CAPS.length) {
                    capLevel = -1;
                    BuildLog.line("GUARD", "no trouble from the server for " + seconds(CAP_STEP) + ": full speed again");
                    endEvent(now);
                } else {
                    BuildLog.line("GUARD", "no trouble from the server for " + seconds(CAP_STEP) + ": at most " + cap() + " blocks a second now");
                }
            }
        }
        report(mc, now);
    }

    private static void endStall(long now, String why) {
        stalled = false;
        stalledFor += now - stallStart;
        eventSlowTicks = now - stallStart;
        limit(0, "the server didn't answer for " + seconds(now - stallStart));
        BuildLog.line("GUARD", why + " after " + seconds(now - stallStart) + ": at most " + cap()
                + " blocks a second until " + NORMAL_SAMPLES + " answers come as fast as before");
    }

    private static void limit(int level, String why) {
        capLevel = level;
        capUntil = AutoBuild.clientTicks + CAP_STEP;
        capWhy = why;
        sinceCapCount = 0;
        if (eventCapStart < 0) {
            eventCapStart = AutoBuild.clientTicks;
            eventPlacedWhileCapped = 0;
        }
    }

    private static void startEvent(long at, String trigger) {
        eventStart = at;
        eventTrigger = trigger;
        eventSlowTicks = 0;
        eventCapStart = -1;
        eventPlacedWhileCapped = 0;
        int sum = 0;
        for (int count : placedPerTick) {
            sum += count;
        }
        eventBaseline = sum / (RATE_TICKS / 20.0);
    }

    /** The trouble is over: what it cost, in blocks and seconds, against the speed just before it. */
    private static void endEvent(long now) {
        if (eventStart < 0) {
            return;
        }
        double capped = eventCapStart < 0 ? 0 : (now - eventCapStart) / 20.0;
        double slow = eventSlowTicks / 20.0;
        double lostBlocks = eventBaseline * slow + Math.max(0, eventBaseline * capped - eventPlacedWhileCapped);
        double lostSeconds = eventBaseline > 0 ? lostBlocks / eventBaseline : 0;
        events++;
        lostBlocksTotal += lostBlocks;
        lostSecondsTotal += lostSeconds;
        BuildLog.line("GUARD-END", String.format(Locale.ROOT,
                "trigger=\"%s\" serverSlow=%.1fs capped=%.1fs placedWhileCapped=%d speedBefore=%.1f/s lostBlocks=%.0f lostTime=%.1fs",
                eventTrigger, slow, capped, eventPlacedWhileCapped, eventBaseline, lostBlocks, lostSeconds));
        eventStart = -1;
        eventCapStart = -1;
    }

    private static int median(int[] values, int count) {
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[count / 2];
    }

    /** The value below which this share of the sorted values lie (nearest rank). */
    private static int percentile(int[] sorted, int count, double p) {
        if (count == 0) {
            return 0;
        }
        return sorted[Math.min(count - 1, Math.max(0, (int) Math.ceil(p * count) - 1))];
    }

    private static int pingMs(Minecraft mc) {
        return mc.getConnection() == null || mc.player == null || mc.getConnection().getPlayerInfo(mc.player.getUUID()) == null
                ? -1 : mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
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
                            .append(String.format(Locale.ROOT, "%.1f", e.getValue() / secs)));
            int[] sorted = Arrays.copyOf(windowAnswers, windowAnswerCount);
            Arrays.sort(sorted);
            String answers = windowAnswerCount == 0 ? "answers=0"
                    : String.format(Locale.ROOT, "answers=%d answerP50=%dt answerP95=%dt answerMax=%dt", windowAnswerCount,
                    percentile(sorted, windowAnswerCount, 0.5), percentile(sorted, windowAnswerCount, 0.95), sorted[windowAnswerCount - 1]);
            BuildLog.line("SERVER", String.format(Locale.ROOT,
                    "window=%.0fs placedPerSec=%.1f %s dropped=%d waiting=%d oldestWaiting=%s tps=%.1f ping=%dms packetsOutPerSec=%.0f (%s)%s%s",
                    secs, placed / secs, answers, droppedInWindow, ServerAcks.waitingCount(), seconds(ServerAcks.oldestWaiting()),
                    ServerLag.tps(), pingMs(mc), packetsTotal.get() / secs, kinds,
                    stalls > 0 ? " stalls=" + stalls + " stalledFor=" + seconds(stalledFor) : "",
                    describe() != null ? " guard=\"" + describe() + "\"" : ""));
        }
        packets.clear();
        packetsTotal.set(0);
        windowAnswerCount = 0;
        placed = 0;
        droppedInWindow = 0;
        stalls = 0;
        stalledFor = 0;
        windowStart = now;
    }

    private static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }
}
