package no.sindrens.printeraddon.build;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import no.sindrens.printeraddon.PrinterAddon;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Farmland the printer left as dirt. The printer tills with its hoe; when such a click did nothing (2 % of them on the
 * player's server) or never came, the dirt stayed: the stem over it couldn't go in, waited "until the end", and the
 * layer went on (stems missing on finished layers of the player's pumpkin farm). Dirt where farmland goes, in reach,
 * with air over it, that the printer hasn't tilled for a moment, is tilled here: a hoe in hand, aimed at a side of it
 * that's seen (the top from above), the look sent to the server first, checked once the server answered, up to 3
 * times (then again after 30 s). Farmland that turns back into dirt is logged with the likely cause.
 */
public class Tiller {
    /** Untilled in reach this long (the printer goes first): tilled here. */
    private static final int PRINTER_FIRST_TICKS = 10;
    private static final int TRIES = 3;
    private static final int SKIP_TICKS = 20 * 30;

    /** The spot to click next tick (the hoe was put in hand this tick: the swap reaches the server first). */
    @Nullable
    private BlockPos pending;
    /**
     * Until then the printer waits after a click here: the server sees one click a turn, as from the printer alone
     * (at interval 1 it kicked the player). Clicks here only come when the printer hasn't clicked for a turn either.
     */
    private long busyUntil;
    /** Untilled spots in reach and since when. */
    private final Map<BlockPos, Long> seen = new HashMap<>();
    /** The add-on's own clicks still to check: spot -> when. */
    private final Map<BlockPos, Long> clicked = new HashMap<>();
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private final Map<BlockPos, Long> skipped = new HashMap<>();
    /** Farmland seen in reach: one that turns back into dirt is logged (see reverted). */
    private final Set<BlockPos> farmland = new HashSet<>();
    /** Spots whose reason for not being tilled was logged, and when (once in 5 minutes each). */
    private final Map<BlockPos, Long> blockedLogged = new HashMap<>();
    private int tilled;
    private int missed;
    private int reverted;
    private long noHoeLoggedAt = Long.MIN_VALUE / 2;

    public void reset() {
        pending = null;
        busyUntil = 0;
        seen.clear();
        clicked.clear();
        tries.clear();
        skipped.clear();
        farmland.clear();
        blockedLogged.clear();
        tilled = missed = reverted = 0;
    }

    public boolean isBusy() {
        return pending != null || AutoBuild.clientTicks < busyUntil;
    }

    /** "tilled here 12, missed 1, turned back into dirt 0", for the end of the build. */
    public String summary() {
        return "tilled by the add-on " + tilled + ", hoe clicks that did nothing " + missed + ", farmland back to dirt " + reverted;
    }

    public int tilledCount() {
        return tilled;
    }

    /** Dirt the game tills into farmland (coarse and rooted dirt become dirt first, then farmland). */
    public static boolean tillable(Block block) {
        return block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.DIRT_PATH
                || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT;
    }

    /** The schematic has farmland here and the world tillable dirt: the hoe still has to go over it. */
    public static boolean untilled(Level level, WorldSchematic schematic, BlockPos pos) {
        return schematic.getBlockState(pos).getBlock() == Blocks.FARMLAND && tillable(level.getBlockState(pos).getBlock());
    }

    /** Returns true if it did something this tick. */
    public boolean tick(Minecraft mc) {
        long tick = AutoBuild.clientTicks;
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null) {
            pending = null;
            return false;
        }
        int interval = Math.max(2, AutoBuild.INSTANCE.speed.interval());
        if (pending != null) {
            BlockPos pos = pending;
            pending = null;
            till(mc, player, schematic, pos, tick);
            busyUntil = tick + interval;
            return true;
        }
        // Not right after a click here or the printer's own: one click a turn in all
        if (tick < busyUntil || tick - AutoBuild.INSTANCE.lastQueuedTick < interval) {
            return false;
        }
        // No farmland in the schematic: nothing to look for
        java.util.Set<Block> blocks = AutoBuild.INSTANCE.schematicBlocks;
        if (!blocks.isEmpty() && !blocks.contains(Blocks.FARMLAND)) {
            return false;
        }
        skipped.values().removeIf(until -> until < tick);
        // Clicks never checked (the player went elsewhere): forgotten after a while
        if (clicked.size() > 256) {
            clicked.values().removeIf(at -> tick - at > 20 * 60);
            tries.keySet().retainAll(clicked.keySet());
        }
        int slot = hoeSlot(player.getInventory());
        if (slot < 0) {
            // No hoe: the printer has none either (one is got with the layer's items, see Tools); said once a minute
            if (tick - noHoeLoggedAt > 20 * 60 && AutoBuild.INSTANCE.layers.tillingLeft() > 0) {
                noHoeLoggedAt = tick;
                BuildLog.line("TILL", "no hoe in the inventory: " + AutoBuild.INSTANCE.layers.tillingLeft() + " farmland still to till waits for one");
            }
            return false;
        }
        BlockPos next = look(mc, player, schematic, tick);
        if (next == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot);
        } else {
            InventoryUtils.setPickedItemToHand(slot, inventory.getItem(slot).copy(), mc);
            AutoBuild.INSTANCE.inventory.moved();
        }
        pending = next;
        return true;
    }

    /** A plain hoe first (the best material, the most worn), else one of the player's own that isn't about to break. */
    private static int hoeSlot(Inventory inventory) {
        int slot = Tools.bestSlot(Tools.HOE, inventory);
        if (slot >= 0) {
            return slot;
        }
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (Tools.usablePrivate(Tools.HOE, inventory.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Goes over the farmland spots in reach: checks the clicks made here, notices farmland turned back into dirt, and
     * returns the nearest dirt to till now, or null.
     */
    @Nullable
    private BlockPos look(Minecraft mc, LocalPlayer player, WorldSchematic schematic, long tick) {
        ClientLevel level = mc.level;
        double reach = player.blockInteractionRange() - 0.3;
        int r = (int) Math.ceil(reach);
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        List<SchematicHelper.Area> areas = SchematicHelper.areas();
        long answerWait = Math.max(8, 2L * ServerLag.pingTicks(mc) + 6);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Set<BlockPos> inReach = new HashSet<>();

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (schematic.getBlockState(pos).getBlock() != Blocks.FARMLAND) {
                continue;
            }
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance > reach * reach || !SchematicHelper.isInAnyArea(areas, pos)) {
                continue;
            }
            // The layers being built, what waits for later, and the farmland under a stem of the layers being built
            // (its own layer finished before)
            if (!AutoBuild.INSTANCE.deferred.contains(pos) && !DataManager.getRenderLayerRange().isPositionWithinRange(pos)
                    && !DataManager.getRenderLayerRange().isPositionWithinRange(pos.above())) {
                continue;
            }
            BlockPos at = pos.immutable();
            BlockState current = level.getBlockState(at);
            Long clickedAt = clicked.get(at);
            if (current.getBlock() == Blocks.FARMLAND) {
                if (clickedAt != null) {
                    clicked.remove(at);
                    tries.remove(at);
                    tilled++;
                }
                seen.remove(at);
                farmland.add(at);
                continue;
            }
            if (!tillable(current.getBlock())) {
                farmland.remove(at);
                continue;
            }
            if (farmland.remove(at)) {
                reverted(mc, player, at);
            }
            if (clickedAt != null) {
                if (tick - clickedAt < answerWait || ServerAcks.isWaiting(at)) {
                    continue;
                }
                // Answered, and still dirt: the click did nothing
                clicked.remove(at);
                missed++;
                int count = tries.getOrDefault(at, 0);
                BuildLog.line("NOCHANGE", at.toShortString().replace(" ", "") + " item=hoe block=" + LayerManager.blockName(current)
                        + " by=add-on try=" + count + "/" + TRIES + " above=" + LayerManager.blockName(level.getBlockState(at.above()))
                        + RunLog.clickInfo(at) + RunLog.neighbours(mc, at));
                if (count >= TRIES) {
                    tries.remove(at);
                    skipped.put(at, tick + SKIP_TICKS);
                    BuildLog.line("TILL", at.toShortString().replace(" ", "") + " still " + LayerManager.blockName(current) + " after "
                            + TRIES + " hoe clicks: again in " + SKIP_TICKS / 20 + " s");
                    continue;
                }
            }
            inReach.add(at);
            seen.putIfAbsent(at, tick);
            String why = whyNot(mc, level, schematic, at);
            if (why != null) {
                // Only what lasts (a block over it), once in 5 minutes a spot: not the moments the printer just
                // clicked it or the water is still to come
                if (why.startsWith("!") && tick - blockedLogged.getOrDefault(at, -100000L) > 20 * 60 * 5) {
                    blockedLogged.put(at, tick);
                    BuildLog.line("TILL", at.toShortString().replace(" ", "") + " can't be tilled: " + why.substring(1));
                }
                continue;
            }
            if (tick - seen.get(at) < PRINTER_FIRST_TICKS || distance >= bestDistance) {
                continue;
            }
            best = at;
            bestDistance = distance;
        }
        // Out of reach: seen again from the start when back (the printer goes first again)
        seen.keySet().retainAll(inReach);
        if (blockedLogged.size() > 512) {
            blockedLogged.values().removeIf(at -> tick - at > 20 * 60 * 5);
        }
        if (farmland.size() > 4096) {
            farmland.clear();
        }
        return best;
    }

    /** Why this dirt can't be tilled right now, or null when it can ("!" first: a reason that lasts, for the log). */
    @Nullable
    private String whyNot(Minecraft mc, Level level, WorldSchematic schematic, BlockPos pos) {
        if (skipped.containsKey(pos)) {
            return "tried " + TRIES + " times a moment ago";
        }
        BlockState above = level.getBlockState(pos.above());
        if (!above.isAir()) {
            // The game only tills with air over it (a grown pumpkin, grass, water...)
            return "!" + LayerManager.blockName(above) + " over it";
        }
        if (ServerAcks.isWaiting(pos) || AutoBuild.INSTANCE.triedRecently(pos, 10)) {
            return "the printer just clicked it";
        }
        return PlacementOrder.whyNotReady(level, schematic, pos, schematic.getBlockState(pos));
    }

    private void till(Minecraft mc, LocalPlayer player, WorldSchematic schematic, BlockPos pos, long tick) {
        ClientLevel level = mc.level;
        ItemStack hand = player.getMainHandItem();
        if (!Tools.isHoe(hand) || !untilled(level, schematic, pos) || !level.getBlockState(pos.above()).isAir()) {
            return;
        }
        if (!PrinterBridge.releaseLook()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        // Gone out of reach since the hoe went into the hand: the server would refuse it
        if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > Math.pow(player.blockInteractionRange() - 0.3, 2)) {
            return;
        }
        // The top when it's seen from here, else a side (the game tills from any side but the bottom)
        Direction face = eye.y > pos.getY() + 1 ? Direction.UP : sideFacing(eye, pos);
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult seenHit = level.clip(new ClipContext(eye, hit.add(hit.subtract(eye).normalize().scale(0.2)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (seenHit.getType() == HitResult.Type.BLOCK && seenHit.getBlockPos().equals(pos)) {
            face = seenHit.getDirection() == Direction.DOWN ? face : seenHit.getDirection();
            hit = seenHit.getLocation();
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        // Looking at the face clicked, and the server told so first, as a player's own click (the printer's hoe click
        // goes to the middle of the block, looking wherever: refused again and again on the player's server)
        lookAt(player, hit);
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(),
                player.onGround(), player.horizontalCollision));
        RunLog.click(mc, pos, face, hit);
        int before = ((no.sindrens.printeraddon.mixin.ClientLevelAccessor) level).printer_addon$predictions().currentSequence();
        ServerAcks.tried(pos);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        if (((no.sindrens.printeraddon.mixin.ClientLevelAccessor) level).printer_addon$predictions().currentSequence() > before) {
            ServerAcks.clicked(before + 1);
        }
        player.swing(InteractionHand.MAIN_HAND);
        Clicks.restoreView(player, oldYaw, oldPitch);
        clicked.put(pos.immutable(), tick);
        int count = tries.merge(pos.immutable(), 1, Integer::sum);
        PrinterAddon.debug("Tilling {} myself (try {}), the printer left it as {}", pos.toShortString(), count,
                LayerManager.blockName(level.getBlockState(pos)));
    }

    /** The side of the block facing the eye most. */
    private static Direction sideFacing(Vec3 eye, BlockPos pos) {
        double dx = eye.x - (pos.getX() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Farmland that turned back into dirt, with the likely cause: a block over it (a grown pumpkin on it turns it into
     * dirt), trampled (the player right over it, falling), or dry (no water within 4 blocks and no crop on it).
     */
    private void reverted(Minecraft mc, LocalPlayer player, BlockPos pos) {
        reverted++;
        Level level = mc.level;
        BlockState above = level.getBlockState(pos.above());
        String cause;
        if (!above.isAir() && above.isSolid()) {
            cause = "block-over-it(" + LayerManager.blockName(above) + ")";
        } else if (Math.abs(player.getX() - (pos.getX() + 0.5)) < 1 && Math.abs(player.getZ() - (pos.getZ() + 0.5)) < 1
                && player.getY() - (pos.getY() + 1) < 1.5 && player.getDeltaMovement().y < 0) {
            cause = "trampled";
        } else if (!waterNear(level, pos)) {
            cause = "dry";
        } else {
            cause = "unknown";
        }
        BuildLog.line("FARMLAND-REVERTED", String.format(Locale.ROOT, "%s cause=%s over=%s", pos.toShortString().replace(" ", ""),
                cause, LayerManager.blockName(above)));
    }

    /** Water within 4 blocks, as the game looks for it to keep farmland wet. */
    private static boolean waterNear(Level level, BlockPos pos) {
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(near).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
    }
}
