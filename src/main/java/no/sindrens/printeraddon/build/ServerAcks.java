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
    @Nullable
    private static BlockPos lastTried;
    private static int sent;
    private static int answered;
    private static int minuteAnswers;
    private static long minuteTicks;
    private static long minuteMax;

    private ServerAcks() {
    }

    public static void reset() {
        waiting.clear();
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
        for (Iterator<Click> it = waiting.values().iterator(); it.hasNext(); ) {
            Click click = it.next();
            if (click.sequence() <= sequence) {
                long took = now - click.tick();
                minuteAnswers++;
                minuteTicks += took;
                minuteMax = Math.max(minuteMax, took);
                answered++;
                it.remove();
            }
        }
    }

    /** The server answers (or it's too early to tell). */
    public static boolean works() {
        return answered > 0 || sent < NO_ANSWER_AFTER;
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
