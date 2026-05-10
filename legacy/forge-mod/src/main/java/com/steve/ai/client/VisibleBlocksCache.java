package com.steve.ai.client;

import com.steve.ai.network.VisibleBlocksPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VisibleBlocksCache {
    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private VisibleBlocksCache() {
    }

    public static void updateSnapshot(VisibleBlocksPacket packet) {
        SNAPSHOTS.put(packet.steveId(), new Snapshot(packet.steveName(), packet.blocks(), System.currentTimeMillis()));
    }

    public static Snapshot getSnapshot(UUID steveId) {
        return SNAPSHOTS.get(steveId);
    }

    public static void clearAll() {
        SNAPSHOTS.clear();
    }

    public record Snapshot(String steveName, List<VisibleBlocksPacket.BlockEntry> blocks, long updatedAtMillis) {
    }
}
