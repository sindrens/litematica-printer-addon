package no.sindrens.printeraddon.build;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/** Which differences between a placed block and the schematic don't matter, because they change on their own. */
public final class StateRules {
    private StateRules() {
    }

    /**
     * Properties that change by themselves after placing: growth (age, stage), farmland moisture, redstone power,
     * pistons extending, hoppers switched off by power, leaf distance... Placing can never set them, so they must
     * not stop the printer from placing. Properties the printer sets by clicking (lever, delay, mode, open, lit,
     * note) are not in here.
     */
    private static final Set<String> CHANGE_BY_THEMSELVES = Set.of(
            "age", "stage", "moisture", "power", "triggered", "enabled", "extended", "short", "distance",
            "persistent", "honey_level", "hatch", "berries", "drag", "bloom", "shrieking", "can_summon",
            "sculk_sensor_phase", "occupied", "snowy", "bottom", "in_wall");

    public static boolean changesByItself(Property<?> property) {
        return CHANGE_BY_THEMSELVES.contains(property.getName());
    }

    /**
     * A filled cauldron (lava, water, powder snow) is placed as an empty cauldron and filled by hand: placing the
     * empty one is right.
     */
    public static boolean isPlaceableBase(Block target, Block placed) {
        return placed == Blocks.CAULDRON
                && (target == Blocks.LAVA_CAULDRON || target == Blocks.WATER_CAULDRON || target == Blocks.POWDER_SNOW_CAULDRON);
    }

    /** A stem that has grown a fruit is the same plant as a fresh stem: planting the seed is right. */
    public static boolean isGrowthVariant(Block a, Block b) {
        return (a == Blocks.ATTACHED_PUMPKIN_STEM && b == Blocks.PUMPKIN_STEM)
                || (a == Blocks.PUMPKIN_STEM && b == Blocks.ATTACHED_PUMPKIN_STEM)
                || (a == Blocks.ATTACHED_MELON_STEM && b == Blocks.MELON_STEM)
                || (a == Blocks.MELON_STEM && b == Blocks.ATTACHED_MELON_STEM);
    }
}
