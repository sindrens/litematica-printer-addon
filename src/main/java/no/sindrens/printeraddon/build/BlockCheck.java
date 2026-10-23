package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import no.sindrens.printeraddon.config.Configs;

import java.util.Objects;
import java.util.Set;

/**
 * Compares the world with the schematic at one position and decides what should happen there.
 */
public final class BlockCheck {
    private BlockCheck() {
    }

    public enum Result {
        /** Matches the schematic (or only differs in things that change by themselves, like crop age or power). */
        OK,
        /** Nothing (or something replaceable like grass or water) where the schematic has a block: the printer places it. */
        MISSING,
        /** A different block than the schematic. Break it. */
        WRONG_BLOCK,
        /** The right block facing the wrong way / wrong half or type. Break it. */
        WRONG_STATE,
        /** Wrong, but the printer fixes it by clicking (repeater delay, door open, tilling, stripping...). */
        PRINTER_FIXES,
        /** A block where the schematic has air. */
        EXTRA,
        /** Wrong, but must not be broken automatically (container, unbreakable). */
        PROTECTED,
        /** Chunk not loaded on the client: unknown. */
        UNLOADED;

        public boolean needsBreaking() {
            return this == WRONG_BLOCK || this == WRONG_STATE;
        }
    }

    /** Properties that decide how a block is placed. A difference here can only be fixed by breaking. */
    private static final Set<String> ORIENTATION_PROPERTIES = Set.of(
            "facing", "axis", "half", "type", "hinge", "face", "rotation", "orientation", "hanging",
            "attachment", "vertical_direction");

    /** Properties the printer adds to by clicking with more of the item. */
    private static final Set<String> COUNT_PROPERTIES = Set.of("candles", "pickles", "layers");

    public static Result check(Level level, WorldSchematic schematic, BlockPos pos) {
        // Litematica only loads the schematic near the player: an unloaded part would read as air
        if (!level.isLoaded(pos) || !schematic.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return Result.UNLOADED;
        }

        BlockState target = schematic.getBlockState(pos);
        BlockState current = level.getBlockState(pos);

        if (target.isAir()) {
            if (current.isAir() || current.getBlock() instanceof LiquidBlock) {
                return Result.OK;
            }
            return isProtected(level, pos, current) ? Result.PROTECTED : Result.EXTRA;
        }

        if (current.equals(target)) {
            return Result.OK;
        }

        if (current.getBlock() == target.getBlock()) {
            return compareSameBlock(level, pos, target, current);
        }

        if (isGrowthVariant(target, current) ) {
            return Result.OK;
        }

        if (printerConverts(target, current)) {
            return Result.PRINTER_FIXES;
        }

        if (current.isAir() || current.canBeReplaced()) {
            return Result.MISSING;
        }

        return isProtected(level, pos, current) ? Result.PROTECTED : Result.WRONG_BLOCK;
    }

    private static Result compareSameBlock(Level level, BlockPos pos, BlockState target, BlockState current) {
        Block block = target.getBlock();
        boolean orientationDiff = false;
        boolean countDiff = false;
        boolean otherDiff = false;

        for (Property<?> property : target.getProperties()) {
            if (!current.hasProperty(property) || sameValue(target, current, property)) {
                continue;
            }

            String name = property.getName();

            if (name.equals("type") && block instanceof ChestBlock) {
                // single/left/right depends on the neighbour chest; the printer handles it
                otherDiff = true;
            } else if (name.equals("type") && block instanceof SlabBlock
                    && target.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                // the printer adds the second slab
                countDiff = true;
            } else if (ORIENTATION_PROPERTIES.contains(name)) {
                orientationDiff = true;
            } else if (COUNT_PROPERTIES.contains(name)) {
                countDiff = true;
            } else {
                otherDiff = true;
            }
        }

        if (orientationDiff) {
            if (!Configs.Breaking.BREAK_WRONG_STATE.getBooleanValue()) {
                return Result.PRINTER_FIXES;
            }
            return isProtected(level, pos, current) ? Result.PROTECTED : Result.WRONG_STATE;
        }

        if (countDiff) {
            return Result.PRINTER_FIXES;
        }

        if (otherDiff && isClickFixable(block)) {
            return Result.PRINTER_FIXES;
        }

        // Only differs in things that change by themselves: power, age, connections, moisture...
        return Result.OK;
    }

    private static <T extends Comparable<T>> boolean sameValue(BlockState a, BlockState b, Property<T> property) {
        return Objects.equals(a.getValue(property), b.getValue(property));
    }

    /** Blocks the printer fixes by right-clicking them (same list as the printer's CycleStateGuide). */
    private static boolean isClickFixable(Block block) {
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock
                || block instanceof LeverBlock || block instanceof RepeaterBlock || block instanceof ComparatorBlock
                || block instanceof NoteBlock;
    }

    /** A stem that has grown a fruit (or the reverse) is still correct. */
    private static boolean isGrowthVariant(BlockState target, BlockState current) {
        Block t = target.getBlock();
        Block c = current.getBlock();
        return (t == Blocks.ATTACHED_PUMPKIN_STEM && c == Blocks.PUMPKIN_STEM)
                || (t == Blocks.PUMPKIN_STEM && c == Blocks.ATTACHED_PUMPKIN_STEM)
                || (t == Blocks.ATTACHED_MELON_STEM && c == Blocks.MELON_STEM)
                || (t == Blocks.MELON_STEM && c == Blocks.ATTACHED_MELON_STEM);
    }

    /** Cases the printer fixes without breaking: tilling, stripping logs, filling flower pots. */
    private static boolean printerConverts(BlockState target, BlockState current) {
        Block t = target.getBlock();
        Block c = current.getBlock();

        if (t == Blocks.FARMLAND) {
            return c == Blocks.DIRT || c == Blocks.GRASS_BLOCK || c == Blocks.DIRT_PATH || c == Blocks.COARSE_DIRT;
        }

        if (t == Blocks.DIRT && c == Blocks.COARSE_DIRT) {
            return true;
        }

        if (t instanceof FlowerPotBlock && c == Blocks.FLOWER_POT) {
            return true;
        }

        String targetId = BuiltInRegistries.BLOCK.getKey(t).getPath();
        String currentId = BuiltInRegistries.BLOCK.getKey(c).getPath();
        return targetId.startsWith("stripped_") && targetId.substring("stripped_".length()).equals(currentId);
    }

    public static boolean isProtected(Level level, BlockPos pos, BlockState current) {
        if (current.getDestroySpeed(level, pos) < 0) {
            return true;
        }
        return Configs.Breaking.SKIP_CONTAINERS.getBooleanValue() && level.getBlockEntity(pos) instanceof Container;
    }
}
