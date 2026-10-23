package no.sindrens.printeraddon.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** A /fly command like EssentialsX's and a /shop like ShopGUI+, so the test server works like the real one. */
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
        // A ShopGUI+-like /shop (see FakeShop)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("shop").executes(context -> {
                    FakeShop.openMain(context.getSource().getPlayerOrException());
                    return 1;
                })));
    }
}
