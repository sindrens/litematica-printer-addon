package no.sindrens.printeraddon.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.gui.screens.Screen;
import no.sindrens.printeraddon.build.DebugInfo;
import no.sindrens.printeraddon.build.LogShare;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Report / share log" (J+B): before the log goes to the add-on's developer, the player says what went wrong, in
 * their own words and with the buttons that fit, and what they were building. The full debug goes with it (see
 * LogShare and DebugInfo), and their screenshot when they took one (F2) just before. What's written is kept until it's
 * sent, also when the screen is closed or sending fails.
 */
public class ShareLogGui extends GuiBase {
    /** The buttons to click and what each means (its words go into the report). */
    private static final String[][] PROBLEMS = {
            {"Skips blocks", "Leaves holes, skips blocks or rows,", "or calls a layer done with blocks left"},
            {"Wrong blocks", "Places the wrong block, or turned the wrong way"},
            {"Too slow", "Places slowly, waits a lot, or flies around too much"},
            {"Gets stuck", "Stops, waits forever, or goes back and forth"},
            {"Flying / falling", "Flies oddly, falls, or gets kicked"},
            {"Items / hotbar", "The wrong item in hand, runs out of items, a messy hotbar"},
            {"Shop / buying", "Buys the wrong thing or too much, stuck in the shop"},
            {"Vaults / chests", "Taking from or putting into the vaults and linked chests"},
            {"Tilling / seeds", "Dirt not tilled, seeds or stems missing"},
            {"Auto break", "Breaking an area"},
            {"Error / crash", "An error in chat, or the game crashed"},
            {"Something else", "Anything else: write it in the box"}};
    private static final int COLUMNS = 4;

    private static String problem = "";
    /** Null: filled in from what's placed (see DebugInfo.describeBuild). */
    @Nullable
    private static String building;
    private static final Set<String> picked = new HashSet<>();
    private static boolean withScreenshot = true;

    @Nullable
    private final DebugInfo.Recent screenshot;
    @Nullable
    private final DebugInfo.Recent crashReport;
    @Nullable
    private GuiTextFieldMultiLine problemField;
    @Nullable
    private GuiTextFieldGeneric buildingField;

    public ShareLogGui(@Nullable Screen parent) {
        this.setParent(parent);
        this.setTitle("Report a problem / share the log");
        this.screenshot = DebugInfo.recentScreenshot(this.mc);
        this.crashReport = DebugInfo.recentCrashReport(this.mc);
        if (building == null) {
            building = DebugInfo.describeBuild();
        }
    }

    /** After a report went out: the next one starts empty. */
    public static void clearDraft() {
        problem = "";
        building = null;
        picked.clear();
        withScreenshot = true;
    }

    @Override
    public void initGui() {
        // Also on a window resize, which builds the screen again
        keepTyped();
        super.initGui();
        int x = 10;
        int width = Math.min(this.width - 20, 480);
        int y = 24;

        this.addLabel(x, y, width, 10, 0xFFFFFFFF, "What went wrong? In your own words (Enter: a new line)");
        y += 11;
        int boxHeight = this.height >= 320 ? 64 : 40;
        GuiTextFieldMultiLine box = new GuiTextFieldMultiLine.Builder().setX(x).setY(y).setWidth(width).setHeight(boxHeight)
                .setShadow(false).build(this.font, "What went wrong");
        box.setCharacterLimit(LogShare.MAX_PROBLEM);
        box.setValue(problem);
        box.setFocused(true);
        this.addTextFieldMultiLine(box, 50, field -> {
            problem = field.getValue();
            return true;
        });
        problemField = box;
        y += boxHeight + 4;

        this.addLabel(x, y, width, 10, 0xFFFFFFFF, "Click what fits (one or more):");
        y += 11;
        int buttonWidth = (width - (COLUMNS - 1) * 3) / COLUMNS;
        for (int i = 0; i < PROBLEMS.length; i++) {
            String[] entry = PROBLEMS[i];
            String name = entry[0];
            String[] help = new String[entry.length - 1];
            System.arraycopy(entry, 1, help, 0, help.length);
            ButtonGeneric button = new ButtonGeneric(x + (i % COLUMNS) * (buttonWidth + 3), y + (i / COLUMNS) * 22, buttonWidth, 20,
                    label(name), help);
            this.addButton(button, (clicked, mouse) -> {
                if (!picked.remove(name)) {
                    picked.add(name);
                }
                clicked.setDisplayString(label(name));
            });
        }
        y += (PROBLEMS.length + COLUMNS - 1) / COLUMNS * 22 + 2;

        String buildLabel = "What were you building?";
        int labelWidth = this.getStringWidth(buildLabel) + 6;
        this.addLabel(x, y + 6, labelWidth, 10, 0xFFFFFFFF, buildLabel);
        GuiTextFieldGeneric line = new GuiTextFieldGeneric(x + labelWidth, y, width - labelWidth, 20, this.font);
        line.setMaxLengthWrapper(LogShare.MAX_BUILDING);
        line.setValueWrapper(building == null ? "" : building);
        this.addTextField(line, field -> {
            building = field.getValueWrapper();
            return true;
        });
        buildingField = line;
        y += 24;

        if (screenshot != null) {
            ButtonGeneric shot = new ButtonGeneric(x, y, Math.min(width, 260), 20, screenshotLabel(),
                    "Your newest screenshot (F2), taken " + screenshot.ago(), "It shows the developer what it looked like");
            this.addButton(shot, (clicked, mouse) -> {
                withScreenshot = !withScreenshot;
                clicked.setDisplayString(screenshotLabel());
            });
        } else {
            this.addLabel(x, y + 6, width, 10, 0xFFAAAAAA, "To add a screenshot: close this, press F2 where it went wrong, then come back");
        }
        y += 24;

        this.addLabel(x, y, width, 10, 0xFFAAAAAA, "Goes with it: the run's log, the game log (no chat), your settings, mods, PC"
                + (crashReport != null ? ", the crash report (" + crashReport.ago() + ")" : ""));
        y += 13;

        ButtonGeneric send = new ButtonGeneric(x, y, 200, 20, LogShare.isSending() ? "Sending the last one..." : "§a✉ Send to the developer");
        send.setEnabled(!LogShare.isSending());
        this.addButton(send, (clicked, mouse) -> send());
        this.addButton(new ButtonGeneric(x + 206, y, 90, 20, "Cancel"), (clicked, mouse) -> this.closeGui(true));
    }

    private void send() {
        keepTyped();
        // The picked ones in the order shown
        List<String> chosen = new ArrayList<>();
        for (String[] entry : PROBLEMS) {
            if (picked.contains(entry[0])) {
                chosen.add(entry[0]);
            }
        }
        LogShare.Report report = new LogShare.Report(problem.trim(), chosen, building == null ? "" : building.trim(),
                withScreenshot && screenshot != null ? screenshot.file() : null);
        GuiBase.openGui(null);
        LogShare.share(this.mc, false, report);
    }

    private void keepTyped() {
        if (problemField != null) {
            problem = problemField.getValue();
        }
        if (buildingField != null) {
            building = buildingField.getValueWrapper();
        }
    }

    private static String label(String name) {
        return picked.contains(name) ? "§a✔ " + name : name;
    }

    private String screenshotLabel() {
        return (withScreenshot ? "§a✔§r Add" : "§7✖ Don't add") + " my screenshot ("
                + (screenshot == null ? "-" : screenshot.ago()) + ")";
    }
}
