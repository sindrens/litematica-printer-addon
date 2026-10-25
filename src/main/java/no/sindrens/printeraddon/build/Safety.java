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

    public void reset() {
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

        if (tookDamage && Configs.Safety.STOP_ON_DAMAGE.getBooleanValue()) {
            // A cactus (or berry bush) of the build brushed on the way: half a heart, nobody attacking. Building goes on
            // while there's plenty of health left
            net.minecraft.world.damagesource.DamageSource source = player.getLastDamageSource();
            boolean plant = source != null && (source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS)
                    || source.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH));
            if (!plant || health < PLANT_DAMAGE_STOP) {
                return plant ? "a cactus of the build hurt you (" + String.format(java.util.Locale.ROOT, "%.1f", health) + " health left)" : "you took damage";
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

        if (Configs.Safety.STOP_NEAR_PLAYERS.getBooleanValue()) {
            double distance = Configs.Safety.NEAR_PLAYER_DISTANCE.getIntegerValue();
            for (Player other : mc.level.players()) {
                if (other != player && !other.isSpectator() && other.distanceToSqr(player) <= distance * distance) {
                    return other.getName().getString() + " is nearby";
                }
            }
        }

        return null;
    }
}
