package no.sindrens.printeraddon.discord;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import no.sindrens.printeraddon.util.Chat;

/** /printeraddon link|unlink|discord|messages|suggestions|vote: the Discord link from chat. */
public final class LinkCommands {
    private LinkCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommands.literal("printeraddon")
                .then(ClientCommands.literal("link")
                        .then(ClientCommands.argument("code", StringArgumentType.greedyString())
                                .executes(c -> {
                                    DiscordLink.link(StringArgumentType.getString(c, "code"));
                                    return 1;
                                }))
                        .executes(c -> {
                            Chat.info("Type §b/link§r on the Printer Addon Discord, then §b/printeraddon link PRINT-XXXX§r (or J+L).");
                            return 1;
                        }))
                .then(ClientCommands.literal("test")
                        .then(ClientCommands.literal("all").executes(c -> {
                            no.sindrens.printeraddon.gui.ConfigGui.runAllTests(net.minecraft.client.Minecraft.getInstance());
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(c -> {
                            no.sindrens.printeraddon.build.TestRunner.INSTANCE.stop(net.minecraft.client.Minecraft.getInstance(), "stopped by you");
                            return 1;
                        }))
                        .executes(c -> {
                            Chat.info("/printeraddon test all: runs every test build (singleplayer, cheats on). Or J+C, Tests tab.");
                            return 1;
                        }))
                .then(ClientCommands.literal("why").executes(c -> {
                    Chat.info(no.sindrens.printeraddon.build.AutoBuild.whyStopped());
                    return 1;
                }))
                .then(ClientCommands.literal("report").executes(c -> {
                    no.sindrens.printeraddon.build.LogShare.reportProblem(net.minecraft.client.Minecraft.getInstance());
                    return 1;
                }))
                .then(ClientCommands.literal("unlink").executes(c -> {
                    DiscordLink.unlink();
                    return 1;
                }))
                .then(ClientCommands.literal("discord").executes(c -> {
                    DiscordLink.status();
                    return 1;
                }))
                .then(ClientCommands.literal("messages").executes(c -> {
                    var inbox = DiscordLink.inbox();
                    if (inbox.isEmpty()) {
                        Chat.info("No messages from Discord yet.");
                    } else {
                        Chat.info("§lMessages from Discord§r (newest first):");
                        inbox.forEach(Chat::info);
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("schematics")
                        .then(ClientCommands.argument("search", StringArgumentType.greedyString()).executes(c -> {
                            DiscordLink.schematics(StringArgumentType.getString(c, "search"));
                            return 1;
                        }))
                        .executes(c -> {
                            DiscordLink.schematics("");
                            return 1;
                        }))
                .then(ClientCommands.literal("schematic").then(ClientCommands.literal("get")
                        .then(ClientCommands.argument("id", StringArgumentType.word()).executes(c -> {
                            DiscordLink.downloadSchematic(StringArgumentType.getString(c, "id"));
                            return 1;
                        }))))
                .then(ClientCommands.literal("suggestions").executes(c -> {
                    DiscordLink.suggestions();
                    return 1;
                }))
                .then(ClientCommands.literal("vote")
                        .then(ClientCommands.argument("number", IntegerArgumentType.integer(1))
                                .then(ClientCommands.literal("up").executes(c -> {
                                    DiscordLink.vote(IntegerArgumentType.getInteger(c, "number"), "up");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("down").executes(c -> {
                                    DiscordLink.vote(IntegerArgumentType.getInteger(c, "number"), "down");
                                    return 1;
                                }))))
                .executes(c -> {
                    Chat.info("§lPrinter Addon + Discord§r: /printeraddon link <code> · unlink · discord · messages · schematics [search] · schematic get <id> · suggestions · vote <n> up|down");
                    return 1;
                }));
    }
}
