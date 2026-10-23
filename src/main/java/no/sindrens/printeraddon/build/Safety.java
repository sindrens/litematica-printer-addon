package no.sindrens.printeraddon.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import no.sindrens.printeraddon.config.Configs;

import javax.annotation.Nullable;

/** Reasons to stop building right away. */
public class Safety {
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
        lastHealth = health;

        if (tookDamage && Configs.Safety.STOP_ON_DAMAGE.getBooleanValue()) {
            return "you took damage";
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
