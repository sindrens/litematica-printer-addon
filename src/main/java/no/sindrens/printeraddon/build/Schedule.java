package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * "Build until 07:00" and "keep at least $500k": set in the settings or from Discord (/schedule), checked every second
 * while auto build or Auto break runs. The stop time is used once, then cleared.
 */
public final class Schedule {
    private static String parsedFrom = "";
    @Nullable
    private static LocalDateTime stopAt;
    private static long lastCheck;

    private Schedule() {
    }

    /** The next time the build stops, or null (also when the text isn't a time). */
    @Nullable
    public static LocalDateTime stopTime() {
        String text = Configs.Generic.STOP_AT.getStringValue().trim();
        if (!text.equals(parsedFrom)) {
            parsedFrom = text;
            stopAt = null;
            if (!text.isEmpty()) {
                try {
                    LocalTime time = LocalTime.parse(text.length() == 4 ? "0" + text : text);
                    LocalDateTime at = LocalDateTime.now().with(time).withSecond(0).withNano(0);
                    stopAt = at.isAfter(LocalDateTime.now()) ? at : at.plusDays(1);
                    BuildLog.line("SCHEDULE", "stops at " + stopAt);
                } catch (DateTimeParseException e) {
                    Chat.warn("Stop time \"" + text + "\" isn't a time: write it like 07:00.");
                }
            }
        }
        return stopAt;
    }

    /** Money the build may still spend before going below "Keep at least this much money", or null for no limit. */
    @Nullable
    public static Double moneyAboveKeep(Minecraft mc) {
        int keep = Configs.Shop.KEEP_MONEY.getIntegerValue();
        if (keep <= 0) {
            return null;
        }
        Double balance = CostEstimate.balance(mc);
        return balance == null ? null : Math.max(0, balance - keep);
    }

    /** "stops at 07:00 · keeps $500,000" for the status and Discord, or "" when nothing is set. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        LocalDateTime at = stopTime();
        if (at != null) {
            sb.append("stops at ").append(String.format(java.util.Locale.ROOT, "%02d:%02d", at.getHour(), at.getMinute()));
        }
        int keep = Configs.Shop.KEEP_MONEY.getIntegerValue();
        if (keep > 0) {
            sb.append(sb.isEmpty() ? "" : " · ").append(String.format(java.util.Locale.ROOT, "keeps $%,d", keep));
        }
        return sb.toString();
    }

    /** Once a second: the stop time reached stops the build (after covering open cactus) or Auto break. */
    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000) {
            return;
        }
        lastCheck = now;
        LocalDateTime at = stopTime();
        if (at == null || LocalDateTime.now().isBefore(at)) {
            return;
        }
        boolean building = AutoBuild.INSTANCE.isRunning();
        boolean breaking = AreaBreaker.INSTANCE.isRunning();
        String time = String.format(java.util.Locale.ROOT, "%02d:%02d", at.getHour(), at.getMinute());
        Configs.Generic.STOP_AT.setValueFromString("");
        Configs.saveToFile();
        if (!building && !breaking) {
            return;
        }
        BuildLog.line("SCHEDULE", "stop time " + time + " reached");
        no.sindrens.printeraddon.discord.DiscordLink.event("schedule", "Stopped on schedule at " + time + ". "
                + (building ? AutoBuild.INSTANCE.stats.total() : "Auto break stopped."));
        Chat.info("Stop time " + time + " reached: stopping.");
        if (breaking) {
            AreaBreaker.INSTANCE.stop(mc, "stopped on schedule at " + time);
        }
        if (building) {
            AutoBuild.INSTANCE.stop(mc, null);
        }
    }

    /**
     * From Discord: "stop_at=07:00;keep_money=500000" (either part; "clear" for both). Returns what's set now, in words.
     */
    public static String apply(String spec) {
        for (String part : spec.split(";")) {
            String p = part.trim();
            if (p.equals("clear")) {
                Configs.Generic.STOP_AT.setValueFromString("");
                Configs.Shop.KEEP_MONEY.setIntegerValue(0);
            } else if (p.startsWith("stop_at=")) {
                Configs.Generic.STOP_AT.setValueFromString(p.substring(8).trim());
            } else if (p.startsWith("keep_money=")) {
                try {
                    Configs.Shop.KEEP_MONEY.setIntegerValue((int) Math.min(Integer.MAX_VALUE, Long.parseLong(p.substring(11).trim())));
                } catch (NumberFormatException ignored) {
                    // left as it was
                }
            }
        }
        Configs.saveToFile();
        String now = describe();
        return now.isEmpty() ? "no stop time, no money limit" : now;
    }
}
