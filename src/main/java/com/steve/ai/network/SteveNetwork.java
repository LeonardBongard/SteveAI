package com.steve.ai.network;

import com.steve.ai.SteveMod;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.network.NetworkDirection;
import net.minecraft.server.level.ServerPlayer;

public final class SteveNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final Identifier CHANNEL_NAME = Identifier.fromNamespaceAndPath(SteveMod.MODID, "main");
    private static final SimpleChannel CHANNEL = ChannelBuilder
        .named(CHANNEL_NAME)
        .networkProtocolVersion(PROTOCOL_VERSION)
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel();

    private static int packetId = 0;

    private SteveNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(VisibleBlocksPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(VisibleBlocksPacket::encode)
            .decoder(VisibleBlocksPacket::decode)
            .consumerMainThread(VisibleBlocksPacket::handle)
            .add();
        CHANNEL.messageBuilder(DebugUiStatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(DebugUiStatePacket::encode)
            .decoder(DebugUiStatePacket::decode)
            .consumerMainThread(DebugUiStatePacket::handle)
            .add();
    }

    public static void sendToPlayer(ServerPlayer player, VisibleBlocksPacket packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(() -> player));
    }

    public static void sendDebugUiState(boolean enabled) {
        CHANNEL.sendToServer(new DebugUiStatePacket(enabled));
    }

}
