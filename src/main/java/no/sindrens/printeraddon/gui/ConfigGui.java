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

        if (tab == Tab.BUILD) {
            addBuildTab(10, 52);
        } else if (tab != Tab.HOTKEYS) {
            // Where the search is (MaLiLib's: the magnifier at the top left of the list)
            this.addLabel(this.width - 230, 30, 220, 10, 0xFFAAAAAA, tab == Tab.ALL
                    ? "Search every setting: the magnifier at the top left"
                    : "Hover a setting for what it does. Search: 'All settings'");
        }
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
        this.addButton(new ButtonGeneric(x, y, 200, 20, running ? "Stop auto build" : "Start auto build"),
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

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS ? super.getConfigWidth() : 160;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case BUILD -> List.<IConfigBase>of();
            case IMPORTANT -> Configs.important();
            case ALL -> Configs.all();
            case GENERIC -> Configs.Generic.OPTIONS;
            case BREAKING -> Configs.Breaking.OPTIONS;
            case LAYERS -> Configs.Layers.OPTIONS;
            case MOVEMENT -> Configs.Movement.OPTIONS;
            case SAFETY -> Configs.Safety.OPTIONS;
            case SHOP -> Configs.Shop.OPTIONS;
            case SPEED -> Configs.Speed.OPTIONS;
            case HOTKEYS -> Hotkeys.HOTKEY_LIST;
        };
        return ConfigOptionWrapper.createFor(configs);
    }

    private enum Tab {
        BUILD("Build"),
        IMPORTANT("Important"),
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
            this.parent.reCreateListWidget();
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
