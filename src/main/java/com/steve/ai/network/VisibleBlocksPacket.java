package com.steve.ai.network;

import com.steve.ai.client.VisibleBlocksCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record VisibleBlocksPacket(UUID steveId, String steveName, List<BlockEntry> blocks) {

    public static void encode(VisibleBlocksPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.steveId);
        buf.writeUtf(packet.steveName);
        buf.writeVarInt(packet.blocks.size());
        for (BlockEntry entry : packet.blocks) {
            buf.writeUtf(entry.blockId().getNamespace());
            buf.writeUtf(entry.blockId().getPath());
            buf.writeBlockPos(entry.position());
            if (entry.metadata() != null) {
                buf.writeBoolean(true);
                buf.writeUtf(entry.metadata());
            } else {
                buf.writeBoolean(false);
            }
        }
    }

    public static VisibleBlocksPacket decode(FriendlyByteBuf buf) {
        UUID steveId = buf.readUUID();
        String steveName = buf.readUtf();
        int size = buf.readVarInt();
        List<BlockEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String namespace = buf.readUtf();
            String path = buf.readUtf();
            BlockPos pos = buf.readBlockPos();
            String metadata = null;
            if (buf.readBoolean()) {
                metadata = buf.readUtf();
            }
            entries.add(new BlockEntry(Identifier.fromNamespaceAndPath(namespace, path), pos, metadata));
        }
        return new VisibleBlocksPacket(steveId, steveName, entries);
    }

    public static void handle(VisibleBlocksPacket packet) {
        VisibleBlocksCache.updateSnapshot(packet);
    }

    public record BlockEntry(Identifier blockId, BlockPos position, @Nullable String metadata) {
    }
}
