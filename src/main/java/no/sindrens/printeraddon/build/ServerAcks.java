package no.sindrens.printeraddon.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The server's answer to each of the printer's clicks. Every click goes out with a sequence number, and the server
 * acknowledges it once it has done the click (the blocks it changed arrive just before); until then the client only
 * shows its own guess. A spot that was clicked, and the spots next to it, wait exactly that long: not a fixed wait,
 * which was too short when the server lagged (the wrong blocks in the logs: an observer the server threw out, a
 * piston clicked against it a moment later landed in its spot) and too long when it doesn't.
 */
public final class ServerAcks {
    /** No answer after this long: not waited for any more. */
    private static final int TIMEOUT = 60;
    /** Clicks without any answer at all: the server doesn't send them (the fixed waits are used). */
    private static final int NO_ANSWER_AFTER = 30;

    private record Click(int sequence, long tick) {
    }

    private static final Map<BlockPos, Click> waiting = new HashMap<>();
    /** When the server answered the last click on each spot (kept a little while). */
    private static final Map<BlockPos, Long> answeredAt = new HashMap<>();
    @Nullable
    private static BlockPos lastTried;
    private static int sent;
    private static int answered;
    private static int minuteAnswers;
    private static long minuteTicks;
    private static long minuteMax;
    private static int dropped;
    /** How long the server took on the last click on each spot (ticks, kept like answeredAt). */
    private static final Map<BlockPos, Integer> answerTook = new HashMap<>();

    private ServerAcks() {
    }

    public static void reset() {
        waiting.clear();
        answeredAt.clear();
        answerTook.clear();
        dropped = 0;
        lastTried = null;
        sent = 0;
        answered = 0;
        minuteAnswers = 0;
        minuteTicks = 0;
        minuteMax = 0;
    }

    /** The printer picked this spot (its click follows). */
    public static void tried(BlockPos pos) {
        lastTried = pos.immutable();
    }

    /** The click for the last picked spot went out with this sequence number. */
    public static void clicked(int sequence) {
        if (lastTried == null) {
            return;
        }
        waiting.put(lastTried, new Click(sequence, AutoBuild.clientTicks));
        lastTried = null;
        sent++;
        if (waiting.size() > 256) {
            waiting.values().removeIf(c -> AutoBuild.clientTicks - c.tick() > TIMEOUT);
        }
    }

    /** The server has done every click up to this sequence number. */
    public static void acked(int sequence) {
        long now = AutoBuild.clientTicks;
        if (answeredAt.size() > 512) {
            answeredAt.values().removeIf(at -> now - at > TIMEOUT);
            answerTook.keySet().retainAll(answeredAt.keySet());
        }
        for (Iterator<Map.Entry<BlockPos, Click>> it = waiting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Click> entry = it.next();
            Click click = entry.getValue();
            if (click.sequence() <= sequence) {
                answeredAt.put(entry.getKey(), now);
                long took = now - click.tick();
                answerTook.put(entry.getKey(), (int) Math.min(Integer.MAX_VALUE, took));
                minuteAnswers++;
                minuteTicks += took;
                minuteMax = Math.max(minuteMax, took);
                answered++;
                ServerWatch.onAnswer(took);
                it.remove();
            }
        }
    }

    /** How long (ticks) the oldest click still without an answer has waited; 0 when none waits. */
    public static int oldestWaiting() {
        long oldest = 0;
        long now = AutoBuild.clientTicks;
        for (Click click : waiting.values()) {
            // Never answered at all (dropped by the server): not waited for after the timeout
            long age = now - click.tick();
            if (age < TIMEOUT * 5) {
                oldest = Math.max(oldest, age);
            }
        }
        return (int) oldest;
    }

    /** Clicks still without an answer. */
    public static int waitingCount() {
        return waiting.size();
    }

    /**
     * Forgets the clicks without an answer for this long (ticks): the server dropped them while it went on answering
     * the others. Returns their spots, oldest first.
     */
    public static java.util.List<BlockPos> dropOlderThan(int ticks) {
        long now = AutoBuild.clientTicks;
        java.util.List<Map.Entry<BlockPos, Click>> old = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Click> entry : waiting.entrySet()) {
            if (now - entry.getValue().tick() >= ticks) {
                old.add(entry);
            }
        }
        old.sort(java.util.Comparator.comparingLong(e -> e.getValue().tick()));
        java.util.List<BlockPos> spots = new java.util.ArrayList<>(old.size());
        for (Map.Entry<BlockPos, Click> entry : old) {
            waiting.remove(entry.getKey());
            spots.add(entry.getKey());
        }
        dropped += spots.size();
        return spots;
    }

    /** Clicks the server never answered (dropped), since the start. */
    public static int droppedCount() {
        return dropped;
    }

    /** How long (ticks) the server took to answer the last click on this spot, or -1 if unknown. */
    public static int answerTicks(BlockPos pos) {
        Integer took = answerTook.get(pos);
        return took == null ? -1 : took;
    }

    /** The server answers (or it's too early to tell). */
    public static boolean works() {
        return answered > 0 || sent < NO_ANSWER_AFTER;
    }

    /**
     * The block at this spot can't be trusted to be on the server yet: its click is still unanswered, or was answered
     * less than {@code ticks} ago (the block update and the answer can come in either order). Sand or gravel placed on
     * it would fall if it isn't there after all.
     */
    public static boolean unconfirmed(BlockPos pos, int ticks) {
        if (isWaiting(pos)) {
            return true;
        }
        Long at = answeredAt.get(pos);
        return at != null && AutoBuild.clientTicks - at < ticks;
    }

    /** This spot was clicked and the server hasn't answered yet. */
    public static boolean isWaiting(BlockPos pos) {
        Click click = waiting.get(pos);
        return click != null && AutoBuild.clientTicks - click.tick() < TIMEOUT;
    }

    /** A spot next to this one was clicked and the server hasn't answered yet (it may not have that block). */
    public static boolean nextToWaiting(BlockPos pos) {
        if (waiting.isEmpty()) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            if (isWaiting(pos.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    /** "answers in 2.4 ticks on average, 9 at most" for the last minute (then counts again). */
    public static String minute() {
        String text = minuteAnswers == 0 ? (sent > 0 && answered == 0 ? "no answers to clicks (fixed waits used)" : "-")
                : String.format("server answers clicks in %.1f ticks on average, %d at most", minuteTicks / (double) minuteAnswers, minuteMax);
        minuteAnswers = 0;
        minuteTicks = 0;
        minuteMax = 0;
        return text;
    }
}
