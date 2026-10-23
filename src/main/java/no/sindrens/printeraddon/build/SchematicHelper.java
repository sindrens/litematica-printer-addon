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
