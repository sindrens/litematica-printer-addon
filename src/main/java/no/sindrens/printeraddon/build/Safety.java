package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import no.sindrens.printeraddon.config.Configs;

import javax.annotation.Nullable;

/** Reasons to stop building right away. */
public class Safety {
    /** Brushing a cactus doesn't stop the build until health is down to this (half of it). */
    private static final float PLANT_DAMAGE_STOP = 10;
    private float lastHealth = -1;
    /** The last check found damage from a cactus or berry bush (the build goes on, the player gets out of it). */
    private boolean plantHurt;

    /** Health this low stops the build whatever hurt the player (also with "Stop on damage" off). */
    private static final float LOW_HEALTH = 6;
    /** The inventory full (no free slot) for this long with nothing put away: stop instead of going on blind. */
    private static final long FULL_INVENTORY_MS = 90_000;
    private long fullSince = -1;
    private int placedAtFull = -1;

    public void reset() {
        fullSince = -1;
        lastHealth = -1;
        plantHurt = false;
    }

    /** The last check found damage from a cactus or berry bush, building on. */
    public boolean hurtByPlant() {
        return plantHurt;
    }

    /** Returns why building must stop, or null if everything is fine. */
    @Nullable
    public String check(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }

        plantHurt = false;
        float health = player.getHealth();
        boolean tookDamage = lastHealth >= 0 && health < lastHealth - 0.01f;
        float lost = lastHealth - health;
        lastHealth = health;

        // A little health gone with no cause at all (no attacker, no block: the server's health sync, an effect running
        // out): Pouched's builds stopped 3 times for 0.4 of it with over 20 health left
        net.minecraft.world.damagesource.DamageSource lastSource = player.getLastDamageSource();
        if (tookDamage && lost < 1.0f && health >= PLANT_DAMAGE_STOP
                && (lastSource == null || lastSource.getEntity() == null && "generic".equals(lastSource.getMsgId()))) {
            BuildLog.line("SAFETY", String.format(java.util.Locale.ROOT, "lost %.1f health, no cause known (health %.1f): building on", lost, health));
            tookDamage = false;
        }
        if (tookDamage && Configs.Safety.STOP_ON_DAMAGE.getBooleanValue()) {
            // A cactus (or berry bush) of the build brushed on the way: half a heart, nobody attacking. Building goes on
            // while there's plenty of health left
            net.minecraft.world.damagesource.DamageSource source = player.getLastDamageSource();
            boolean plant = source != null && (source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS)
                    || source.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH));
            if (!plant || health < PLANT_DAMAGE_STOP) {
                // What it was, for the log: two stops of a night's run said only "you took damage"
                String kind = source == null ? "unknown" : source.getMsgId();
                net.minecraft.world.entity.Entity by = source == null ? null : source.getEntity();
                BuildLog.line("SAFETY", String.format(java.util.Locale.ROOT, "damage source=%s%s player=%.2f,%.2f,%.2f flying=%s onGround=%s lost=%.1f health=%.1f food=%d: stopping",
                        kind, by == null ? "" : " by=" + by.getName().getString(), player.getX(), player.getY(), player.getZ(),
                        player.getAbilities().flying, player.onGround(), lost, health, player.getFoodData().getFoodLevel()));
                return plant ? "a cactus of the build hurt you (" + String.format(java.util.Locale.ROOT, "%.1f", health) + " health left)"
                        : "you took damage (" + kind + (by == null ? "" : " from " + by.getName().getString()) + String.format(java.util.Locale.ROOT, ", %.1f health left)", health);
            }
            plantHurt = true;
            // Which block it was (the player saw the flower on top and took it for the flower: it's the cactus under it)
            net.minecraft.core.BlockPos harm = Mover.harmNear(mc.level, player.position());
            String what = harm == null ? "" : " at " + harm.toShortString()
                    + (mc.level.getBlockState(harm.above()).is(net.minecraft.world.level.block.Blocks.CACTUS_FLOWER) ? " (a flower on top)" : "")
                    + (harm.getY() > AutoBuild.INSTANCE.layers.getLayerTop() ? ", grown above the layers being built" : "");
            BuildLog.line("SAFETY", String.format(java.util.Locale.ROOT, "damage source=%s%s player=%.2f,%.2f,%.2f lost=%.1f health=%.1f food=%d: building on",
                    source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) ? "cactus" : "berry-bush", what,
                    player.getX(), player.getY(), player.getZ(), lost, health, player.getFoodData().getFoodLevel()));
            RunLog.harmTouch();
            RunLog.event("damage", "source=" + (source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) ? "cactus" : "berry-bush")
                    + (harm != null ? " block=" + harm.toShortString().replace(" ", "") : "") + String.format(java.util.Locale.ROOT, " lost=%.1f health=%.1f", lost, health));
        }

        // Always, whatever the settings: lava, a fall into the void, very low health
        if (player.isInLava()) {
            BuildLog.line("SAFETY", String.format(java.util.Locale.ROOT, "in lava at %.1f %.1f %.1f: stopping", player.getX(), player.getY(), player.getZ()));
            return "you're in lava";
        }
        if (!player.getAbilities().flying && (player.getY() < mc.level.getMinY() + 4 || player.fallDistance > 12 && noGroundBelow(mc, player))) {
            BuildLog.line("SAFETY", String.format(java.util.Locale.ROOT, "falling at %.1f %.1f %.1f (fell %.1f, nothing below): stopping",
                    player.getX(), player.getY(), player.getZ(), player.fallDistance));
            return "you're falling with nothing under you (the void)";
        }
        if (health > 0 && health <= LOW_HEALTH) {
            return String.format(java.util.Locale.ROOT, "your health is low (%.1f)", health);
        }
        // Full and nothing placed meanwhile (a full inventory of building blocks that get placed is normal)
        int placedNow = AutoBuild.INSTANCE.isRunning() ? AutoBuild.INSTANCE.stats.totalPlaced() : 0;
        if (AutoBuild.INSTANCE.isRunning() && InventoryCleaner.freeSlots(player.getInventory()) == 0 && placedNow == placedAtFull) {
            long now = System.currentTimeMillis();
            if (fullSince < 0) {
                fullSince = now;
            } else if (now - fullSince > FULL_INVENTORY_MS) {
                fullSince = -1;
                return "your inventory has been full for 90 seconds with nothing placed (link a chest, or make room)";
            }
        } else {
            fullSince = -1;
            placedAtFull = placedNow;
        }

        if (Configs.Safety.STOP_NEAR_PLAYERS.getBooleanValue()) {
            double distance = Configs.Safety.NEAR_PLAYER_DISTANCE.getIntegerValue();
            for (Player other : mc.level.players()) {
                if (other != player && !other.isSpectator() && other.distanceToSqr(player) <= distance * distance
                        && !trusted(other.getName().getString())) {
                    return other.getName().getString() + " is nearby";
                }
            }
        }

        return null;
    }

    private static boolean noGroundBelow(Minecraft mc, LocalPlayer player) {
        net.minecraft.core.BlockPos.MutableBlockPos pos = player.blockPosition().mutable();
        for (int i = 0; i < 64 && pos.getY() > mc.level.getMinY(); i++) {
            pos.move(0, -1, 0);
            if (!mc.level.getBlockState(pos).isAir() || !mc.level.getFluidState(pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** A player on the "Players who don't stop it" list (names, any case, comma or space separated). */
    private static boolean trusted(String name) {
        for (String n : Configs.Safety.TRUSTED_PLAYERS.getStringValue().split("[,;\\s]+")) {
            if (!n.isBlank() && n.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
