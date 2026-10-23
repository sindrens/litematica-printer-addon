package no.sindrens.printeraddon.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

/** Which blocks the add-on places itself with one click, instead of leaving them to the printer. */
public enum QuickPlaceMode implements IConfigOptionListEntry {
    WHEN_PRINTER_CANT("when_printer_cant", "Only what the printer can't place"),
    ALL("all", "All it can predict (fastest)"),
    OFF("off", "Never (printer only)");

    private final String id;
    private final String displayName;

    QuickPlaceMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    public String getStringValue() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        QuickPlaceMode[] values = values();
        return values[(ordinal() + (forward ? 1 : values.length - 1)) % values.length];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (QuickPlaceMode mode : values()) {
            if (mode.id.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return WHEN_PRINTER_CANT;
    }
}
