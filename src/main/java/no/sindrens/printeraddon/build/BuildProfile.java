package no.sindrens.printeraddon.build;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What kind of build this is, worked out from its blocks once they're counted, and the settings that matter for it
 * switched on: there are no presets to choose. The rest adapts by itself as it builds (layers together where safe, lane
 * width from the reach, spot by spot for few blocks, holds for what stands on what...). Said once in chat and the log.
 */
public final class BuildProfile {
    private static String last = "";

    private BuildProfile() {
    }

    public static String last() {
        return last;
    }

    /** {@code needs}: what the build still needs, by item; {@code blocks}: the block kinds the schematic has. */
    public static void apply(Map<Item, Integer> needs, Set<Block> blocks) {
        long total = 0;
        long cactusFarm = 0;
        long farm = 0;
        long redstone = 0;
        long falling = 0;
        long sided = 0;
        for (Map.Entry<Item, Integer> e : needs.entrySet()) {
            total += e.getValue();
            if (!(e.getKey() instanceof BlockItem bi)) {
                if (e.getKey() == net.minecraft.world.item.Items.STRING) {
                    cactusFarm += e.getValue();
                }
                continue;
            }
            Block b = bi.getBlock();
            if (b == Blocks.CACTUS || b == Blocks.SAND || b == Blocks.RED_SAND) {
                cactusFarm += e.getValue();
            }
            if (b instanceof net.minecraft.world.level.block.CropBlock || b instanceof net.minecraft.world.level.block.StemBlock
                    || b == Blocks.FARMLAND || b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO) {
                farm += e.getValue();
            }
            if (b instanceof net.minecraft.world.level.block.ObserverBlock || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                    || b instanceof net.minecraft.world.level.block.DiodeBlock || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || b instanceof net.minecraft.world.level.block.HopperBlock || b instanceof net.minecraft.world.level.block.DispenserBlock) {
                redstone += e.getValue();
            }
            if (b instanceof net.minecraft.world.level.block.FallingBlock) {
                falling += e.getValue();
            }
            if (b.defaultBlockState().getProperties().stream().anyMatch(p -> p.getName().equals("facing") || p.getName().equals("axis"))) {
                sided += e.getValue();
            }
        }
        if (total == 0) {
            return;
        }
        boolean water = blocks.contains(Blocks.WATER) || blocks.stream().anyMatch(b -> b.defaultBlockState().hasProperty(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
        List<String> kinds = new ArrayList<>();
        List<String> doing = new ArrayList<>();
        if (cactusFarm * 10 >= total * 6) {
            kinds.add("cactus farm");
            if (!Configs.Layers.CAP_BEFORE_STOP.getBooleanValue()) {
                Configs.Layers.CAP_BEFORE_STOP.setBooleanValue(true);
                doing.add("covers open cactus before a stop");
            }
            doing.add("sand only on string the server has");
        }
        if (farm * 10 >= total) {
            kinds.add("crop farm");
            doing.add("tills the farmland as it goes");
        }
        if (redstone * 20 >= total || sided * 10 >= total * 3) {
            kinds.add(redstone * 20 >= total ? "redstone" : "blocks that face a way");
            if (!Configs.Generic.TURN_VIEW_FOR_PRINTER.getBooleanValue()) {
                Configs.Generic.TURN_VIEW_FOR_PRINTER.setBooleanValue(true);
            }
            if (!Configs.Speed.WAIT_AFTER_SWITCH.getBooleanValue()) {
                Configs.Speed.WAIT_AFTER_SWITCH.setBooleanValue(true);
            }
            doing.add("faces each block the right way");
        }
        if (falling * 10 >= total && cactusFarm * 10 < total * 6) {
            kinds.add("sand/gravel");
            doing.add("falling blocks only on what's surely there");
        }
        if (water) {
            kinds.add("water");
            doing.add("water with a bucket on its turn");
        }
        if (kinds.isEmpty()) {
            kinds.add("general build");
        }
        if (!Configs.Speed.CLICK_SEEN_FACE.getBooleanValue()) {
            Configs.Speed.CLICK_SEEN_FACE.setBooleanValue(true);
        }
        doing.add("layers together where safe");
        doing.add("lanes as wide as the reach allows");
        String text = String.join(", ", kinds) + String.format(Locale.ROOT, " (%,d blocks to go)", total);
        last = String.join(", ", kinds);
        BuildLog.line("PROFILE", text + " | " + String.join("; ", doing));
        Chat.info("Build: " + text + ". Set for it: " + String.join(", ", doing) + ".");
    }
}
