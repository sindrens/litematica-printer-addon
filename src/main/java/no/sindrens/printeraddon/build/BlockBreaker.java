package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;
import no.sindrens.printeraddon.config.Configs;
import no.sindrens.printeraddon.util.Chat;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Breaks blocks that don't match the schematic, one at a time, with the best tool in the hotbar.
 */
public class BlockBreaker {
    public enum Mode {
        /** Wrong block type / wrong direction. */
        WRONG,
        /** Blocks where the schematic has air. */
        EXTRA
    }

    /** Skip a block that failed to break for this many ticks. */
    private static final int FAILED_RETRY_TICKS = 20 * 60;

    @Nullable
    private BlockPos target;
    private Direction face = Direction.UP;
    private int ticksOnTarget;
    private int cooldown;
    private long tickCounter;
    private int brokenCount;
    /** Set by findNext: a wrong block was found but hasn't been wrong long enough yet. */
    private boolean waiting;
    private final Map<BlockPos, Long> failed = new HashMap<>();
    /** When a wrong block was first seen, and as what: it must stay the same for a while before it's broken. */
    private final Map<BlockPos, Seen> suspects = new HashMap<>();
    /** How often each spot has been broken, to notice the printer placing it wrong again and again. */
    private final Map<BlockPos, Integer> breakCounts = new HashMap<>();

    private record Seen(BlockState state, long since) {
    }

    /** Blocks to break no matter what the schematic says (blocks air place put in the wrong spot). */
    private Set<BlockPos> forced = new java.util.HashSet<>();

    public void setForced(Set<BlockPos> forced) {
        this.forced = forced;
    }

    public boolean isBusy() {
        return target != null;
    }

    public int getBrokenCount() {
        return brokenCount;
    }

    public void resetBrokenCount() {
        brokenCount = 0;
    }

    /** Forget which spots were given up on (start of a new build). */
    public void reset() {
        failed.clear();
        suspects.clear();
        breakCounts.clear();
    }

    public void stop(Minecraft mc) {
        if (target != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        target = null;
    }

    /**
     * Runs one tick. Returns false when there is nothing (more) to break within reach.
     */
    public boolean tick(Minecraft mc, Mode mode) {
        tickCounter++;
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();

        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            target = null;
            return false;
        }

        if (target != null) {
            continueBreaking(mc, player, schematic, mode);
            return true;
        }

        if (cooldown > 0) {
            cooldown--;
            return true;
        }

        waiting = false;
        BlockPos next = findNext(mc, player, schematic, mode);
        if (next == null) {
            // Still busy if a wrong block is being watched before it's broken
            return waiting;
        }

        startBreaking(mc, player, next);
        return true;
    }

