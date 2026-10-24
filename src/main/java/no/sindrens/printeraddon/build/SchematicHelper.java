package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to Litematica's schematic placements. */
public final class SchematicHelper {
    private SchematicHelper() {
    }

    /** Min/max corners of one placed sub-region (inclusive). */
    public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    @Nullable
    public static WorldSchematic world() {
        return SchematicWorldHandler.getSchematicWorld();
    }

    /** All enabled sub-regions of all enabled placements. */
    public static List<Area> areas() {
        List<Area> areas = new ArrayList<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isEnabled()) {
                continue;
            }

            for (Box box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
                BlockPos a = box.getPos1();
                BlockPos b = box.getPos2();
                if (a == null || b == null) {
                    continue;
                }
                areas.add(new Area(
                        Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
            }
        }

        return areas;
    }

    /**
     * Every kind of block in the enabled placements, straight from the schematics' palettes (instant: no world or
     * schematic world reading, so it's complete before the first block is looked at).
     */
    public static java.util.Set<net.minecraft.world.level.block.Block> blockKinds() {
        java.util.Set<net.minecraft.world.level.block.Block> kinds = new java.util.HashSet<>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isEnabled() || placement.getSchematic() == null) {
                continue;
            }
            try {
                for (String region : placement.getSchematic().getAreas().keySet()) {
                    var container = placement.getSchematic().getSubRegionContainer(region);
                    if (container == null || container.getPalette() == null) {
                        continue;
                    }
                    var palette = container.getPalette();
                    for (int id = 0; id < palette.getPaletteSize(); id++) {
                        net.minecraft.world.level.block.state.BlockState state = palette.getBlockState(id);
                        if (state != null && !state.isAir()) {
                            kinds.add(state.getBlock());
                        }
                    }
                }
            } catch (RuntimeException e) {
                no.sindrens.printeraddon.PrinterAddon.debug("Couldn't read the palette of {}: {}", placement.getName(), e.toString());
            }
        }
        return kinds;
    }

    /** What's placed where (names, files, origins, rotations, boxes): a different value is a different build. */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isEnabled()) {
                continue;
            }
            java.nio.file.Path file = placement.getSchematicFile();
            sb.append(placement.getName()).append('@').append(file == null ? "-" : file.getFileName())
                    .append('@').append(placement.getOrigin().toShortString()).append('@').append(placement.getRotation())
                    .append('@').append(placement.getMirror()).append(';');
        }
        return sb.append(areas()).toString();
    }

    public static boolean isInAnyArea(List<Area> areas, BlockPos pos) {
        for (Area area : areas) {
            if (area.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Lowest and highest Y of all enabled placements, or null if nothing is placed. */
    @Nullable
    public static int[] yRange(List<Area> areas) {
        if (areas.isEmpty()) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Area area : areas) {
            min = Math.min(min, area.minY());
            max = Math.max(max, area.maxY());
        }
        return new int[]{min, max};
    }
}
