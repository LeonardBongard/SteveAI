package com.steve.ai.network;

import com.steve.ai.SteveMod;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.List;

public final class SteveNetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = ChannelBuilder
        .named(Identifier.fromNamespaceAndPath(SteveMod.MODID, "main"))
        .networkProtocolVersion(PROTOCOL_VERSION)
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel();

    private static int packetId = 0;

    private SteveNetworkHandler() {
    }

    public static void register() {
        CHANNEL.messageBuilder(S2CVisibleBlocksPacket.class, nextPacketId(), NetworkDirection.PLAY_TO_CLIENT)
            .encoder(S2CVisibleBlocksPacket::encode)
            .decoder(S2CVisibleBlocksPacket::decode)
            .consumerMainThread(S2CVisibleBlocksPacket::handle)
            .add();
    }

    public static void sendVisibleBlocks(ServerLevel level, int steveEntityId, List<VisibleBlockEntry> entries) {
        if (level == null || level.players().isEmpty()) {
            return;
        }
        S2CVisibleBlocksPacket packet = new S2CVisibleBlocksPacket(steveEntityId, entries);
        for (ServerPlayer player : level.players()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    private static int nextPacketId() {
        return packetId++;
    }
}