    private void startBreaking(Minecraft mc, LocalPlayer player, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        target = pos.immutable();
        suspects.remove(target);

        int count = breakCounts.merge(target, 1, Integer::sum);
        int max = Configs.Breaking.MAX_BREAKS_PER_BLOCK.getIntegerValue();
        if (count > max) {
            // Placed wrong every time: leave it and tell the player once
            failed.put(target, Long.MAX_VALUE);
            target = null;
            Chat.warn(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " keeps coming back wrong ("
                    + state.getBlock().getName().getString() + "). Not breaking it again; fix it by hand.");
            return;
        }

        ticksOnTarget = 0;
        face = faceTowardsPlayer(player, pos);

        selectBestTool(player, state);
        lookAt(player, pos);
        PrinterAddon.debug("Breaking {} at {} with {}", state, target.toShortString(),
                player.getInventory().getItem(player.getInventory().getSelectedSlot()));
        mc.gameMode.startDestroyBlock(target, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void continueBreaking(Minecraft mc, LocalPlayer player, WorldSchematic schematic, Mode mode) {
        BlockPos pos = target;
        BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
        boolean isForced = forced.contains(pos);

        if (isForced ? mc.level.getBlockState(pos).isAir() : !shouldBreak(result, mode, pos)) {
            if (isForced) {
                forced.remove(pos);
            }
            // Gone (or fixed): done
            PrinterAddon.debug("Done with {} after {} ticks ({})", pos.toShortString(), ticksOnTarget, result);
            target = null;
            brokenCount++;
            cooldown = Configs.Speed.breakCooldownTicks();
            return;
        }

        int timeout = Configs.Breaking.BREAK_TIMEOUT_SECONDS.getIntegerValue() * 20;
        if (++ticksOnTarget > timeout || !inReach(player, pos)) {
            PrinterAddon.debug("Gave up on {} after {} ticks (in reach: {})", pos.toShortString(), ticksOnTarget,
                    inReach(player, pos));
            failed.put(pos, tickCounter);
            stop(mc);
            return;
        }

        lookAt(player, pos);
        mc.gameMode.continueDestroyBlock(pos, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    /** The blocks of the "Always break these blocks" option (read again when it changes). */
    private static String alwaysBreakText;
    private static java.util.Set<net.minecraft.world.level.block.Block> alwaysBreak = java.util.Set.of();

    /** One of the blocks the player wants gone wherever they are in the schematic (cactus flowers on the farm). */
    public static boolean alwaysBreak(BlockState state) {
        String text = Configs.Breaking.ALWAYS_BREAK.getStringValue();
        if (!text.equals(alwaysBreakText)) {
            alwaysBreakText = text;
            java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.HashSet<>();
            for (String part : text.split("[,;\\s]+")) {
                String id = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (id.isEmpty()) {
                    continue;
                }
                net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (key != null) {
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key).ifPresent(block -> {
                        if (block != net.minecraft.world.level.block.Blocks.AIR) {
                            blocks.add(block);
                        }
                    });
                }
            }
            alwaysBreak = blocks;
        }
        return !alwaysBreak.isEmpty() && alwaysBreak.contains(state.getBlock());
    }

    /** Broken as one of the "always break" blocks: there, and not what the schematic wants there. */
    public static boolean breakAnyway(net.minecraft.world.level.Level level, WorldSchematic schematic, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return alwaysBreak(state) && schematic.getBlockState(pos).getBlock() != state.getBlock();
    }

    private static boolean shouldBreak(BlockCheck.Result result, Mode mode, BlockPos pos) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        WorldSchematic schematic = SchematicHelper.world();
        if (mode == Mode.WRONG && mc.level != null && schematic != null && breakAnyway(mc.level, schematic, pos)) {
            return true;
        }
        return switch (mode) {
            // A container the printer put in the wrong spot during this build may be broken too
            case WRONG -> (result.needsBreaking() && !AutoBuild.INSTANCE.isConflict(pos, result,
                    net.minecraft.client.Minecraft.getInstance().level.getBlockState(pos)))
                    || (result == BlockCheck.Result.PROTECTED && AutoBuild.INSTANCE.placedDuringBuild(pos));
            case EXTRA -> result == BlockCheck.Result.EXTRA;
        };
    }

    @Nullable
    private BlockPos findNext(Minecraft mc, LocalPlayer player, WorldSchematic schematic, Mode mode) {
        failed.values().removeIf(t -> t != Long.MAX_VALUE && tickCounter - t > FAILED_RETRY_TICKS);
        suspects.values().removeIf(seen -> tickCounter - seen.since() > FAILED_RETRY_TICKS);
        int wrongFor = Configs.Breaking.WRONG_FOR_TICKS.getIntegerValue();

        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        if (areas.isEmpty()) {
            return null;
        }

        double range = range(player);
        int r = (int) Math.ceil(range);
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        BlockPos feet = player.blockPosition();
        BlockPos underFeet = feet.below();

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance > range * range || distance >= bestDistance) {
                continue;
            }
            if (forced.contains(pos) && !mc.level.getBlockState(pos).isAir()) {
                best = pos.immutable();
                bestDistance = distance;
                continue;
            }
            // Only the layers being built; "always break" blocks anywhere in the schematic's area
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos) && !(mode == Mode.WRONG && breakAnyway(mc.level, schematic, pos))) {
                continue;
            }
            // A temporary rail stays until the rail helper releases it
            if (AutoBuild.INSTANCE.rails.isTemp(pos)) {
                continue;
            }
            if (!SchematicHelper.isInAnyArea(areas, pos)) {
                continue;
            }
            // Don't dig out the block we're standing on
            if (pos.equals(underFeet) && !player.getAbilities().flying) {
                continue;
            }
            if (failed.containsKey(pos)) {
                skipNote(pos, "failed (no luck breaking it a moment ago: again in a minute)");
                continue;
            }

            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            // (The player's own blocks, left alone, are in the CONFLICT lines: thousands of them on the player's island)
            if (!shouldBreak(result, mode, pos)) {
                continue;
            }
            // A cactus that grew up (or the flower on it), with the layer's sand still to go in beside it somewhere
            // down its column: the whole column drops by itself once that's placed (breaking each takes 3 s while
            // flying, 5 times slower than standing: 74 blocks a minute on the player's server)
            BlockPos dropsFor = dropsBecauseOf(mc.level, schematic, pos);
            if (dropsFor != null) {
                skipNote(pos, "growth (drops by itself once " + dropsFor.toShortString() + " is placed)");
                continue;
            }

