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
        sb.append(String.format(java.util.Locale.ROOT, " | %.1f blocks away", distance));
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
        if (result == BlockCheck.Result.MISSING && AirPlace.needsAirPlace(mc.level, schematic, pos, target)) {
            sb.append(AutoBuild.INSTANCE.airPlace.allowed() ? " | floating: air place" : " | floating: air place is off");
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
            if (PrinterBridge.isAirPlace()) {
                sb.append(" (in the air on)");
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
        // Any hoe that can be used: a plain one, or one of the player's own (a netherite hoe on the hotbar was said to
        // be "no hoe", though the printer tilled with it)
        if (result == BlockCheck.Result.PRINTER_FIXES && target.getBlock() == net.minecraft.world.level.block.Blocks.FARMLAND
                && !anyHoe(mc.player.getInventory())) {
            return "no hoe for tilling";
        }
        String waiting = PlacementOrder.whyNotReady(mc.level, schematic, pos, target);
        if (waiting != null) {
            return waiting.replaceAll(",? (at )?\\[.*$", "").replaceAll(",? (at )?-?\\d+, -?\\d+, -?\\d+$", "");
        }
        if (result.needsBreaking()) {
            return "a wrong block to break first";
        }
        // Can't stand there as things are: a wrong block beside it (a grown cactus next to a cactus to go in) is to be
        // broken first, not a spot to stand at to be found (the player flew around such cactus for 3 minutes)
        if (result == BlockCheck.Result.MISSING && !target.canSurvive(mc.level, pos)) {
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                BlockPos next = pos.relative(side);
                if (BlockCheck.check(mc.level, schematic, next).needsBreaking()) {
                    return "a wrong block next to them to break first";
                }
            }
            return "they can't stand there yet (what's next to them)";
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

    private static boolean anyHoe(net.minecraft.world.entity.player.Inventory inventory) {
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (Tools.usable(Tools.HOE, stack) || Tools.usablePrivate(Tools.HOE, stack)) {
                return true;
            }
        }
        return false;
    }
}
