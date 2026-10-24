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

    private static boolean shouldBreak(BlockCheck.Result result, Mode mode, BlockPos pos) {
        return switch (mode) {
            // A container the printer put in the wrong spot during this build may be broken too
            case WRONG -> result.needsBreaking()
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
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
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
                continue;
            }

            BlockCheck.Result result = BlockCheck.check(mc.level, schematic, pos);
            if (!shouldBreak(result, mode, pos)) {
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

        return best;
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
     * Picks the fastest tool in the hotbar for this block, skipping tools that are about to break.
     */
    private static void selectBestTool(LocalPlayer player, BlockState state) {
        if (player.getAbilities().instabuild) {
            return;
        }

        Inventory inventory = player.getInventory();
        int minDurability = Configs.Breaking.MIN_TOOL_DURABILITY.getIntegerValue();
        int bestSlot = -1;
        float bestScore = -1;

        // The whole inventory: the hotbar is kept for the layer's blocks, so tools may be in the inventory
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= minDurability) {
                continue;
            }

            float score = scoreOf(stack, state);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        // The selected tool might be one that is about to break: move off it
        ItemStack selected = inventory.getItem(inventory.getSelectedSlot());
        boolean selectedProtected = selected.isDamageableItem()
                && selected.getMaxDamage() - selected.getDamageValue() <= minDurability;

        if (bestSlot != -1 && (selectedProtected || bestScore > scoreOf(selected, state) + 0.001f)) {
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
