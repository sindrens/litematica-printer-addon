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
    private static Tab tab = Tab.GENERIC;

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
        }
    }

    private void addBuildTab(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        boolean running = AutoBuild.INSTANCE.isRunning();
        this.addButton(new ButtonGeneric(x, y, 200, 20, running ? "Stop auto build" : "Start auto build"),
                (button, mouse) -> {
                    GuiBase.openGui(null);
                    AutoBuild.INSTANCE.toggle(mc);
                });
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
    }

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS ? super.getConfigWidth() : 160;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case BUILD -> List.<IConfigBase>of();
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
