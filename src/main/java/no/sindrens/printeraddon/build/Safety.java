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

    public void reset() {
        lastHealth = -1;
    }

    /** Returns why building must stop, or null if everything is fine. */
    @Nullable
    public String check(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }

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
                return "you took damage";
            }
            BuildLog.line("SAFETY", String.format("brushed a %s at %.1f %.1f %.1f: -%.1f health, %.1f left, building on",
                    source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) ? "cactus" : "berry bush",
                    player.getX(), player.getY(), player.getZ(), lost, health));
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