            BlockPos immutable = pos.immutable();
            BlockState state = mc.level.getBlockState(pos);
            Seen seen = suspects.get(immutable);
            if (seen == null || !seen.state().equals(state)) {
                suspects.put(immutable, new Seen(state, tickCounter));
                if (wrongFor > 0) {
                    waiting = true;
                    continue;
                }
            } else if (tickCounter - seen.since() < wrongFor) {
                waiting = true;
                continue;
            }

            best = immutable;
            bestDistance = distance;
        }

        // A column of cactus to go: its lowest one, and the ones above it break by themselves (one break of 3 s instead
        // of one for each)
        if (best != null && mc.level.getBlockState(best).is(net.minecraft.world.level.block.Blocks.CACTUS)) {
            BlockPos low = best;
            while (mc.level.getBlockState(low.below()).is(net.minecraft.world.level.block.Blocks.CACTUS)
                    && eye.distanceToSqr(Vec3.atCenterOf(low.below())) <= range * range
                    && SchematicHelper.isInAnyArea(areas, low.below()) && !failed.containsKey(low.below())
                    && shouldBreak(BlockCheck.check(mc.level, schematic, low.below()), mode, low.below())) {
                low = low.below();
            }
            best = low;
        }
        return best;
    }

    /**
     * A cactus (or a cactus flower on one) that will drop by itself: down its column, a cactus has beside it a solid
     * block of the layers being built that's still missing and can go in now. A cactus can't stand next to one: it
     * drops as soon as that's placed, and everything on top of it with it.
     */
    public static boolean fallsByItself(net.minecraft.world.level.Level level, WorldSchematic schematic, BlockPos pos) {
        return dropsBecauseOf(level, schematic, pos) != null;
    }

    /** The block still to go in whose placing knocks this one down (see fallsByItself), or null. */
    @Nullable
    private static BlockPos dropsBecauseOf(net.minecraft.world.level.Level level, WorldSchematic schematic, BlockPos pos) {
        BlockPos at = pos;
        if (level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.CACTUS_FLOWER)) {
            at = at.below();
        }
        for (int steps = 0; steps < 4 && level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.CACTUS); steps++) {
            BlockPos side = popsFor(level, schematic, at);
            if (side != null) {
                return side;
            }
            at = at.below();
        }
        return null;
    }

    /** Why each wrong block was last left alone (once a minute each in the build log). */
    private final Map<BlockPos, Long> skipNoted = new HashMap<>();

    private void skipNote(BlockPos pos, String reason) {
        Long at = skipNoted.get(pos);
        if (at != null && tickCounter - at < 20 * 60) {
            return;
        }
        if (skipNoted.size() > 512) {
            skipNoted.values().removeIf(t -> tickCounter - t >= 20 * 60);
        }
        skipNoted.put(pos.immutable(), tickCounter);
        Minecraft mc = Minecraft.getInstance();
        BuildLog.line("BREAK-SKIP", pos.toShortString().replace(" ", "") + " " + (mc.level == null ? "?"
                : LayerManager.blockName(mc.level.getBlockState(pos))) + " reason=" + reason);
    }

    /** The missing block beside this cactus whose placing makes it drop, or null. */
    @Nullable
    private static BlockPos popsFor(net.minecraft.world.level.Level level, WorldSchematic schematic, BlockPos pos) {
        LayerManager layers = AutoBuild.INSTANCE.layers;
        if (!layers.isActive() || pos.getY() < layers.getLayer() || pos.getY() > layers.getLayerTop()) {
            return null;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            BlockState wanted = schematic.getBlockState(side);
            if (wanted.isAir() || !wanted.isSolid() || !level.getBlockState(side).canBeReplaced()
                    || BlockCheck.check(level, schematic, side) != BlockCheck.Result.MISSING) {
                continue;
            }
            // Only a block that can go in beside the cactus as it stands: a cactus of the layer can't (a cactus never
            // stands next to one), so a grown one was left for that cactus to knock down, which never came; 68 cactus
            // were left at the end of a layer, the player flying around them for 3 minutes
            if (!wanted.canSurvive(level, side)) {
                continue;
            }
            // Sand only goes in on what holds it (its string)
            if (wanted.getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                    && net.minecraft.world.level.block.FallingBlock.isFree(level.getBlockState(side.below()))) {
                continue;
            }
            return side;
        }
        return null;
    }

    private static double range(LocalPlayer player) {
        return Math.min(Configs.Breaking.BREAK_RANGE.getDoubleValue(), player.blockInteractionRange());
    }

    private static boolean inReach(LocalPlayer player, BlockPos pos) {
        double range = range(player) + 0.5;
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
    }

    private static Direction faceTowardsPlayer(LocalPlayer player, BlockPos pos) {
        Vec3 d = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    /** Tells the server the player looks at the block (some servers check it); the camera stays where it is. */
    private static void lookAt(LocalPlayer player, BlockPos pos) {
        if (Configs.Breaking.LOOK_AT_BLOCKS.getBooleanValue()) {
            Vec3 d = Vec3.atCenterOf(pos).subtract(player.getEyePosition());
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(yaw, pitch,
                    player.onGround(), player.horizontalCollision));
        }
    }

    /**
     * Picks the tool for this block: a plain one that breaks it faster first (used until it breaks), then one of the
     * player's own (enchanted, renamed, netherite: the player's rule, those last), never one of those about to break.
     */
    private static void selectBestTool(LocalPlayer player, BlockState state) {
        if (player.getAbilities().instabuild) {
            return;
        }

        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        int bestKey = -1;
        float bestScore = -1;

        // The whole inventory: the hotbar is kept for the layer's blocks, so tools may be in the inventory
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (worn(stack)) {
                continue;
            }

            int key = keyOf(stack, state);
            float score = scoreOf(stack, state);
            if (key > bestKey || key == bestKey && score > bestScore) {
                bestKey = key;
                bestScore = score;
                bestSlot = slot;
            }
        }

        // The selected tool might be one of the player's own that is about to break: move off it
        ItemStack selected = inventory.getItem(inventory.getSelectedSlot());
        boolean selectedProtected = worn(selected);
        int selectedKey = keyOf(selected, state);

        if (bestSlot != -1 && (selectedProtected || bestKey > selectedKey
                || bestKey == selectedKey && bestScore > scoreOf(selected, state) + 0.001f)) {
            if (!Inventory.isHotbarSlot(bestSlot)) {
                // Swap the tool onto the last hotbar slot (the one left free for tools)
                int toolSlot = Inventory.getSelectionSize() - 1;
                Minecraft mc = Minecraft.getInstance();
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, bestSlot, toolSlot,
                        net.minecraft.world.inventory.ContainerInput.SWAP, player);
                AutoBuild.INSTANCE.inventory.moved();
                bestSlot = toolSlot;
            }
            inventory.setSelectedSlot(bestSlot);
        }
    }

    /** One of the player's own tools that's about to break: not used (plain ones are used until they break). */
    private static boolean worn(ItemStack stack) {
        return Tools.privateTool(stack) && stack.isDamageableItem() && (Tools.wornSpecial(stack)
                || stack.getMaxDamage() - stack.getDamageValue() <= Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue());
    }

    /** 2: a plain tool that helps with this block, 1: one of the player's own that helps, 0: nothing that helps. */
    private static int keyOf(ItemStack stack, BlockState state) {
        boolean helps = stack.getDestroySpeed(state) > 1.0f || state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state);
        return !helps ? 0 : Tools.privateTool(stack) ? 1 : 2;
    }

    private static float scoreOf(ItemStack stack, BlockState state) {
        // Mining speed, plus a bonus for the right tool, plus a tiny bonus for not wearing out a tool
        float score = stack.getDestroySpeed(state);
        if (state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state)) {
            score += 0.5f;
        }
        if (!stack.isDamageableItem()) {
            score += 0.01f;
        }
        return score;
    }
}
