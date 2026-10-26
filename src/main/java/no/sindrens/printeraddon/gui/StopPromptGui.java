package no.sindrens.printeraddon.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import no.sindrens.printeraddon.build.LogShare;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Asked after every auto build or Auto break run with "Always send logs" off (see StopPrompt): may the run's log go to
 * the add-on's developer?
 * Send log, Always send logs (from now on without asking), Write what happened (the report screen, with the reason
 * filled in), or Not now.
 */
public class StopPromptGui extends GuiBase {
    private final String why;
    private final String what;

    public StopPromptGui(String why, String what) {
        this.why = why;
        this.what = what;
        this.setTitle(LogShare.isFinished(why) ? what + " finished" : what + " stopped");
    }

    @Override
    public void initGui() {
        super.initGui();
        int width = Math.min(this.width - 20, 380);
        int x = (this.width - width) / 2;
        boolean finished = LogShare.isFinished(why);
        List<String> lines = new ArrayList<>();
        if (finished) {
            lines.add("§aThe build is done.");
        } else if (LogShare.byPlayer(why)) {
            lines.add("You stopped it.");
        } else {
            lines.addAll(wrap("§eWhy: §r" + why, width));
        }
        lines.add("");
        lines.addAll(wrap(finished || LogShare.byPlayer(why) ? "Send this run's log to the add-on's developer? Every log helps make it faster and better."
                : "Send this run's log to the add-on's developer? It helps find and fix what stopped it.", width));
        int y = Math.max(28, this.height / 2 - 70 - lines.size() * 5);
        this.addLabel(x, y, width, lines.size() * 11, 0xFFFFFFFF, lines);
        y += lines.size() * 11 + 6;
        this.addLabel(x, y, width, 10, 0xFFAAAAAA, "The log and debug, no chat and no chest or vault spots.");
        y += 16;

        ButtonGeneric send = new ButtonGeneric(x, y, width, 20, "§a✉ Send log");
        this.addButton(send, (button, mouse) -> {
            GuiBase.openGui(null);
            LogShare.share(this.mc, false, null, why);
        });
        y += 24;
        ButtonGeneric always = new ButtonGeneric(x, y, width, 20, "§b↻ Always send logs",
                "From now on every run's log goes by itself, without asking.",
                "Turn it off in the settings: 'Always send logs'.");
        this.addButton(always, (button, mouse) -> {
            Configs.Generic.SHARE_LOGS.setBooleanValue(true);
            Configs.saveToFile();
            GuiBase.openGui(null);
            Chat.info("Every run's log now goes to the developer by itself (off: 'Always send logs' in the settings).");
            LogShare.share(this.mc, false, null, why);
        });
        y += 24;
        this.addButton(new ButtonGeneric(x, y, width, 20, "✎ Write what happened..."),
                (button, mouse) -> GuiBase.openGui(new ShareLogGui(null, why)));
        y += 24;
        this.addButton(new ButtonGeneric(x, y, width, 20, "Not now"), (button, mouse) -> GuiBase.openGui(null));
    }

    /** The text in lines that fit the width (words kept whole). */
    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.getStringWidth(next) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
