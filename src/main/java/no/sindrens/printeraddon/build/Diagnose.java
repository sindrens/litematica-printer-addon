package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.guides.Guide;
import me.aleksilassila.litematica.printer.guides.Guides;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Explains why a block isn't done, with everything that decides it: what's there and what should be, distance,
 * the item, what the add-on is waiting for, and what each of the printer's placement guides says.
 */
public final class Diagnose {
    private static final Guides GUIDES = new Guides();

    private Diagnose() {
    }

    public static String explain(Minecraft mc, WorldSchematic schematic, BlockPos pos) {
        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
        BlockState target = schematic.getBlockState(pos);
        BlockState current = mc.level.getBlockState(pos);
        StringBuilder sb = new StringBuilder();
        sb.append(pos.toShortString()).append(' ').append(result)
                .append(": is ").append(BlockText.of(current)).append(", should be ").append(BlockText.of(target));

        double distance = Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
        sb.append(String.format(" | %.1f blocks away", distance));
        double reach = Math.min(me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue(),
                mc.player.blockInteractionRange());
        if (distance > reach) {
            sb.append(" (out of reach)");
        }

        ItemStack item = MaterialCache.getInstance().getRequiredBuildItemForState(target);
        if (!item.isEmpty() && result == BlockCheck.Result.MISSING) {
            sb.append(" | item ").append(item.getHoverName().getString()).append(": ")
                    .append(mc.player.getInventory().countItem(item.getItem())).append(" in inventory");
        }

        String waiting = PlacementOrder.whyNotReady(mc.level, schematic, pos, target);
        if (waiting != null) {
            sb.append(" | waits: ").append(waiting);
        }
        AutoBuild build = AutoBuild.INSTANCE;
        if (build.deferred.contains(pos)) {
            sb.append(" | deferred (placed when ready)");
        }
        if (build.rails.isTemp(pos)) {
            sb.append(" | temporary rail spot");
        }
        if (FacePlace.isCandidate(mc.level, pos, target)) {
            sb.append(" | nothing to place it against: the add-on places it against its ")
                    .append(FacePlace.faceFor(target).getName()).append(" side");
        } else if (AirPlace.isCandidate(mc.level, pos, target)) {
            sb.append(" | floating: air place");
        }

        if (result == BlockCheck.Result.MISSING || result == BlockCheck.Result.PRINTER_FIXES) {
            sb.append(" | printer guides:");
            SchematicBlockState state = new SchematicBlockState(mc.level, schematic, pos.immutable());
            for (Guide guide : GUIDES.getInteractionGuides(state)) {
                sb.append(' ').append(guide.getClass().getSimpleName().replace("Guide", ""))
                        .append(guide.canExecute(mc.player) ? "=yes" : "=no");
                if (guide.skipOtherGuides()) {
                    sb.append("(last)");
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Short text for a block state: "hopper[facing=north]" instead of "Block{minecraft:hopper}[...]". */
    static final class BlockText {
        private BlockText() {
        }

        static String of(BlockState state) {
            return state.toString().replace("Block{minecraft:", "").replace("}", "");
        }
    }
}
