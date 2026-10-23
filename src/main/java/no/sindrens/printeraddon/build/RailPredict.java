package no.sindrens.printeraddon.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Predicts the shape a rail gets when it's placed, with the same rules as the game: it joins the rails next to it
 * (also one block up or down) that have a free end, prefers a curve when two of them meet at a corner, tilts up to a
 * rail one block higher, and otherwise lies along the way the player faces.
 */
public final class RailPredict {
    private RailPredict() {
    }

    /**
     * The shape a rail placed at {@code pos} gets now.
     *
     * @param straightOnly powered, detector and activator rails can't curve
     * @param facingEastWest the player faces east or west (the shape it takes when nothing decides)
     */
    public static RailShape shapeIfPlaced(Level level, BlockPos pos, boolean straightOnly, boolean facingEastWest) {
        RailShape fallback = facingEastWest ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        boolean n = canJoin(level, pos, Direction.NORTH);
        boolean s = canJoin(level, pos, Direction.SOUTH);
        boolean w = canJoin(level, pos, Direction.WEST);
        boolean e = canJoin(level, pos, Direction.EAST);

        RailShape shape = null;
        boolean ns = n || s;
        boolean ew = w || e;
        if (ns && !ew) {
            shape = RailShape.NORTH_SOUTH;
        }
        if (ew && !ns) {
            shape = RailShape.EAST_WEST;
        }
        boolean se = s && e;
        boolean sw = s && w;
        boolean ne = n && e;
        boolean nw = n && w;
        if (!straightOnly) {
            if (se && !n && !w) {
                shape = RailShape.SOUTH_EAST;
            }
            if (sw && !n && !e) {
                shape = RailShape.SOUTH_WEST;
            }
            if (nw && !s && !e) {
                shape = RailShape.NORTH_WEST;
            }
            if (ne && !s && !w) {
                shape = RailShape.NORTH_EAST;
            }
        }
        if (shape == null) {
            if (ns && ew) {
                shape = fallback;
            } else if (ns) {
                shape = RailShape.NORTH_SOUTH;
            } else if (ew) {
                shape = RailShape.EAST_WEST;
            }
            if (!straightOnly) {
                // Not powered: the game checks the corners in this order
                if (nw) {
                    shape = RailShape.NORTH_WEST;
                }
                if (ne) {
                    shape = RailShape.NORTH_EAST;
                }
                if (sw) {
                    shape = RailShape.SOUTH_WEST;
                }
                if (se) {
                    shape = RailShape.SOUTH_EAST;
                }
            }
        }
        if (shape == RailShape.NORTH_SOUTH) {
            if (isRail(level, pos.north().above())) {
                shape = RailShape.ASCENDING_NORTH;
            }
            if (isRail(level, pos.south().above())) {
                shape = RailShape.ASCENDING_SOUTH;
            }
        }
        if (shape == RailShape.EAST_WEST) {
            if (isRail(level, pos.east().above())) {
                shape = RailShape.ASCENDING_EAST;
            }
            if (isRail(level, pos.west().above())) {
                shape = RailShape.ASCENDING_WEST;
            }
        }
        return shape == null ? fallback : shape;
    }

    /** The directions a shape joins (a slope counts as its line). */
    public static List<Direction> directions(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> List.of(Direction.NORTH, Direction.SOUTH);
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> List.of(Direction.EAST, Direction.WEST);
            case NORTH_EAST -> List.of(Direction.NORTH, Direction.EAST);
            case NORTH_WEST -> List.of(Direction.NORTH, Direction.WEST);
            case SOUTH_EAST -> List.of(Direction.SOUTH, Direction.EAST);
            case SOUTH_WEST -> List.of(Direction.SOUTH, Direction.WEST);
        };
    }

    /** The directions in which a rail placed with this shape actually joins a rail now. */
    public static List<Direction> links(Level level, BlockPos pos, RailShape shape) {
        List<Direction> links = new ArrayList<>();
        for (Direction d : directions(shape)) {
            if (canJoin(level, pos, d)) {
                links.add(d);
            }
        }
        return links;
    }

    /** True if there's a rail on that side (same level, or one up or down) with a free end, so a new rail joins it. */
    static boolean canJoin(Level level, BlockPos pos, Direction side) {
        BlockPos neighbor = railAt(level, pos.relative(side));
        if (neighbor == null) {
            return false;
        }
        return linkedCount(level, neighbor, pos) < 2;
    }

    /** How many rails the rail at {@code rail} is joined to both ways, not counting the spot {@code ignore}. */
    private static int linkedCount(Level level, BlockPos rail, BlockPos ignore) {
        int count = 0;
        for (BlockPos end : ends(rail, level.getBlockState(rail))) {
            if (end.getX() == ignore.getX() && end.getZ() == ignore.getZ()) {
                continue;
            }
            BlockPos other = railAt(level, end);
            if (other != null && pointsAt(other, level.getBlockState(other), rail)) {
                count++;
            }
        }
        return count;
    }

    private static boolean pointsAt(BlockPos rail, BlockState state, BlockPos target) {
        for (BlockPos end : ends(rail, state)) {
            if (end.getX() == target.getX() && end.getZ() == target.getZ()) {
                return true;
            }
        }
        return false;
    }

    /** The two spots a placed rail's ends point at (one up for the high end of a slope). */
    private static List<BlockPos> ends(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BaseRailBlock rail)) {
            return List.of();
        }
        RailShape shape = state.getValue(rail.getShapeProperty());
        return switch (shape) {
            case NORTH_SOUTH -> List.of(pos.north(), pos.south());
            case EAST_WEST -> List.of(pos.west(), pos.east());
            case ASCENDING_NORTH -> List.of(pos.north().above(), pos.south());
            case ASCENDING_SOUTH -> List.of(pos.north(), pos.south().above());
            case ASCENDING_EAST -> List.of(pos.west(), pos.east().above());
            case ASCENDING_WEST -> List.of(pos.west().above(), pos.east());
            case NORTH_EAST -> List.of(pos.north(), pos.east());
            case NORTH_WEST -> List.of(pos.north(), pos.west());
            case SOUTH_EAST -> List.of(pos.south(), pos.east());
            case SOUTH_WEST -> List.of(pos.south(), pos.west());
        };
    }

    /** The rail at a spot, or one block above or below it, like the game looks for neighbours. */
    @Nullable
    static BlockPos railAt(Level level, BlockPos pos) {
        if (isRail(level, pos)) {
            return pos;
        }
        if (isRail(level, pos.above())) {
            return pos.above();
        }
        if (isRail(level, pos.below())) {
            return pos.below();
        }
        return null;
    }

    private static boolean isRail(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BaseRailBlock;
    }
}
