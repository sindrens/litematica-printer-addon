package no.sindrens.printeraddon.build;

import java.util.Locale;

/** One line on what to do about why a run stopped, for chat and Discord ("" when there's nothing to do). */
public final class Advice {
    private Advice() {
    }

    public static String forStop(String why) {
        if (why == null) {
            return "";
        }
        String w = why.toLowerCase(Locale.ROOT);
        if (w.contains("spending limit") || w.contains("money to keep") || w.contains("money")) {
            return "Earn or bring money, or raise the limit (J+B when starting, or /schedule on Discord), then start again.";
        }
        if (w.startsWith("missing") || w.contains("not in the shop") || w.contains("out of")) {
            return "Put the items in your inventory or a vault; the build goes on by itself, or start it again.";
        }
        if (w.contains("vault") && (w.contains("full") || w.contains("empty"))) {
            return "Empty a vault or link a chest (look at it, J+B, 'Link the chest'), then start again.";
        }
        if (w.contains("nearby")) {
            return "Someone came close. Add friends to 'Players who don't stop it' (J+B settings), then start again.";
        }
        if (w.contains("damage") || w.contains("hurt")) {
            return "Check what hurt you in the build log (SAFETY lines); start again when it's safe.";
        }
        if (w.contains("nothing could") || w.contains("stuck")) {
            return "Look at what's left near you: a block in the way, or a part not loaded. Start again; it tries once more.";
        }
        if (w.contains("lag") || w.contains("timed out") || w.contains("kicked")) {
            return "The server had trouble; it rejoins and goes on by itself if 'Rejoin' is on.";
        }
        if (w.contains("schedule") || w.contains("paused") || w.contains("finished") || w.contains("by you")) {
            return "";
        }
        if (w.contains("lava")) {
            return "Get out of the lava and check the build for lava nearby before starting again.";
        }
        if (w.contains("falling") || w.contains("void")) {
            return "Something gave way under you. Check where you were (build log SAFETY line) before starting again.";
        }
        if (w.contains("health is low")) {
            return "Eat and heal first (turn on 'Eat when hungry'), then start again.";
        }
        if (w.contains("inventory has been full")) {
            return "Link a chest (J+B), set your vaults, or make room, then start again.";
        }
        if (w.contains("not allowed on this server") || w.contains("doesn't allow")) {
            return "The server switched Printer Addon off. Ask the server's staff; don't try to get around it.";
        }
        if (w.contains("switched off")) {
            return "Switch the add-on on again (J+A).";
        }
        return "Send the log (it goes by itself with 'Always send logs') and start again.";
    }

    /** "why. What to do." */
    public static String withAdvice(String why) {
        String advice = forStop(why);
        return advice.isEmpty() ? why : why + ". " + advice;
    }
}
