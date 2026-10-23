package no.sindrens.printeraddon.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.config.Hotkeys;

import java.util.List;
import java.util.Objects;

public class ConfigGui extends GuiConfigsBase {
    private static Tab tab = Tab.GENERIC;

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
    }

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS ? super.getConfigWidth() : 160;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
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
