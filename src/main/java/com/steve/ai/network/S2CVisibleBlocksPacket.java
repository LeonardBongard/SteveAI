package com.steve.ai.network;

import com.steve.ai.client.SteveDebugBlocksData;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CVisibleBlocksPacket {
    private final int steveEntityId;
    private final List<VisibleBlockEntry> entries;

    public S2CVisibleBlocksPacket(int steveEntityId, List<VisibleBlockEntry> entries) {
        this.steveEntityId = steveEntityId;
        this.entries = entries;
    }

    public static void encode(S2CVisibleBlocksPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.steveEntityId);
        buffer.writeVarInt(packet.entries.size());
        for (VisibleBlockEntry entry : packet.entries) {
            buffer.writeUtf(entry.blockId(), 256);
            buffer.writeBlockPos(entry.position());
            buffer.writeFloat(entry.distance());
            buffer.writeVarInt(entry.lastSeenTick());
        }
    }

    public static S2CVisibleBlocksPacket decode(FriendlyByteBuf buffer) {
        int steveEntityId = buffer.readVarInt();
        int size = buffer.readVarInt();
        List<VisibleBlockEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String blockId = buffer.readUtf(256);
            BlockPos pos = buffer.readBlockPos();
            float distance = buffer.readFloat();
            int lastSeenTick = buffer.readVarInt();
            entries.add(new VisibleBlockEntry(blockId, pos, distance, lastSeenTick));
        }
        return new S2CVisibleBlocksPacket(steveEntityId, entries);
    }

    public static void handle(S2CVisibleBlocksPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            SteveDebugBlocksData.updateVisibleBlocks(packet.steveEntityId, packet.entries)
        );
        context.setPacketHandled(true);
    }
}
