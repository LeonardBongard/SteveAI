package com.steve.ai.network;

import com.steve.ai.SteveMod;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class SteveNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(Identifier.fromNamespaceAndPath(SteveMod.MODID, "main"))
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .simpleChannel();

    private static int packetId = 0;

    private SteveNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(SteveMessagePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
            .encoder(SteveMessagePacket::encode)
            .decoder(SteveMessagePacket::decode)
            .consumerMainThread(SteveMessagePacket::handle)
            .add();
    }

    public static void sendToPlayer(ServerPlayer player, String steveName, String message) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SteveMessagePacket(steveName, message));
    }

    private static int nextId() {
        return packetId++;
    }
}
