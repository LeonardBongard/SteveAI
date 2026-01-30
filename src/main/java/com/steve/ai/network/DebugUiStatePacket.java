package com.steve.ai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record DebugUiStatePacket(boolean enabled) {

    public static void encode(DebugUiStatePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
    }

    public static DebugUiStatePacket decode(FriendlyByteBuf buf) {
        return new DebugUiStatePacket(buf.readBoolean());
    }

    public static void handle(DebugUiStatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                DebugUiTracker.setSubscribed(context.getSender().getUUID(), packet.enabled);
            }
        });
        context.setPacketHandled(true);
    }
}
