package no.sindrens.printeraddon.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** A /fly command like EssentialsX's, so the test server works like a real one. */
public class TestCommands implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("fly").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    boolean allow = !player.getAbilities().mayfly;
                    player.getAbilities().mayfly = allow;
                    if (!allow) {
                        player.getAbilities().flying = false;
                    }
                    player.onUpdateAbilities();
                    player.sendSystemMessage(Component.literal("Flying " + (allow ? "enabled" : "disabled")));
                    return 1;
                })));
    }
}
