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

        ItemStack item = StateRules.itemFor(target);
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
        if (AirPlace.isCandidate(mc.level, pos, target)) {
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

    /**
     * Why a block can't be placed right now, in a few words without coordinates (the same for many blocks, so they
     * can be counted, see Mover#waitReason).
     */
    public static String shortReason(Minecraft mc, WorldSchematic schematic, BlockPos pos) {
        AutoBuild build = AutoBuild.INSTANCE;
        if (build.givenUp(pos)) {
            return "tried without luck, again in a moment";
        }
        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
        BlockState target = schematic.getBlockState(pos);
        switch (result) {
            case UNLOADED -> {
                return "not loaded yet";
            }
            case BUCKET -> {
                net.minecraft.world.item.Item bucket = Fluids.bucketFor(target);
                return bucket != null && mc.player.getInventory().countItem(bucket) == 0
                        ? "no " + new ItemStack(bucket).getHoverName().getString() + " in the inventory" : "water to go in";
            }
            case PROTECTED -> {
                return "a container is in the way (fix it by hand)";
            }
            default -> {
            }
        }
        ItemStack item = StateRules.itemFor(target);
        if (result == BlockCheck.Result.MISSING && !item.isEmpty() && mc.player.getInventory().countItem(item.getItem()) == 0) {
            return "no " + item.getHoverName().getString() + " in the inventory";
        }
        if (result == BlockCheck.Result.PRINTER_FIXES && target.getBlock() == net.minecraft.world.level.block.Blocks.FARMLAND
                && Tools.hoeUses(mc.player.getInventory()) == 0) {
            return "no hoe for tilling";
        }
        String waiting = PlacementOrder.whyNotReady(mc.level, schematic, pos, target);
        if (waiting != null) {
            return waiting.replaceAll(",? (at )?\\[.*$", "").replaceAll(",? (at )?-?\\d+, -?\\d+, -?\\d+$", "");
        }
        if (result.needsBreaking()) {
            return "a wrong block to break first";
        }
        return "the printer can't place them from where it can stand";
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
