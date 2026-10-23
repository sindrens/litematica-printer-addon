package no.sindrens.printeraddon.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

/**
 * Speed presets. Each preset sets how fast the add-on (and, if enabled, the printer) acts.
 * CUSTOM uses the values in the "Speed" tab.
 */
public enum SpeedPreset implements IConfigOptionListEntry {
    SAFE("safe", "Safe", 12, 6, 10),
    NORMAL("normal", "Normal", 6, 2, 6),
    FAST("fast", "Fast", 2, 0, 3),
    CUSTOM("custom", "Custom", -1, -1, -1);

    private final String id;
    private final String displayName;
    /** Printer's own "printingInterval" (lower is faster). */
    public final int printerInterval;
    /** Ticks to wait after a block is broken before the next one. */
    public final int breakCooldownTicks;
    /** Ticks to wait between clicks in the shop menu. */
    public final int shopClickDelayTicks;

    SpeedPreset(String id, String displayName, int printerInterval, int breakCooldownTicks, int shopClickDelayTicks) {
        this.id = id;
        this.displayName = displayName;
        this.printerInterval = printerInterval;
        this.breakCooldownTicks = breakCooldownTicks;
        this.shopClickDelayTicks = shopClickDelayTicks;
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
        SpeedPreset[] values = values();
        int index = (ordinal() + (forward ? 1 : values.length - 1)) % values.length;
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (SpeedPreset preset : values()) {
            if (preset.id.equalsIgnoreCase(value)) {
                return preset;
            }
        }
        return NORMAL;
    }
}
