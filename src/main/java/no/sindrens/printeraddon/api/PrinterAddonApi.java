package no.sindrens.printeraddon.api;

import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AreaBreaker;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.discord.DiscordLink;

/**
 * For other mods (AutoSell): whether a build runs, pausing it for a while and going on after, and a message to the
 * player's Discord control room. Called by name, so they work without Printer Addon too; kept stable.
 */
public final class PrinterAddonApi {
    private PrinterAddonApi() {
    }

    /** Auto build is running. */
    public static boolean isBuilding() {
        return AutoBuild.INSTANCE.isRunning();
    }

    /** Auto break is running. */
    public static boolean isBreaking() {
        return AreaBreaker.INSTANCE.isRunning();
    }

    /** In the middle of something that shouldn't be cut off: buying, a vault or chest, crafting, a manual task. */
    public static boolean isBusy() {
        return AutoBuild.INSTANCE.busyWithTrip();
    }

    /** Pauses the build (as a stop that goes on later); true if a build was running and is paused now. */
    public static boolean pauseFor(String why) {
        return AutoBuild.INSTANCE.pauseForOtherMod(Minecraft.getInstance(), why);
    }

    /** Goes on with a build paused by {@link #pauseFor}. */
    public static void resume() {
        AutoBuild.INSTANCE.resumeForOtherMod(Minecraft.getInstance());
    }

    /** An alert to the player's Discord control room (when linked). */
    public static void discordEvent(String type, String message) {
        DiscordLink.event(type, message);
    }

    /** Sends another mod's log file through the log channel (with the debug); false when it can't go. */
    public static boolean shareLog(String what, String why, String file, String summary, boolean automatic) {
        return no.sindrens.printeraddon.build.LogShare.shareOther(Minecraft.getInstance(), what, why, java.nio.file.Path.of(file), summary, automatic);
    }
}
