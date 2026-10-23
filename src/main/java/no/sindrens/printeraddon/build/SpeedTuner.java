package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds the fastest printer interval that still places correctly. It watches every block that appears near the
 * player and checks it against the schematic: after enough correct blocks in a row the interval goes one tick down,
 * after a wrong one it goes two ticks up again (and stays there for a while).
 */
public class SpeedTuner {
    private static final int CHECK_EVERY = 10;
    private static final int RADIUS = 6;
    /** A block is judged this long after it appeared (the printer may still fix it, e.g. a slab or a comparator). */
    private static final int JUDGE_AFTER = 20;
    /** No speeding up for this long after a mistake. */
    private static final int CALM_AFTER_MISTAKE = 20 * 60;

    private final Map<BlockPos, BlockState> seen = new HashMap<>();
    private final Map<BlockPos, Long> appeared = new HashMap<>();
    private int interval = -1;
    private int goodInARow;
    private int good;
    private int bad;
    private long tick;
    private long calmUntil;

    public void reset() {
        seen.clear();
        appeared.clear();
        interval = -1;
        goodInARow = 0;
        good = 0;
        bad = 0;
        calmUntil = 0;
    }

    /** The interval to use now (the starting one until it's tuned). */
    public int interval() {
        int start = Configs.Speed.printerInterval();
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue()) {
            return start;
        }
        if (interval < 0) {
            interval = start;
        }
        return interval;
    }

    public String describe() {
        return Configs.Speed.AUTO_SPEED.getBooleanValue()
                ? "interval " + interval() + " (auto: " + good + " right, " + bad + " wrong)"
                : "interval " + interval();
    }

    public void tick(Minecraft mc) {
        if (!Configs.Speed.AUTO_SPEED.getBooleanValue() || mc.player == null || mc.level == null || ++tick % CHECK_EVERY != 0) {
            return;
        }
        WorldSchematic schematic = SchematicHelper.world();
        if (schematic == null) {
            return;
        }

        BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -RADIUS, -RADIUS), center.offset(RADIUS, RADIUS, RADIUS))) {
            if (schematic.getBlockState(pos).isAir()) {
                continue;
            }
            BlockState now = mc.level.getBlockState(pos);
            BlockState before = seen.put(pos.immutable(), now);
            if (before != null && (before.isAir() || before.canBeReplaced()) && !now.isAir() && !now.canBeReplaced()) {
                appeared.put(pos.immutable(), tick);
            }
        }

        appeared.entrySet().removeIf(entry -> {
            if (tick - entry.getValue() < JUDGE_AFTER) {
                return false;
            }
            judge(mc, schematic, entry.getKey());
            return true;
        });
        if (seen.size() > 20000) {
            seen.clear();
        }
    }

    private void judge(Minecraft mc, WorldSchematic schematic, BlockPos pos) {
        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
        switch (result) {
            case OK, BUCKET, PRINTER_FIXES -> {
                good++;
                if (++goodInARow >= Configs.Speed.AUTO_SPEED_STEP_BLOCKS.getIntegerValue() && tick > calmUntil) {
                    goodInARow = 0;
                    int min = Configs.Speed.AUTO_SPEED_MIN_INTERVAL.getIntegerValue();
                    if (interval() > min) {
                        interval--;
                        Chat.info("Speed: printer interval " + interval + " (the last "
                                + Configs.Speed.AUTO_SPEED_STEP_BLOCKS.getIntegerValue() + " blocks were all right)");
                    }
                }
            }
            case WRONG_BLOCK, WRONG_STATE -> {
                bad++;
                goodInARow = 0;
                calmUntil = tick + CALM_AFTER_MISTAKE;
                int max = Configs.Speed.printerInterval();
                int slower = Math.min(max, interval() + 2);
                PrinterAddon.debug("Wrong block at {} at interval {}: {}", pos.toShortString(), interval(), mc.level.getBlockState(pos));
                if (slower != interval) {
                    interval = slower;
                    Chat.warn("Speed: printer interval " + interval + " (a block came out wrong at " + pos.toShortString() + ")");
                }
            }
            default -> {
            }
        }
    }
}
