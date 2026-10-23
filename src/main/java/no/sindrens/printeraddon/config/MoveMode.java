package no.sindrens.printeraddon.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

/** How auto build gets the player to the next unfinished blocks. */
public enum MoveMode implements IConfigOptionListEntry {
    WALK_AND_FLY("walk_and_fly", "Walk, fly when needed"),
    WALK("walk", "Walk only"),
    FLY("fly", "Fly only"),
    OFF("off", "Don't move");

    private final String id;
    private final String displayName;

    MoveMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public boolean canWalk() {
        return this == WALK_AND_FLY || this == WALK;
    }

    public boolean canFly() {
        return this == WALK_AND_FLY || this == FLY;
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
        MoveMode[] values = values();
        return values[(ordinal() + (forward ? 1 : values.length - 1)) % values.length];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (MoveMode mode : values()) {
            if (mode.id.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return WALK_AND_FLY;
    }
}
