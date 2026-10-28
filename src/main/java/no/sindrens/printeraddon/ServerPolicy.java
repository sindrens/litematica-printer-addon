package no.sindrens.printeraddon;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import no.sindrens.printeraddon.util.Chat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What the server allows (official build). A server can switch the add-on off for its players, or allow movement faster
 * than vanilla (Modrinth's rules: movement changes only with the server's opt-in), in two ways:
 * <ul>
 *   <li>a plugin message on the channel {@code printer_addon:policy} with the text {@code disable},
 *   {@code allow-fast-movement} or {@code default};</li>
 *   <li>a tag in the server's MOTD: {@code [printer_addon:disable]} or {@code [printer_addon:allow-fast-movement]}
 *   (color codes around it are fine, so it can be hidden).</li>
 * </ul>
 * Singleplayer and LAN games allow everything (no other players). Nothing here does anything in the Mythian build.
 */
public final class ServerPolicy {
    public static final String DISABLE_TAG = "[printer_addon:disable]";
    public static final String FAST_TAG = "[printer_addon:allow-fast-movement]";

    private static boolean disabled;
    private static boolean fastMovement;
    private static String source = "";

    private ServerPolicy() {
    }

    public record PolicyPayload(String value) implements CustomPacketPayload {
        public static final Type<PolicyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PrinterAddon.MOD_ID, "policy"));
        public static final StreamCodec<FriendlyByteBuf, PolicyPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.value.getBytes(StandardCharsets.UTF_8)),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new PolicyPayload(new String(bytes, StandardCharsets.UTF_8));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        try {
            PayloadTypeRegistry.clientboundPlay().register(PolicyPayload.TYPE, PolicyPayload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(PolicyPayload.TYPE, (payload, context) ->
                    context.client().execute(() -> apply(payload.value().trim().toLowerCase(Locale.ROOT), "the server's plugin message")));
        } catch (RuntimeException | LinkageError e) {
            PrinterAddon.LOGGER.warn("[Printer Addon] Server policy channel not registered: {}", e.toString());
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> onJoin(mc)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
    }

    private static void reset() {
        disabled = false;
        fastMovement = false;
        source = "";
    }

    private static void onJoin(Minecraft mc) {
        reset();
        ServerData data = mc.getCurrentServer();
        if (data == null || data.motd == null) {
            return;
        }
        String motd = net.minecraft.ChatFormatting.stripFormatting(data.motd.getString());
        if (motd == null) {
            return;
        }
        String m = motd.toLowerCase(Locale.ROOT);
        if (m.contains(DISABLE_TAG)) {
            apply("disable", "the server's MOTD");
        } else if (m.contains(FAST_TAG)) {
            apply("allow-fast-movement", "the server's MOTD");
        }
    }

    private static void apply(String value, String from) {
        if (!Edition.OFFICIAL) {
            return;
        }
        switch (value) {
            case "disable" -> {
                disabled = true;
                source = from;
                Chat.warn("This server doesn't allow Printer Addon (" + from + "): auto build and Auto break are off here.");
                Minecraft mc = Minecraft.getInstance();
                if (no.sindrens.printeraddon.build.AutoBuild.INSTANCE.isRunning()) {
                    no.sindrens.printeraddon.build.AutoBuild.INSTANCE.stop(mc, "not allowed on this server");
                }
                if (no.sindrens.printeraddon.build.AreaBreaker.INSTANCE.isRunning()) {
                    no.sindrens.printeraddon.build.AreaBreaker.INSTANCE.stop(mc, "not allowed on this server");
                }
            }
            case "allow-fast-movement" -> {
                fastMovement = true;
                source = from;
                Chat.info("This server allows faster movement than vanilla (" + from + "): the speed settings go past it here.");
            }
            case "default" -> reset();
            default -> PrinterAddon.LOGGER.info("[Printer Addon] unknown server policy \"{}\" from {}", value, from);
        }
    }

    /** The server switched the add-on off (official build only). */
    public static boolean disabled() {
        return Edition.OFFICIAL && disabled;
    }

    /**
     * Movement faster than vanilla may be used: always in the Mythian build; in the official build in singleplayer or LAN,
     * or when the server allows it.
     */
    /** In-game tests: play by the server rules in singleplayer too (movement keys), to test them. */
    public static boolean serverRulesInSingleplayer;

    public static boolean fastMovementAllowed() {
        if (!Edition.OFFICIAL) {
            return true;
        }
        if (serverRulesInSingleplayer && !fastMovement) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.hasSingleplayerServer() || fastMovement;
    }

    public static String describe() {
        if (!Edition.OFFICIAL) {
            return "Mythian build: no limits";
        }
        return disabled ? "off on this server (" + source + ")"
                : fastMovementAllowed() ? "faster movement allowed" + (source.isEmpty() ? " (singleplayer)" : " (" + source + ")")
                : "vanilla movement limits";
    }
}
