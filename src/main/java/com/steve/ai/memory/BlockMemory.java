package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockMemory {
    private static final int DEFAULT_MAX_ENTRIES = 4000;

    private final SteveEntity steve;
    private final int maxAgeTicks;
    private final int maxEntries;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final ArrayDeque<SeenKey> order = new ArrayDeque<>();

    public BlockMemory(SteveEntity steve, int maxAgeTicks) {
        this(steve, maxAgeTicks, DEFAULT_MAX_ENTRIES);
    }

    public BlockMemory(SteveEntity steve, int maxAgeTicks, int maxEntries) {
        this.steve = steve;
        this.maxAgeTicks = maxAgeTicks;
        this.maxEntries = maxEntries;
    }

    public void record(BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return;
        }
        long now = steve.level().getGameTime();
        long key = pos.asLong();
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(pos.immutable());
            entries.put(key, entry);
        } else if (entry.blockId != null
            && entry.blockId.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
            return;
        } else {
            entry.pos = pos.immutable();
        }
        entry.blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        entry.lastSeenTick = now;
        order.addLast(new SeenKey(key, now));
        prune(now);
    }

    @Nullable
    public BlockPos findNearest(Block block, BlockPos origin, int maxDistance) {
        long now = steve.level().getGameTime();
        String targetId = BuiltInRegistries.BLOCK.getKey(block).toString();
        double bestDist = Double.MAX_VALUE;
        BlockPos bestPos = null;

        for (Entry entry : entries.values()) {
            if (!targetId.equals(entry.blockId)) {
                continue;
            }
            if (now - entry.lastSeenTick > maxAgeTicks) {
                continue;
            }
            double dist = origin.distSqr(entry.pos);
            if (maxDistance > 0 && dist > (double) maxDistance * (double) maxDistance) {
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = entry.pos;
            }
        }

        return bestPos;
    }

    public List<BlockPos> findNearestMatches(Block block, BlockPos origin, int maxDistance, int limit) {
        long now = steve.level().getGameTime();
        String targetId = BuiltInRegistries.BLOCK.getKey(block).toString();
        List<BlockPos> matches = new ArrayList<>();

        for (Entry entry : entries.values()) {
            if (!targetId.equals(entry.blockId)) {
                continue;
            }
            if (now - entry.lastSeenTick > maxAgeTicks) {
                continue;
            }
            double dist = origin.distSqr(entry.pos);
            if (maxDistance > 0 && dist > (double) maxDistance * (double) maxDistance) {
                continue;
            }
            matches.add(entry.pos);
        }

        matches.sort(Comparator.comparingDouble(origin::distSqr));
        if (matches.size() > limit) {
            return new ArrayList<>(matches.subList(0, limit));
        }
        return matches;
    }

    private void prune(long now) {
        while (!order.isEmpty()) {
            SeenKey seen = order.peekFirst();
            Entry entry = entries.get(seen.key);
            if (entry == null || entry.lastSeenTick != seen.tick) {
                order.removeFirst();
                continue;
            }
            if (now - entry.lastSeenTick > maxAgeTicks || entries.size() > maxEntries) {
                entries.remove(seen.key);
                order.removeFirst();
                continue;
            }
            break;
        }
    }

    private static final class Entry {
        private BlockPos pos;
        private String blockId;
        private long lastSeenTick;

        private Entry(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final class SeenKey {
        private final long key;
        private final long tick;

        private SeenKey(long key, long tick) {
            this.key = key;
            this.tick = tick;
        }
    }
}
