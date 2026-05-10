package com.steve.ai.network;

import net.minecraft.network.FriendlyByteBuf;

public record DebugUiStatePacket(boolean enabled) {

    public static void encode(DebugUiStatePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
    }

    public static DebugUiStatePacket decode(FriendlyByteBuf buf) {
        return new DebugUiStatePacket(buf.readBoolean());
    }

    public static void handle(DebugUiStatePacket packet) {
        // Transport callback intentionally deferred to SteveNetwork wiring.
    }
}
