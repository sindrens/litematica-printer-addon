package no.sindrens.printeraddon.gui;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.BuildStats;
import no.sindrens.printeraddon.config.Configs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A small box at the top left during auto build: layer, blocks left, speed, time left, what it's doing, what's missing. */
public final class BuildHud {
    private static final int BACKGROUND = 0x90000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GREY = 0xFFAAAAAA;
    private static final int WARN = 0xFFFFCC55;

    private BuildHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(PrinterAddon.MOD_ID, "build_status"), BuildHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        AutoBuild build = AutoBuild.INSTANCE;
        if (!Configs.Generic.SHOW_HUD.getBooleanValue() || !build.isRunning() || mc.player == null) {
            return;
        }

        long started = no.sindrens.printeraddon.build.Perf.start();
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        add(lines, colors, "Printer Addon - " + build.layers.status(), TEXT);

        int left = build.blocksLeft();
        double rate = build.stats.recentRate();
        String eta = rate > 0.5 ? BuildStats.time((long) (left / rate * 60_000)) : "-";
        add(lines, colors, String.format("%,d blocks left in the build, about %s to go", left, eta), TEXT);
        add(lines, colors, String.format("%.0f blocks/min, %s", rate, build.speed.describe()), GREY);
        int busy = build.stats.printerBusyPercent();
        add(lines, colors, "Printer busy " + busy + "%" + (busy < 80 ? " (mostly idle because: " + build.stats.printerIdleReason() + ")" : ""), GREY);
        add(lines, colors, "Doing: " + build.doing(), GREY);

        Map<Item, Integer> missing = build.shoppingList;
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("Missing: ");
            int shown = 0;
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                if (shown++ == 3) {
                    sb.append("...");
                    break;
                }
                sb.append(entry.getValue()).append(' ').append(new ItemStack(entry.getKey()).getHoverName().getString()).append(", ");
            }
            add(lines, colors, sb.toString().replaceAll(", $", ""), WARN);
        }

        Font font = mc.font;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int x = 4;
        int y = 4;
        int lineHeight = font.lineHeight + 2;
        graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * lineHeight, BACKGROUND);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x, y + i * lineHeight, colors.get(i));
        }
        no.sindrens.printeraddon.build.Perf.end("on-screen display", started);
    }

    private static void add(List<String> lines, List<Integer> colors, String text, int color) {
        lines.add(text);
        colors.add(color);
    }
}
