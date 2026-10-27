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
        EXTRA,
        /** Every block of Litematica's area selection (Auto break, see AreaBreaker): no schematic needed. */
        AREA
    }

    /** The mode of the last tick: Auto break takes the fastest tool, the player's own too (see selectBestTool). */
    private Mode currentMode = Mode.WRONG;

    /** Couldn't break this a moment ago (left for a minute). */
    public boolean failedRecently(BlockPos pos) {
        Long at = failed.get(pos);
        return at != null && (at == Long.MAX_VALUE || tickCounter - at <= FAILED_RETRY_TICKS);
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

    /** The block being broken goes with the first hit (string, a flower): no need to hold still for it. */
    private boolean quickTarget;

    public boolean quickTarget() {
        return target != null && quickTarget && hopTo == null;
    }

    /** Breaking this block, or one up to 3 under it in its column (a column of cactus falls with its lowest). */
    public boolean breakingUnder(BlockPos pos) {
        return target != null && target.getX() == pos.getX() && target.getZ() == pos.getZ()
                && pos.getY() >= target.getY() && pos.getY() - target.getY() <= 3;
    }

    /**
     * Standing on the block under the feet while breaking, flying off: the game breaks blocks 5 times slower off the
     * ground (a cactus took 66 ticks while flying, 12 standing). Only when there's something to stand on right under
     * the feet (the player hovers on top of the layer), no cactus near, and not on the block being broken.
     */
    private boolean standing;
    private int standingTicks;
    /**
     * Nothing to stand on under the feet (a lane flown along the empty row beside a cactus row): first a short hop,
     * at most a block sideways, onto a block of the layer that's there, then standing there while breaking.
     */
    @Nullable
    private Vec3 hopTo;
    private int hopTicks;
    private static final int HOP_TICKS = 20;
    /**
     * The spot to stand at is at most this many blocks to the side (a hop of 0.6 s at most). Waiting for the sand
     * around to come in first was tried: the lane flew on meanwhile and came back for each, the layer took twice as long.
     */
    private static final int HOP_REACH = 2;

    public boolean isStanding() {
        return standing || hopTo != null;
    }

    /** Where to stand for breaking this block: a spot beside the player on top of a block that's there, or null. */
    @Nullable
    private Vec3 standSpot(LocalPlayer player, BlockPos target) {
        net.minecraft.world.level.Level level = player.level();
        Vec3 at = player.position();
        BlockPos feet = BlockPos.containing(at.x, at.y + 0.3, at.z);
        double range = range(player);
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        // Why the spots around didn't do, for the build log when none does
        int noFooting = 0;
        int harm = 0;
        int far = 0;
        int blocked = 0;
        for (int dx = -HOP_REACH; dx <= HOP_REACH; dx++) {
            for (int dz = -HOP_REACH; dz <= HOP_REACH; dz++) {
                BlockPos spotFeet = feet.offset(dx, 0, dz);
                BlockPos below = spotFeet.below();
                if (below.equals(target) || !FlyPath.fits(level, spotFeet)) {
                    blocked++;
                    continue;
                }
                net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
                if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.999) {
                    noFooting++;
                    continue;
                }
                Vec3 spot = new Vec3(spotFeet.getX() + 0.5, spotFeet.getY(), spotFeet.getZ() + 0.5);
                if (Mover.harmNear(level, spot) != null) {
                    harm++;
                    continue;
                }
                if (spot.add(0, player.getEyeHeight(), 0).distanceToSqr(Vec3.atCenterOf(target)) > range * range) {
                    far++;
                    continue;
                }
                if (!FlyPath.clear(level, at, spot) || !harmFreeWay(level, at, spot)) {
                    blocked++;
                    continue;
                }
                double distance = at.distanceToSqr(spot);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = spot;
                }
            }
        }
        noSpotWhy = String.format(java.util.Locale.ROOT, "from %.1f,%.1f,%.1f: noFooting=%d cactusNear=%d outOfReach=%d blocked=%d",
                at.x, at.y, at.z, noFooting, harm, far, blocked);
        return best;
    }

    /** Why the last search found no spot to stand at (see standSpot), for the build log when it breaks from the air. */
    private String noSpotWhy = "";
    /** When the last "from the air" line was written, and the breaks from the air since. */
    private long airLoggedAt = Long.MIN_VALUE / 2;
    private int airBreaks;

    /** Nothing that hurts near the body anywhere on the straight way from a to b. */
    private static boolean harmFreeWay(net.minecraft.world.level.Level level, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        int steps = Math.max(1, (int) Math.ceil(d.length() / 0.3));
        for (int i = 1; i <= steps; i++) {
            if (Mover.harmNear(level, a.add(d.scale((double) i / steps))) != null) {
                return false;
            }
        }
        return true;
    }

    /** On the way to the spot to stand at: true while still going (the break starts once there). */
    private boolean hopping(Minecraft mc, LocalPlayer player) {
        if (hopTo == null) {
            return false;
        }
        Vec3 to = hopTo.subtract(player.position());
        if (to.lengthSqr() < 0.06 * 0.06 || ++hopTicks > HOP_TICKS) {
            hopTo = null;
            player.setDeltaMovement(Vec3.ZERO);
            standForBreaking(player, target);
            mc.gameMode.startDestroyBlock(target, face);
            player.swing(InteractionHand.MAIN_HAND);
            return false;
        }
        double length = to.length();
        player.setDeltaMovement(to.scale(Math.min(0.2, length) / length));
        return true;
    }

    private void standForBreaking(LocalPlayer player, BlockPos pos) {
        net.minecraft.world.entity.player.Abilities abilities = player.getAbilities();
        if (standing || !abilities.flying || !abilities.mayfly || !Configs.Breaking.STAND_TO_BREAK.getBooleanValue()) {
            return;
        }
        BlockPos under = BlockPos.containing(player.getX(), player.getY() - 0.06, player.getZ());
        if (pos.equals(under) || player.level().noCollision(player, player.getBoundingBox().move(0, -0.06, 0))
                || Mover.harmNear(player.level(), player.position()) != null) {
            return;
        }
        abilities.flying = false;
        player.onUpdateAbilities();
        standing = true;
        standingTicks = 0;
    }

    /** Flying again after breaking (or when standing didn't work out). */
    private void flyAgain(LocalPlayer player) {
        if (!standing) {
            return;
        }
        standing = false;
        net.minecraft.world.entity.player.Abilities abilities = player.getAbilities();
        if (abilities.mayfly && !abilities.flying) {
            abilities.flying = true;
            player.onUpdateAbilities();
            // On the ground the game ends flying again after the tick's move (as when landing): up off it, as its own
            // double jump does. Else Auto break stood on its next block, waiting, and once broke it and fell
            if (player.onGround()) {
                Vec3 motion = player.getDeltaMovement();
                player.setDeltaMovement(motion.x, 0.42, motion.z);
            }
        }
    }

    public int getBrokenCount() {
        return brokenCount;
    }

    /** Breaks done, of them standing, and the ticks they took (for the run log). */
    private int breaks;
    private int standingBreaks;
    private long breakTicks;

    public String summary() {
        return "breaks=" + breaks + " standing=" + standingBreaks + " avgTicks=" + (breaks == 0 ? 0 : breakTicks / breaks);
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
        hopTo = null;
        if (mc.player != null) {
            flyAgain(mc.player);
        }
    }

    /**
     * Runs one tick. Returns false when there is nothing (more) to break within reach.
     */
    public boolean tick(Minecraft mc, Mode mode) {
        tickCounter++;
        LocalPlayer player = mc.player;
        WorldSchematic schematic = SchematicHelper.world();

        currentMode = mode;
        if (player == null || mc.level == null || mc.gameMode == null || schematic == null && mode != Mode.AREA) {
            target = null;
            return false;
        }

        if (target != null) {
            if (!hopping(mc, player)) {
                continueBreaking(mc, player, schematic, mode);
            }
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
        // A block that takes long: standing for it (here, or a hop beside); one that breaks at once (a flower) from the air
        boolean slow = state.getDestroySpeed(mc.level, pos) > 0.05f && Configs.Breaking.STAND_TO_BREAK.getBooleanValue()
                && player.getAbilities().flying && player.getAbilities().mayfly;
        Vec3 spot = null;
        if (slow && player.level().noCollision(player, player.getBoundingBox().move(0, -0.06, 0))) {
            spot = standSpot(player, pos);
            // Once a minute at most: on a cactus farm there's no footing at all, and every break was a line
            if (spot == null && AutoBuild.clientTicks - airLoggedAt >= 1200) {
                BuildLog.lineOrKeep("BREAK", "from the air: " + pos.toShortString().replace(" ", "") + ", no spot to stand at " + noSpotWhy
                        + (airBreaks > 0 ? " (" + airBreaks + " more from the air since the last line)" : ""));
                airLoggedAt = AutoBuild.clientTicks;
                airBreaks = 0;
            } else if (spot == null) {
                airBreaks++;
            }
        }
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
        selectBestTool(player, state, currentMode == Mode.AREA);
        quickTarget = state.getDestroyProgress(player, mc.level, pos) >= 1.0f;
        lookAt(player, pos);
        hopTo = spot;
        hopTicks = 0;
        PrinterAddon.debug("Breaking {} at {} with {}{}", state, target.toShortString(),
                player.getInventory().getItem(player.getInventory().getSelectedSlot()), hopTo != null ? ", from " + hopTo : "");
        if (hopTo != null) {
            return;
        }
        if (slow) {
            standForBreaking(player, target);
        }
        mc.gameMode.startDestroyBlock(target, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void continueBreaking(Minecraft mc, LocalPlayer player, @Nullable WorldSchematic schematic, Mode mode) {
        BlockPos pos = target;
        BlockCheck.Result result = schematic == null ? null : BlockCheck.check(mc.level, schematic, pos);
        boolean isForced = forced.contains(pos);

        boolean gone = mode == Mode.AREA ? !AreaBreaker.stillThere(mc.level, pos)
                : isForced ? mc.level.getBlockState(pos).isAir() : !shouldBreak(result, mode, pos);
        if (gone) {
            if (isForced) {
                forced.remove(pos);
            }
            // Gone (or fixed): done
            PrinterAddon.debug("Done with {} after {} ticks ({}{})", pos.toShortString(), ticksOnTarget, result, standing ? ", standing" : "");
            breakTicks += ticksOnTarget;
            breaks++;
            if (standing) {
                standingBreaks++;
            }
            target = null;
            brokenCount++;
            cooldown = Configs.Speed.breakCooldownTicks();
            flyAgain(player);
            return;
        }
        // Standing didn't work out (nothing held the player after all): flying again, breaking on from the air
        if (standing && ++standingTicks > 3 && !player.onGround()) {
            flyAgain(player);
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

    /**
     * Broken as one of the "always break" blocks: there, on the layers being built, where the schematic wants a block
     * that can be placed right now (it's in the inventory, and the build isn't waiting for items). On a big cactus farm
     * flowers come back on the cactus tops all the time: out of string it stopped placing and went round breaking
     * flowers until string came, and flowers anywhere else in the schematic were chased too. Now a flower only goes
     * when it's in the way of the next block.
     */
    public static boolean breakAnyway(net.minecraft.world.level.Level level, WorldSchematic schematic, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!alwaysBreak(state)) {
            return false;
        }
        BlockState wanted = schematic.getBlockState(pos);
        if (wanted.getBlock() == state.getBlock() || !DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
            return false;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || AutoBuild.INSTANCE.isWaitingForItems()) {
            return false;
        }
        // Where a block goes itself
        if (!wanted.isAir()) {
            return canPlaceNow(mc, wanted);
        }
        // Beside a block still to go in: the printer clicks against the flower (or holds that block back while it's
        // there, see Clicks.goingAway). Three sand blocks of a finished layer waited for flowers beside them for good
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState nWanted = schematic.getBlockState(n);
            if (!nWanted.isAir() && level.getBlockState(n).canBeReplaced() && !level.getBlockState(n).is(nWanted.getBlock())
                    && DataManager.getRenderLayerRange().isPositionWithinRange(n) && canPlaceNow(mc, nWanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canPlaceNow(net.minecraft.client.Minecraft mc, BlockState wanted) {
        net.minecraft.world.item.Item item = wanted.getBlock().asItem();
        return item != net.minecraft.world.item.Items.AIR && (mc.player.getAbilities().instabuild || mc.player.getInventory().countItem(item) > 0);
    }

    private static boolean shouldBreak(BlockCheck.Result result, Mode mode, BlockPos pos) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        WorldSchematic schematic = SchematicHelper.world();
        if (mode == Mode.WRONG && mc.level != null && schematic != null && alwaysBreak(mc.level.getBlockState(pos))) {
            return breakAnyway(mc.level, schematic, pos);
        }
        return switch (mode) {
            // A container the printer put in the wrong spot during this build may be broken too
            case WRONG -> (result.needsBreaking() && !AutoBuild.INSTANCE.isConflict(pos, result,
                    net.minecraft.client.Minecraft.getInstance().level.getBlockState(pos)))
                    || (result == BlockCheck.Result.PROTECTED && AutoBuild.INSTANCE.placedDuringBuild(pos));
            case EXTRA -> result == BlockCheck.Result.EXTRA;
            // Auto break decides for itself (see AreaBreaker): never asked here
            case AREA -> false;
        };
    }

    @Nullable
    private BlockPos findNext(Minecraft mc, LocalPlayer player, WorldSchematic schematic, Mode mode) {
        failed.values().removeIf(t -> t != Long.MAX_VALUE && tickCounter - t > FAILED_RETRY_TICKS);
        suspects.values().removeIf(seen -> tickCounter - seen.since() > FAILED_RETRY_TICKS);
        if (mode == Mode.AREA) {
            return AreaBreaker.INSTANCE.nextInReach(mc, player, range(player));
        }
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
            // (Not a flower that's in the way: it goes with one hit, and the block it's in the way of may be the one
            // that would make it drop, held back while it's there)
            BlockPos dropsFor = alwaysBreak(mc.level.getBlockState(pos)) ? null : dropsBecauseOf(mc.level, schematic, pos);
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
     * The best tool for the block onto the hand: a plain one that helps first (never one of the player's own when a
     * plain one does), or with {@code fastest} (Auto break) simply the fastest, Efficiency counted, the player's own
     * too. Never one of the player's own that's about to break.
     */
    private static void selectBestTool(LocalPlayer player, BlockState state, boolean fastest) {
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

            int key = fastest ? Math.min(1, keyOf(stack, state)) : keyOf(stack, state);
            float score = fastest ? effectiveSpeed(player, stack, state) : scoreOf(stack, state);
            if (key > bestKey || key == bestKey && score > bestScore) {
                bestKey = key;
                bestScore = score;
                bestSlot = slot;
            }
        }

        // The selected tool might be one of the player's own that is about to break: move off it
        ItemStack selected = inventory.getItem(inventory.getSelectedSlot());
        boolean selectedProtected = worn(selected);
        int selectedKey = fastest ? Math.min(1, keyOf(selected, state)) : keyOf(selected, state);
        float selectedScore = fastest ? effectiveSpeed(player, selected, state) : scoreOf(selected, state);

        if (bestSlot != -1 && (selectedProtected || bestKey > selectedKey
                || bestKey == selectedKey && bestScore > selectedScore + 0.001f)) {
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

    /** How fast the stack breaks the block, Efficiency included (for the fastest tool, see selectBestTool). */
    private static float effectiveSpeed(LocalPlayer player, ItemStack stack, BlockState state) {
        float speed = scoreOf(stack, state);
        if (stack.getDestroySpeed(state) > 1.0f) {
            try {
                var efficiency = player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
                int level = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack);
                if (level > 0) {
                    speed += level * level + 1;
                }
            } catch (RuntimeException ignored) {
                // No enchantment registry (shouldn't happen in a world): the tool's own speed
            }
        }
        return speed;
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
