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
        // A PlayerVaults-like /pv N: a double chest of its own per number, kept while the game runs
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("pv").then(Commands.argument("number",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 99)).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    int number = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "number");
                    net.minecraft.world.SimpleContainer vault = VAULTS.computeIfAbsent(number, n -> new net.minecraft.world.SimpleContainer(54));
                    player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                            (id, inventory, p) -> net.minecraft.world.inventory.ChestMenu.sixRows(id, inventory, vault),
                            Component.literal("Vault #" + number)));
                    return 1;
                }))));
    }

    /** The vaults of /pv (see above), by number. */
    public static final java.util.Map<Integer, net.minecraft.world.SimpleContainer> VAULTS = new java.util.HashMap<>();
}
