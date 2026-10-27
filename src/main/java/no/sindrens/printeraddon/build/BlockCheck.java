package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import no.sindrens.printeraddon.config.Configs;

import java.util.List;
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
        UNLOADED,
        /** Needs a bucket: a water/lava source, filling a cauldron, or waterlogging a block. The add-on does it. */
        BUCKET;

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
        // Past the edge of where the server lets the player build (see Edge): nothing to do there
        if (Edge.outside(level, pos)) {
            return Result.OK;
        }

        BlockState target = schematic.getBlockState(pos);
        BlockState current = level.getBlockState(pos);

        // A piston that was moving when the schematic was saved: nothing to place there. One moving right now (a
        // farm at work): in 2 ticks it's settled, and the next look sees what's there then (it can't be broken,
        // and counted as wrong a farm's layer was never done)
        if (target.getBlock() == Blocks.MOVING_PISTON || current.getBlock() == Blocks.MOVING_PISTON) {
            return Result.OK;
        }

        if (target.getBlock() instanceof LiquidBlock) {
            if (current.getFluidState().getType().isSame(target.getFluidState().getType())) {
                return Result.OK;
            }
            // Flowing water/lava comes from its source by itself
            if (!target.getFluidState().isSource()) {
                return Result.OK;
            }
            return current.isAir() || current.canBeReplaced() ? Result.BUCKET : Result.WRONG_BLOCK;
        }

        // Lava/water/powder snow in a cauldron: the printer places the empty cauldron, then a bucket fills it
        if (target.getBlock() instanceof AbstractCauldronBlock && target.getBlock() != Blocks.CAULDRON
                && current.getBlock() == Blocks.CAULDRON) {
            return Result.BUCKET;
        }

        if (target.isAir()) {
            if (current.isAir() || current.getBlock() instanceof LiquidBlock) {
                return Result.OK;
            }
            return isProtected(level, pos, current) ? Result.PROTECTED : Result.EXTRA;
        }

        if (current.equals(target)) {
            return Result.OK;
        }

        // A pumpkin or melon next to its stem grows there by itself (a farm's fruit spot): nothing to place, and
        // whatever is there now (the fruit, or nothing) is right
        if (isGrownFruit(schematic, pos, target)) {
            return Result.OK;
        }

        if (current.getBlock() == target.getBlock()) {
            return compareSameBlock(level, schematic, pos, target, current);
        }

        if (StateRules.isGrowthVariant(target.getBlock(), current.getBlock())) {
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

    /**
     * True for a rail whose shape isn't final yet because a rail it should join isn't placed: it isn't joined to
     * any rail it shouldn't be, and it turns by itself (or tilts, for a slope) when that rail goes in.
     */
    private static boolean railPending(Level level, WorldSchematic schematic, BlockPos pos, BlockState target, BlockState current) {
        List<Direction> want = RailHelper.connections(target);
        boolean waiting = false;
        for (Direction d : want) {
            BlockPos next = pos.relative(d);
            if (RailHelper.railAroundInSchematic(schematic, next) && !RailHelper.railAround(level, next)) {
                waiting = true;
            }
        }
        if (!waiting) {
            return false;
        }
        for (Direction d : RailHelper.connections(current)) {
            if (!want.contains(d) && RailHelper.railAround(level, pos.relative(d))) {
                return false;
            }
        }
        return true;
    }

    private static Result compareSameBlock(Level level, WorldSchematic schematic, BlockPos pos, BlockState target, BlockState current) {
        Block block = target.getBlock();
        boolean orientationDiff = false;
        boolean countDiff = false;
        boolean otherDiff = false;

        for (Property<?> property : target.getProperties()) {
            if (!current.hasProperty(property) || sameValue(target, current, property)) {
                continue;
            }

            String name = property.getName();

            if (name.equals("facing") && (block == Blocks.ATTACHED_PUMPKIN_STEM || block == Blocks.ATTACHED_MELON_STEM)) {
                // An attached stem bends towards wherever its fruit grew: that changes by itself (never broken for it)
                continue;
            }
            if (name.equals("type") && block instanceof ChestBlock) {
                // single/left/right depends on the neighbour chest; the printer handles it
                otherDiff = true;
            } else if (name.equals("type") && block instanceof SlabBlock
                    && target.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                // the printer adds the second slab
                countDiff = true;
            } else if (name.equals("shape") && block instanceof net.minecraft.world.level.block.BaseRailBlock) {
                // Not final yet: a rail it joins (often a slope's top, in the next layer) isn't there yet
                if (railPending(level, schematic, pos, target, current)) {
                    continue;
                }
                // A rail with the wrong shape sends carts the wrong way: break it and place it again
                orientationDiff = true;
            } else if (ORIENTATION_PROPERTIES.contains(name)) {
                orientationDiff = true;
            } else if (COUNT_PROPERTIES.contains(name)) {
                countDiff = true;
            } else {
                otherDiff = true;
            }
        }

        if (!orientationDiff && target.hasProperty(BlockStateProperties.WATERLOGGED)
                && target.getValue(BlockStateProperties.WATERLOGGED) && !current.getValue(BlockStateProperties.WATERLOGGED)) {
            // Right block, but it should stand in water: a water bucket does that
            return Result.BUCKET;
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

    /** A pumpkin or melon in the schematic right next to a stem of its kind: the stem grows it. */
    private static boolean isGrownFruit(WorldSchematic schematic, BlockPos pos, BlockState target) {
        Block fruit = target.getBlock();
        if (fruit != Blocks.PUMPKIN && fruit != Blocks.MELON) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Block next = schematic.getBlockState(pos.relative(direction)).getBlock();
            if (fruit == Blocks.PUMPKIN ? next == Blocks.PUMPKIN_STEM || next == Blocks.ATTACHED_PUMPKIN_STEM
                    : next == Blocks.MELON_STEM || next == Blocks.ATTACHED_MELON_STEM) {
                return true;
            }
        }
        return false;
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
