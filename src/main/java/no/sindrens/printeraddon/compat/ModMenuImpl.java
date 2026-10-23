package no.sindrens.printeraddon.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import no.sindrens.printeraddon.gui.ConfigGui;

/** The settings button in Mod Menu opens the same settings as J+C. */
public class ModMenuImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigGui gui = new ConfigGui();
            gui.setParent(parent);
            return gui;
        };
    }
}
