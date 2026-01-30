package com.steve.ai.network;

import com.steve.ai.client.SteveGUI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SteveMessagePacket(String steveName, String message) {

    public static void encode(SteveMessagePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.steveName);
        buffer.writeUtf(packet.message);
    }

    public static SteveMessagePacket decode(FriendlyByteBuf buffer) {
        String steveName = buffer.readUtf();
        String message = buffer.readUtf();
        return new SteveMessagePacket(steveName, message);
    }

    public static void handle(SteveMessagePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> SteveGUI.addSteveMessage(packet.steveName, packet.message));
        context.setPacketHandled(true);
    }
}
