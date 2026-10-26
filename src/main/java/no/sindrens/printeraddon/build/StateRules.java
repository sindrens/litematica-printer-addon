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
     * Like {@link #changesByItself(Property)}, for this block. Redstone sets some properties as it runs: an observer
     * saved while it pulsed is "powered" in the schematic, and no click places a powered observer, so the printer
     * never placed it (65 observers in the inventory, "Guesser=no", the layer left with them missing). A lever's
     * "powered" is how the player set it, so that one still counts.
     */
    public static boolean changesByItself(Block block, Property<?> property) {
        if (changesByItself(property)) {
            return true;
        }
        String name = property.getName();
        if (name.equals("powered")) {
            return !(block instanceof net.minecraft.world.level.block.LeverBlock);
        }
        if (name.equals("locked")) {
            return block instanceof net.minecraft.world.level.block.RepeaterBlock;
        }
        if (name.equals("lit")) {
            return block instanceof net.minecraft.world.level.block.RedstoneLampBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock
                    || block instanceof net.minecraft.world.level.block.RedStoneOreBlock
                    || block instanceof net.minecraft.world.level.block.AbstractFurnaceBlock;
        }
        return false;
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
    /**
     * The item that builds a block. Like Litematica's, but an attached stem (one bent towards its fruit, which no
     * item places) is built from seeds: the plain stem bends by itself once its fruit grows.
     */
    public static net.minecraft.world.item.ItemStack itemFor(net.minecraft.world.level.block.state.BlockState target) {
        if (target.getBlock() == Blocks.ATTACHED_PUMPKIN_STEM) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PUMPKIN_SEEDS);
        }
        if (target.getBlock() == Blocks.ATTACHED_MELON_STEM) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MELON_SEEDS);
        }
        // Farmland is dirt tilled with a hoe
        if (target.getBlock() == Blocks.FARMLAND) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT);
        }
        return fi.dy.masa.litematica.materials.MaterialCache.getInstance().getRequiredBuildItemForState(target);
    }

    public static boolean isGrowthVariant(Block a, Block b) {
        return (a == Blocks.ATTACHED_PUMPKIN_STEM && b == Blocks.PUMPKIN_STEM)
                || (a == Blocks.PUMPKIN_STEM && b == Blocks.ATTACHED_PUMPKIN_STEM)
                || (a == Blocks.ATTACHED_MELON_STEM && b == Blocks.MELON_STEM)
                || (a == Blocks.MELON_STEM && b == Blocks.ATTACHED_MELON_STEM);
    }

}
