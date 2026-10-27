package no.sindrens.printeraddon.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

/**
 * Speed presets. Each preset sets how fast the add-on (and, if enabled, the printer) acts.
 * CUSTOM uses the values in the "Speed" tab.
 */
public enum SpeedPreset implements IConfigOptionListEntry {
    // The printer stays at interval 12 in every preset: faster makes it place blocks wrong
    // The official (Modrinth) build is never faster than a vanilla player: flying at most 0.55 blocks a tick, 5 ticks
    // between two mined blocks; "fast" (flying 0.9 a tick, breaking with no wait) is only in the Mythian build
    SAFE("safe", "Safe", 12, 6, 10, 0.18, 0.4),
    NORMAL("normal", "Normal", 12, no.sindrens.printeraddon.Edition.OFFICIAL ? 5 : 2, 6, 0.21, 0.55),
    FAST("fast", "Fast", 12, 0, 3, 0.27, 0.9),
    CUSTOM("custom", "Custom", -1, -1, -1, -1, -1);

    private final String id;
    private final String displayName;
    /** Printer's own "printingInterval" (lower is faster). */
    public final int printerInterval;
    /** Ticks to wait after a block is broken before the next one. */
    public final int breakCooldownTicks;
    /** Ticks to wait between clicks in the shop menu. */
    public final int shopClickDelayTicks;
    /** Blocks per tick when walking (vanilla walking is about 0.21). */
    public final double walkSpeed;
    /** Blocks per tick when flying (vanilla creative flight is about 0.55). */
    public final double flySpeed;

    SpeedPreset(String id, String displayName, int printerInterval, int breakCooldownTicks, int shopClickDelayTicks,
                double walkSpeed, double flySpeed) {
        this.id = id;
        this.displayName = displayName;
        this.printerInterval = printerInterval;
        this.breakCooldownTicks = breakCooldownTicks;
        this.shopClickDelayTicks = shopClickDelayTicks;
        this.walkSpeed = walkSpeed;
        this.flySpeed = flySpeed;
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
        if (values[index] == FAST && no.sindrens.printeraddon.Edition.OFFICIAL) {
            index = (index + (forward ? 1 : values.length - 1)) % values.length;
        }
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (SpeedPreset preset : values()) {
            if (preset.id.equalsIgnoreCase(value)) {
                return preset == FAST && no.sindrens.printeraddon.Edition.OFFICIAL ? NORMAL : preset;
            }
        }
        return NORMAL;
    }
}
