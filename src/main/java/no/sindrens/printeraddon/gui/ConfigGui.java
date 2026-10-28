package no.sindrens.printeraddon.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.Hotkeys;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.phys.BlockHitResult;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.ChestLinks;
import java.util.Set;

import java.util.List;
import java.util.Objects;

public class ConfigGui extends GuiConfigsBase {
    /** Opens on the important settings (the ones a player sets once); the build menu (J+B) opens on Build. */
    private static Tab tab = Tab.IMPORTANT;

    /** J+B: the settings opened on the Build tab (start/stop, linked chests, quick actions). */
    public static ConfigGui buildMenu() {
        tab = Tab.BUILD;
        return new ConfigGui();
    }

    public ConfigGui() {
        super(10, 50, PrinterAddon.MOD_ID, null, "Printer Addon");
        this.setListPosition(10, listY(tab));
    }

    /** Where the list of settings starts: below the buttons on the Controls tab. */
    private static int listY(Tab tab) {
        return tab == Tab.CONTROLS ? CONTROLS_LIST_Y : 50;
    }

    /** The first one's key, else the second's (start / stop show the start/stop key while they have none of their own). */
    private static String keyOr(fi.dy.masa.malilib.config.options.ConfigHotkey first, fi.dy.masa.malilib.config.options.ConfigHotkey second) {
        String own = key(first);
        return own.isEmpty() ? key(second) : own;
    }

    private static final int CONTROLS_LIST_Y = 52 + 24 * 4 + 22;

    /** J+key text for a button, empty when no key is set. */
    private static String key(fi.dy.masa.malilib.config.options.ConfigHotkey hotkey) {
        String keys = hotkey.getKeybind().getKeysDisplayString();
        return keys == null || keys.isBlank() ? "" : " \u00a77(" + keys + ")";
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;

        for (Tab t : Tab.values()) {
            ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, t.displayName);
            button.setEnabled(tab != t);
            this.addButton(button, new TabListener(t, this));
            x += button.getWidth() + 2;
        }

