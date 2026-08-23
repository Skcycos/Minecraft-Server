package com.tanrunn.tcth.impl.shadow;

import io.netty.buffer.ByteBuf;

import java.util.function.Consumer;

import com.tanrunn.tcth.TCTHIntegration;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-authoritative shadow-thief cooldown state sent to the client HUD. */
public record ShadowCooldownPayload(int remainingTicks) implements CustomPacketPayload {

    private static volatile Consumer<ShadowCooldownPayload> clientHandler = payload -> {
    };

    public static final Type<ShadowCooldownPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TCTHIntegration.MODID, "shadow_cooldown"));

    public static final StreamCodec<ByteBuf, ShadowCooldownPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShadowCooldownPayload::remainingTicks,
            ShadowCooldownPayload::new);

    public ShadowCooldownPayload {
        remainingTicks = Math.max(0, remainingTicks);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, ShadowCooldownPayload::handle);
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        long remaining = ShadowCooldownTracker.SHARED.remainingCooldownTicks(player.getUUID());
        int ticks = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, remaining));
        PacketDistributor.sendToPlayer(player, new ShadowCooldownPayload(ticks));
    }

    /** Installs the client-only HUD sink without loading client classes on a server. */
    public static void setClientHandler(Consumer<ShadowCooldownPayload> handler) {
        clientHandler = handler == null ? payload -> {
        } : handler;
    }

    private static void handle(ShadowCooldownPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> clientHandler.accept(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