        if (tab == Tab.CONTROLS) {
            addControlsTab(10, 52);
        } else if (tab == Tab.BUILD) {
            addBuildTab(10, 52);
        } else if (tab == Tab.BREAK_AREA) {
            addBreakTab(10, 52);
        } else if (tab == Tab.DISCORD) {
            addDiscordTab(10, 52);
        } else if (tab == Tab.COMMANDS) {
            String server = no.sindrens.printeraddon.config.ServerProfiles.current();
            this.addLabel(this.width - 330, 30, 320, 10, 0xFFAAAAAA, server.isEmpty()
                    ? "Kept per server: join a server to set its commands"
                    : "Kept for " + server + " only (" + no.sindrens.printeraddon.ServerPolicy.describe() + ")");
        } else if (tab != Tab.HOTKEYS) {
            // Where the search is (MaLiLib's: the magnifier at the top left of the list)
            this.addLabel(this.width - 230, 30, 220, 10, 0xFFAAAAAA, tab == Tab.ALL
                    ? "Search every setting: the magnifier at the top left"
                    : "Hover a setting for what it does. Search: 'All settings'");
        }
    }

    /**
     * Controls: start, stop, pause, stop after this layer, stop everything and the layer up / down, as buttons, with
     * their keys listed below to change right here.
     */
    private void addControlsTab(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        AutoBuild build = AutoBuild.INSTANCE;
        boolean running = build.isRunning();
        boolean paused = build.isRemotelyPaused();
        if (!Configs.enabled()) {
            this.addLabel(x, y + 5, 400, 10, 0xFFFF5555, "The add-on is off: switch it on in the Build tab (or J+A).");
            return;
        }
        int w = 180;
        ButtonGeneric start = new ButtonGeneric(x, y, w, 20, (paused ? "\u00a7a\u25b6 Go on" : "\u00a7a\u25b6 Start") + keyOr(Hotkeys.START_AUTO_BUILD, Hotkeys.TOGGLE_AUTO_BUILD));
        start.setEnabled(!running);
        this.addButton(start, (button, mouse) -> {
            GuiBase.openGui(null);
            build.startOnly(mc);
        });
        ButtonGeneric stop = new ButtonGeneric(x + w + 4, y, w, 20, "\u00a7c\u25a0 Stop" + keyOr(Hotkeys.STOP_AUTO_BUILD, Hotkeys.TOGGLE_AUTO_BUILD));
        stop.setEnabled(running || paused);
        this.addButton(stop, (button, mouse) -> {
            build.stopOnly(mc);
            this.initGui();
        });
        ButtonGeneric pause = new ButtonGeneric(x + (w + 4) * 2, y, w, 20, (paused ? "\u25b6 Go on" : "\u23f8 Pause") + key(Hotkeys.PAUSE_AUTO_BUILD));
        pause.setEnabled(running || paused);
        this.addButton(pause, (button, mouse) -> {
            build.togglePause(mc);
            this.initGui();
        });
        y += 24;
        boolean afterLayer = build.isStopAfterLayer();
        ButtonGeneric layerStop = new ButtonGeneric(x, y, w * 2 + 4, 20, "Stop after this layer: "
                + (afterLayer ? "\u00a7aON" : "\u00a7cOFF") + key(Hotkeys.STOP_AFTER_LAYER));
        layerStop.setEnabled(running || paused);
        this.addButton(layerStop, (button, mouse) -> {
            build.toggleStopAfterLayer(mc);
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + (w + 4) * 2, y, w, 20, "\u00a7cStop everything" + key(Hotkeys.STOP_ALL)), (button, mouse) -> {
            no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.cancel("stopped");
            no.sindrens.printeraddon.shop.ShopBuyer.INSTANCE.cancel("stopped");
            build.stopEverything(mc);
            build.cancelPutAway();
            no.sindrens.printeraddon.build.Resume.cancel();
            no.sindrens.printeraddon.build.Reconnect.cancel();
            this.initGui();
        });
        y += 24;
        this.addButton(new ButtonGeneric(x, y, w, 20, "\u25bc Previous layer" + key(Hotkeys.PREVIOUS_LAYER)), (button, mouse) -> {
            build.layers.moveLayer(-1);
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + w + 4, y, w, 20, "\u25b2 Next layer" + key(Hotkeys.NEXT_LAYER)), (button, mouse) -> {
            build.layers.moveLayer(1);
            this.initGui();
        });
        y += 28;
        String state = running ? "\u00a7aRunning\u00a7r: " + build.layers.status() + (afterLayer ? " \u00a7e(stops when this layer is done)" : "")
                : paused ? "\u00a7ePaused\u00a7r" + (afterLayer ? " \u00a7e(stops after the layer when it goes on)" : "")
                : "\u00a77Not running";
        this.addLabel(x, y, 600, 10, 0xFFFFFFFF, state);
        y += 14;
        this.addLabel(x, y, 600, 10, 0xFFAAAAAA, "Keys: click a key to change it, the arrow resets it. Keys left empty do nothing.");
    }

    private void addBuildTab(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        boolean running = AutoBuild.INSTANCE.isRunning();
        // The add-on itself on or off: off, Litematica Printer works exactly as without it
        boolean on = Configs.enabled();
        this.addButton(new ButtonGeneric(x, y, 200, 20, on ? "Add-on: \u00a7aON\u00a7r (click: plain printer)" : "Add-on: \u00a7cOFF\u00a7r (click to switch on)"),
                (button, mouse) -> {
                    AutoBuild.INSTANCE.setAddonEnabled(mc, !Configs.enabled());
                    this.initGui();
                });
        this.addLabel(x + 210, y + 5, 300, 10, 0xFFAAAAAA, on ? "Also J+A" : "Litematica Printer works on its own now");
        y += 30;
        if (!on) {
            return;
        }
        // Start in green, stop in red: the main buttons, seen at once
        this.addButton(new ButtonGeneric(x, y, 200, 20, running ? "\u00a7c\u25a0 STOP auto build" : "\u00a7a\u25b6 START auto build"),
                (button, mouse) -> {
                    GuiBase.openGui(null);
                    AutoBuild.INSTANCE.toggle(mc);
                });
        if (!running) {
            // The building items into the vaults now (also done by itself when auto build stops)
            ButtonGeneric putAway = new ButtonGeneric(x + 210, y, 170, 20, "Put building items away");
            putAway.setEnabled(!AutoBuild.INSTANCE.isPuttingAway());
            this.addButton(putAway, (button, mouse) -> {
                GuiBase.openGui(null);
                AutoBuild.INSTANCE.putAway(mc, true);
            });
        }
        // What went wrong and the run's log to the add-on's developer (see ShareLogGui); every run's log goes by itself
        // with "Always send logs"
        this.addButton(new ButtonGeneric(x + 390, y, 130, 20, "Report / share log"), (button, mouse) -> GuiBase.openGui(new ShareLogGui(this)));
        y += 30;

        // The chest the crosshair was on when the menu opened
        BlockPos looked = mc.hitResult instanceof BlockHitResult hit && mc.level != null
                && mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container ? hit.getBlockPos() : null;
        Set<BlockPos> linked = mc.player == null ? Set.of() : ChestLinks.list(mc);
        this.addLabel(x, y, 300, 10, 0xFFFFFFFF, "Linked chests: " + linked.size()
                + " (only these are used for materials, before buying)");
        y += 14;
        if (looked != null) {
            boolean isLinked = linked.contains(looked);
            this.addButton(new ButtonGeneric(x, y, 200, 20, (isLinked ? "Unlink" : "Link") + " the chest at " + looked.toShortString()),
                    (button, mouse) -> {
                        boolean now = ChestLinks.toggle(mc, looked);
                        no.sindrens.printeraddon.util.Chat.info((now ? "Linked" : "Unlinked") + " the chest at " + looked.toShortString() + ".");
                        this.initGui();
                    });
        } else {
            this.addLabel(x, y + 5, 300, 10, 0xFFAAAAAA, "To link a chest: look at it, then open this menu (J+B).");
        }
        y += 24;
        ButtonGeneric clear = new ButtonGeneric(x, y, 200, 20, "Unlink all chests");
        clear.setEnabled(!linked.isEmpty());
        this.addButton(clear, (button, mouse) -> {
            ChestLinks.clear(mc);
            this.initGui();
        });
        y += 30;

        this.addButton(new ButtonGeneric(x, y, 98, 20, "Build report"), (button, mouse) -> {
            GuiBase.openGui(null);
            no.sindrens.printeraddon.build.BuildReport.show(mc);
        });
        this.addButton(new ButtonGeneric(x + 102, y, 98, 20, "Cost"), (button, mouse) -> {
            GuiBase.openGui(null);
            no.sindrens.printeraddon.build.CostEstimate.show(mc);
        });
        y += 24;
        this.addButton(new ButtonGeneric(x, y, 200, 20, "Scan the shop"), (button, mouse) -> {
            GuiBase.openGui(null);
            AutoBuild.INSTANCE.stopEverything(mc);
            no.sindrens.printeraddon.shop.ShopScanner.INSTANCE.start(mc);
        });
        y += 24;
        // The full log of each build (every step, every flight, a snapshot every 5 s)
        this.addLabel(x + 210, y + 5, 320, 10, 0xFFAAAAAA, "Settings to look at: the Important tab");
        this.addButton(new ButtonGeneric(x, y, 200, 20, "Open build logs folder"), (button, mouse) -> {
            java.nio.file.Path folder = no.sindrens.printeraddon.build.BuildLog.folder();
            try {
                java.nio.file.Files.createDirectories(folder);
            } catch (java.io.IOException ignored) {
            }
            net.minecraft.util.Util.getPlatform().openPath(folder);
        });
    }

    /** Discord: link status, link / unlink, sync on/off, the schematic library and messages from the developer. */
    private void addDiscordTab(int x, int y) {
        boolean linked = no.sindrens.printeraddon.discord.DiscordLink.isLinked();
        boolean sync = Configs.Generic.DISCORD_SYNC.getBooleanValue();
        this.addLabel(x, y, 500, 10, 0xFFFFFFFF, linked
                ? "Linked with Discord as \u00a7b" + no.sindrens.printeraddon.discord.DiscordLink.linkedTo()
                + "\u00a7r: your control room shows progress and lets you pause or stop from your phone."
                : "Not linked. On the Printer Addon Discord type /link, then click Link here and type the code.");
        y += 16;
        this.addButton(new ButtonGeneric(x, y, 200, 20, linked ? "Link again (new code)" : "\u00a7aLink with Discord"), (button, mouse) -> {
            GuiBase.openGui(null);
            GuiBase.openGui(new net.minecraft.client.gui.screens.ChatScreen("/printeraddon link PRINT-", false));
        });
        if (linked) {
            this.addButton(new ButtonGeneric(x + 204, y, 96, 20, "Unlink"), (button, mouse) -> {
                no.sindrens.printeraddon.discord.DiscordLink.unlink();
                this.initGui();
            });
        }
        y += 24;
        this.addButton(new ButtonGeneric(x, y, 200, 20, "Discord sync: " + (sync ? "\u00a7aON" : "\u00a7cOFF")), (button, mouse) -> {
            Configs.Generic.DISCORD_SYNC.setBooleanValue(!Configs.Generic.DISCORD_SYNC.getBooleanValue());
            this.initGui();
        });
        this.addLabel(x + 210, y + 5, 320, 10, 0xFFAAAAAA, "Never sends chat, coordinates, chests or vaults");
        y += 24;
        this.addButton(new ButtonGeneric(x, y, 200, 20, "Schematic library"), (button, mouse) -> {
            GuiBase.openGui(null);
            no.sindrens.printeraddon.discord.DiscordLink.schematics("");
        });
        this.addButton(new ButtonGeneric(x + 204, y, 96, 20, "Suggestions"), (button, mouse) -> {
            GuiBase.openGui(null);
            no.sindrens.printeraddon.discord.DiscordLink.suggestions();
        });
        y += 30;
        List<String> inbox = no.sindrens.printeraddon.discord.DiscordLink.inbox();
        this.addLabel(x, y, 500, 10, 0xFFFFFFFF, inbox.isEmpty() ? "No messages from Discord yet." : "Messages from Discord:");
        y += 14;
        for (int i = 0; i < Math.min(6, inbox.size()); i++) {
            String line = inbox.get(i);
            this.addLabel(x, y, 600, 10, 0xFFDDDDDD, line.length() > 110 ? line.substring(0, 110) + "..." : line);
            y += 12;
        }
    }

    /** The page of the Auto break tab's list of blocks. */
    private static int blockPage;

    /**
     * Auto break: Litematica's selected area, start and stop, what happens to the drops, and the blocks in the area,
     * each to click on to skip that kind (all oak logs stay) or break it again.
     */
    private void addBreakTab(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        no.sindrens.printeraddon.build.AreaBreaker breaker = no.sindrens.printeraddon.build.AreaBreaker.INSTANCE;
        List<int[]> boxes = no.sindrens.printeraddon.build.AreaBreaker.selection();
        this.addLabel(x, y, 500, 10, 0xFFFFFFFF, "Selected area (Litematica's area selection): "
                + no.sindrens.printeraddon.build.AreaBreaker.describe(boxes));
        y += 14;
        if (boxes.isEmpty() && !breaker.isRunning()) {
            this.addLabel(x, y + 5, 520, 10, 0xFFAAAAAA, "Select an area with Litematica's area selection tool (corner 1 and corner 2), then open this tab again.");
            return;
        }
        boolean running = breaker.isRunning();
        this.addButton(new ButtonGeneric(x, y, 200, 20, running ? "\u00a7c\u25a0 STOP Auto break" : "\u00a7a\u25b6 START Auto break"), (button, mouse) -> {
            GuiBase.openGui(null);
            if (running) {
                breaker.stop(mc, null);
            } else {
                breaker.start(mc);
            }
        });
        boolean pickUp = Configs.Breaking.PICK_UP_ITEMS.getBooleanValue();
        this.addButton(new ButtonGeneric(x + 210, y, 240, 20, "Drops: " + (pickUp ? "\u00a7aput away\u00a7r (vaults, linked chests)" : "\u00a7eignored\u00a7r")),
                (button, mouse) -> {
                    Configs.Breaking.PICK_UP_ITEMS.setBooleanValue(!pickUp);
                    Configs.saveToFile();
                    this.initGui();
                });
        y += 24;
        if (running) {
            this.addLabel(x, y, 520, 10, 0xFFFFFF55, breaker.status());
            y += 14;
        }
        java.util.Map<net.minecraft.world.level.block.Block, Integer> blocks = no.sindrens.printeraddon.build.AreaBreaker.blocksIn(mc, boxes, false);
        int toBreak = 0;
        for (java.util.Map.Entry<net.minecraft.world.level.block.Block, Integer> entry : blocks.entrySet()) {
            if (!no.sindrens.printeraddon.build.AreaBreaker.skipped(entry.getKey())) {
                toBreak += entry.getValue();
            }
        }
        this.addLabel(x, y + 5, 420, 10, 0xFFFFFFFF, toBreak + " blocks to break" + (no.sindrens.printeraddon.build.AreaBreaker.countedAll()
                ? "" : " (counted up to 4 million spots)") + ". Click a block to skip it, again to break it:");
        this.addButton(new ButtonGeneric(x + 430, y, 90, 20, "Count again"), (button, mouse) -> {
            no.sindrens.printeraddon.build.AreaBreaker.blocksIn(mc, boxes, true);
            this.initGui();
        });
        y += 24;
        List<java.util.Map.Entry<net.minecraft.world.level.block.Block, Integer>> entries = new java.util.ArrayList<>(blocks.entrySet());
        int rows = Math.max(1, (this.height - y - 34) / 22);
        int perPage = rows * 2;
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        blockPage = Math.max(0, Math.min(blockPage, pages - 1));
        for (int i = blockPage * perPage; i < Math.min(entries.size(), (blockPage + 1) * perPage); i++) {
            net.minecraft.world.level.block.Block block = entries.get(i).getKey();
            boolean skip = no.sindrens.printeraddon.build.AreaBreaker.skipped(block);
            int index = i - blockPage * perPage;
            int bx = x + (index / rows) * 255;
            int by = y + (index % rows) * 22;
            this.addButton(new ButtonGeneric(bx, by, 250, 20, block.getName().getString() + " " + entries.get(i).getValue() + ": "
                    + (skip ? "\u00a7cskip" : "\u00a7abreak")), (button, mouse) -> {
                no.sindrens.printeraddon.build.AreaBreaker.toggleSkip(block);
                this.initGui();
            });
        }
        if (pages > 1) {
            int py = y + rows * 22 + 4;
            ButtonGeneric previous = new ButtonGeneric(x, py, 60, 20, "< Back");
            previous.setEnabled(blockPage > 0);
            this.addButton(previous, (button, mouse) -> {
                blockPage--;
                this.initGui();
            });
            this.addLabel(x + 70, py + 5, 100, 10, 0xFFAAAAAA, "Page " + (blockPage + 1) + " of " + pages);
            ButtonGeneric next = new ButtonGeneric(x + 170, py, 60, 20, "Next >");
            next.setEnabled(blockPage < pages - 1);
            this.addButton(next, (button, mouse) -> {
                blockPage++;
                this.initGui();
            });
        }
    }

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS || tab == Tab.CONTROLS ? super.getConfigWidth() : 160;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case BUILD, BREAK_AREA, DISCORD -> List.<IConfigBase>of();
            case IMPORTANT -> Configs.important();
            case COMMANDS -> no.sindrens.printeraddon.config.ServerProfiles.settings();
            case ALL -> Configs.all();
            case GENERIC -> Configs.Generic.OPTIONS;
            case BREAKING -> Configs.Breaking.OPTIONS;
            case LAYERS -> Configs.Layers.OPTIONS;
            case MOVEMENT -> Configs.Movement.OPTIONS;
            case SAFETY -> Configs.Safety.OPTIONS;
            case SHOP -> Configs.Shop.OPTIONS;
            case SPEED -> Configs.Speed.OPTIONS;
            case HOTKEYS -> Hotkeys.HOTKEY_LIST;
            case CONTROLS -> Hotkeys.CONTROL_LIST;
        };
        return ConfigOptionWrapper.createFor(configs);
    }

    private enum Tab {
        CONTROLS("Controls"),
        BUILD("Build"),
        BREAK_AREA("Auto break"),
        DISCORD("Discord"),
        IMPORTANT("Important"),
        COMMANDS("Commands (this server)"),
        ALL("All settings"),
        GENERIC("Generic"),
        BREAKING("Breaking"),
        LAYERS("Layers"),
        MOVEMENT("Movement"),
        SAFETY("Safety"),
        SHOP("Shop"),
        SPEED("Speed"),
        HOTKEYS("Hotkeys");

        final String displayName;

        Tab(String displayName) {
            this.displayName = displayName;
        }
    }

    private record TabListener(Tab tab, ConfigGui parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            ConfigGui.tab = this.tab;
            this.parent.setListPosition(10, listY(this.tab));
            this.parent.reCreateListWidget();
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
