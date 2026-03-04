package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedHashMap;

public class SteveMemory {
    private final SteveEntity steve;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private final Deque<ViewSample> viewSamples;
    private final PerceptionCache perceptionCache;
    private List<VisibleBlockEntry> visibleBlocks;
    private final Map<BlockPos, ChestMemoryEntry> knownChests;
    private final Map<Long, Map<String, EpisodicObservation>> episodicByChunk;
    private final Map<String, SemanticStat> semanticStats;
    private final Map<String, Set<Long>> semanticSeenChunks;
    private static final int MAX_RECENT_ACTIONS = 20;
    private static final int MAX_VIEW_SAMPLES = 160;
    private static final int MAX_EPISODIC_CHUNKS = 256;
    private static final int MAX_EPISODIC_PER_CHUNK = 20;
    private static final String[] YAW_LABELS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] PITCH_LABELS = {"Up", "Level", "Down"};

    public SteveMemory(SteveEntity steve) {
        this.steve = steve;
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();
        this.viewSamples = new ArrayDeque<>();
        this.perceptionCache = new PerceptionCache();
        this.visibleBlocks = new ArrayList<>();
        this.knownChests = new HashMap<>();
        this.episodicByChunk = new LinkedHashMap<>();
        this.semanticStats = new HashMap<>();
        this.semanticSeenChunks = new HashMap<>();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void addAction(String action) {
        recentActions.addLast(action);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    public List<String> getRecentActions(int count) {
        int size = Math.min(count, recentActions.size());
        List<String> result = new ArrayList<>();
        
        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }
        
        return result;
    }

    public List<VisibleBlockEntry> getVisibleBlocks() {
        return Collections.unmodifiableList(visibleBlocks);
    }

    public void setVisibleBlocks(List<VisibleBlockEntry> visibleBlocks) {
        this.visibleBlocks = new ArrayList<>(visibleBlocks);
        ingestVisibleSnapshot(this.visibleBlocks);
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }

    public void recordViewSample(float yaw, float pitch, long timestamp) {
        viewSamples.addLast(new ViewSample(normalizeYaw(yaw), clampPitch(pitch), timestamp));
        while (viewSamples.size() > MAX_VIEW_SAMPLES) {
            viewSamples.removeFirst();
        }
    }

    public String getViewCoverageSummary() {
        if (viewSamples.isEmpty()) {
            return "View coverage: no samples";
        }
        int[] yawCounts = new int[YAW_LABELS.length];
        int[] pitchCounts = new int[PITCH_LABELS.length];
        for (ViewSample sample : viewSamples) {
            yawCounts[yawBucket(sample.yaw)]++;
            pitchCounts[pitchBucket(sample.pitch)]++;
        }
        StringBuilder yawSummary = new StringBuilder("Yaw ");
        appendCounts(yawSummary, YAW_LABELS, yawCounts);
        StringBuilder pitchSummary = new StringBuilder("Pitch ");
        appendCounts(pitchSummary, PITCH_LABELS, pitchCounts);
        return String.format(Locale.ROOT, "View coverage (%d): %s | %s", viewSamples.size(), yawSummary, pitchSummary);
    }

    public List<String> getLeastSeenDirections(int count) {
        if (viewSamples.isEmpty() || count <= 0) {
            return List.of();
        }
        int yawBuckets = YAW_LABELS.length;
        int pitchBuckets = PITCH_LABELS.length;
        int[] combined = new int[yawBuckets * pitchBuckets];
        for (ViewSample sample : viewSamples) {
            int index = pitchBucket(sample.pitch) * yawBuckets + yawBucket(sample.yaw);
            combined[index]++;
        }

        List<DirectionCoverage> coverage = new ArrayList<>(combined.length);
        for (int pitchIndex = 0; pitchIndex < pitchBuckets; pitchIndex++) {
            for (int yawIndex = 0; yawIndex < yawBuckets; yawIndex++) {
                int index = pitchIndex * yawBuckets + yawIndex;
                String label = YAW_LABELS[yawIndex] + "-" + PITCH_LABELS[pitchIndex];
                coverage.add(new DirectionCoverage(label, combined[index]));
            }
        }

        coverage.sort(Comparator.comparingInt(DirectionCoverage::count));
        int limit = Math.min(count, coverage.size());
        List<String> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(coverage.get(i).label());
        }
        return result;
    }

    public PerceptionCache getPerceptionCache() {
        return perceptionCache;
    }

    public void rememberChestContents(BlockPos pos, Map<String, Integer> contents, long tick) {
        if (pos == null) {
            return;
        }
        Map<String, Integer> sanitized = new HashMap<>();
        if (contents != null) {
            for (Map.Entry<String, Integer> entry : contents.entrySet()) {
                String itemId = entry.getKey();
                Integer count = entry.getValue();
                if (itemId == null || itemId.isBlank() || count == null || count <= 0) {
                    continue;
                }
                sanitized.put(itemId, count);
            }
        }
        knownChests.put(pos.immutable(), new ChestMemoryEntry(pos.immutable(), sanitized, tick));
    }

    public List<ChestMemoryEntry> getKnownChests() {
        return List.copyOf(knownChests.values());
    }

    public EpisodicTarget findBestEpisodicTarget(
        Set<String> candidateBlockIds,
        BlockPos origin,
        long nowTick,
        int maxDistance
    ) {
        if (candidateBlockIds == null || candidateBlockIds.isEmpty() || origin == null || maxDistance <= 0) {
            return null;
        }
        double maxDistSqr = (double) maxDistance * maxDistance;
        EpisodicTarget best = null;

        for (Map<String, EpisodicObservation> chunkMap : episodicByChunk.values()) {
            for (EpisodicObservation observation : chunkMap.values()) {
                if (!candidateBlockIds.contains(observation.blockId())) {
                    continue;
                }
                double distSqr = origin.distSqr(observation.position());
                if (distSqr > maxDistSqr) {
                    continue;
                }
                long age = Math.max(0, nowTick - observation.lastSeenTick());
                double freshness = Math.max(0.08, 1.0 - (age / 24000.0));
                double distancePenalty = 1.0 / (1.0 + (Math.sqrt(distSqr) / 24.0));
                double confidence = Math.max(0.05, observation.confidence());
                double score = confidence * freshness * distancePenalty;
                if (best == null || score > best.score()) {
                    best = new EpisodicTarget(
                        observation.blockId(),
                        observation.position(),
                        (float) score,
                        age,
                        observation.confidence(),
                        observation.seenCount()
                    );
                }
            }
        }
        return best;
    }

    public List<BlockPos> getEpisodicPositions(int maxPositions) {
        if (maxPositions <= 0 || episodicByChunk.isEmpty()) {
            return List.of();
        }
        // Multi-chunk balanced export:
        // take top observations per chunk and round-robin so one hot chunk does not crowd out others.
        List<List<EpisodicObservation>> perChunk = new ArrayList<>();
        for (Map<String, EpisodicObservation> chunkMap : episodicByChunk.values()) {
            List<EpisodicObservation> rows = new ArrayList<>(chunkMap.values());
            rows.sort(Comparator
                .comparingLong(EpisodicObservation::lastSeenTick).reversed()
                .thenComparing(EpisodicObservation::confidence, Comparator.reverseOrder())
                .thenComparingInt(EpisodicObservation::seenCount).reversed());
            perChunk.add(rows);
        }
        perChunk.sort((a, b) -> Long.compare(newestTickOfList(b), newestTickOfList(a)));

        List<BlockPos> out = new ArrayList<>(Math.min(maxPositions, getEpisodicObservationCount()));
        int depth = 0;
        while (out.size() < maxPositions) {
            boolean progressed = false;
            for (List<EpisodicObservation> chunkRows : perChunk) {
                if (depth < chunkRows.size()) {
                    out.add(chunkRows.get(depth).position());
                    progressed = true;
                    if (out.size() >= maxPositions) {
                        break;
                    }
                }
            }
            if (!progressed) {
                break;
            }
            depth++;
        }
        return out;
    }

    public int getEpisodicObservationCount() {
        int total = 0;
        for (Map<String, EpisodicObservation> chunkMap : episodicByChunk.values()) {
            total += chunkMap.size();
        }
        return total;
    }

    public String getSemanticSummary(int maxItems) {
        if (semanticStats.isEmpty()) {
            return "No semantic memory";
        }
        List<Map.Entry<String, SemanticStat>> rows = new ArrayList<>(semanticStats.entrySet());
        rows.sort(Comparator
            .comparingInt((Map.Entry<String, SemanticStat> e) -> e.getValue().totalSeen()).reversed()
            .thenComparingInt(e -> e.getValue().chunkSeenCount()).reversed());

        StringBuilder sb = new StringBuilder();
        int shown = Math.min(maxItems, rows.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Map.Entry<String, SemanticStat> row = rows.get(i);
            String shortId = shortId(row.getKey());
            sb.append(shortId)
                .append(" seen=").append(row.getValue().totalSeen())
                .append(" chunks=").append(row.getValue().chunkSeenCount());
        }
        if (rows.size() > shown) {
            sb.append(" ...");
        }
        return sb.toString();
    }

    public List<VisibleBlock> getVisibleBlocksSnapshot() {
        return perceptionCache.getVisibleBlocks();
    }

    public void saveToNBT(ValueOutput output) {
        output.putString("CurrentGoal", currentGoal == null ? "" : currentGoal);

        ValueOutput.TypedOutputList<String> actionsList = output.list("RecentActions", Codec.STRING);
        for (String action : recentActions) {
            actionsList.add(action);
        }
    }

    public void loadFromNBT(ValueInput input) {
        currentGoal = input.getStringOr("CurrentGoal", currentGoal);

        input.list("RecentActions", Codec.STRING).ifPresent(list -> {
            recentActions.clear();
            list.stream().forEach(recentActions::add);
        });
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized < 0) {
            normalized += 360.0f;
        }
        return normalized;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    private static int yawBucket(float yaw) {
        int bucket = (int) Math.floor((yaw + 22.5f) / 45.0f) % YAW_LABELS.length;
        if (bucket < 0) {
            bucket += YAW_LABELS.length;
        }
        return bucket;
    }

    private static int pitchBucket(float pitch) {
        if (pitch <= -30.0f) {
            return 0;
        }
        if (pitch >= 30.0f) {
            return 2;
        }
        return 1;
    }

    private static void appendCounts(StringBuilder builder, String[] labels, int[] counts) {
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(labels[i]).append('=').append(counts[i]);
        }
    }

    private record ViewSample(float yaw, float pitch, long timestamp) {}

    private record DirectionCoverage(String label, int count) {}

    public record ChestMemoryEntry(BlockPos position, Map<String, Integer> items, long lastSeenTick) {}

    public record EpisodicTarget(
        String blockId,
        BlockPos position,
        float score,
        long ageTicks,
        float confidence,
        int seenCount
    ) {}

    private record EpisodicObservation(
        String blockId,
        BlockPos position,
        long lastSeenTick,
        int seenCount,
        float confidence
    ) {}

    private record SemanticStat(
        int totalSeen,
        int chunkSeenCount,
        long lastSeenTick
    ) {}

    private void ingestVisibleSnapshot(List<VisibleBlockEntry> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        for (VisibleBlockEntry entry : snapshot) {
            if (entry == null || entry.position() == null || entry.blockId() == null || entry.blockId().isBlank()) {
                continue;
            }
            long chunkKey = chunkKey(entry.position());
            Map<String, EpisodicObservation> chunkMap = episodicByChunk.computeIfAbsent(chunkKey, k -> new HashMap<>());
            EpisodicObservation prev = chunkMap.get(entry.blockId());
            if (prev == null) {
                chunkMap.put(
                    entry.blockId(),
                    new EpisodicObservation(entry.blockId(), entry.position().immutable(), entry.lastSeenTick(), 1, 0.35F)
                );
            } else {
                float confidence = Math.min(1.0F, prev.confidence() + 0.08F);
                chunkMap.put(
                    entry.blockId(),
                    new EpisodicObservation(
                        entry.blockId(),
                        entry.position().immutable(),
                        entry.lastSeenTick(),
                        prev.seenCount() + 1,
                        confidence
                    )
                );
            }

            Set<Long> blockChunks = semanticSeenChunks.computeIfAbsent(entry.blockId(), k -> new java.util.HashSet<>());
            boolean firstChunkVisitForBlock = blockChunks.add(chunkKey);
            SemanticStat semantic = semanticStats.get(entry.blockId());
            if (semantic == null) {
                semanticStats.put(entry.blockId(), new SemanticStat(1, 1, entry.lastSeenTick()));
            } else {
                int chunkSeen = semantic.chunkSeenCount() + (firstChunkVisitForBlock ? 1 : 0);
                semanticStats.put(
                    entry.blockId(),
                    new SemanticStat(semantic.totalSeen() + 1, chunkSeen, entry.lastSeenTick())
                );
            }
        }

        trimEpisodicMemory();
    }

    private void trimEpisodicMemory() {
        if (episodicByChunk.isEmpty()) {
            return;
        }
        for (Map<String, EpisodicObservation> chunkMap : episodicByChunk.values()) {
            if (chunkMap.size() <= MAX_EPISODIC_PER_CHUNK) {
                continue;
            }
            List<Map.Entry<String, EpisodicObservation>> rows = new ArrayList<>(chunkMap.entrySet());
            rows.sort(Comparator
                .comparingLong((Map.Entry<String, EpisodicObservation> e) -> e.getValue().lastSeenTick()).reversed()
                .thenComparing(e -> e.getValue().confidence(), Comparator.reverseOrder()));
            chunkMap.clear();
            for (int i = 0; i < Math.min(MAX_EPISODIC_PER_CHUNK, rows.size()); i++) {
                Map.Entry<String, EpisodicObservation> row = rows.get(i);
                chunkMap.put(row.getKey(), row.getValue());
            }
        }

        if (episodicByChunk.size() > MAX_EPISODIC_CHUNKS) {
            List<Map.Entry<Long, Map<String, EpisodicObservation>>> chunks = new ArrayList<>(episodicByChunk.entrySet());
            chunks.sort(Comparator.comparingLong(e -> newestTickInChunk(e.getValue())));
            int toRemove = episodicByChunk.size() - MAX_EPISODIC_CHUNKS;
            for (int i = 0; i < toRemove; i++) {
                episodicByChunk.remove(chunks.get(i).getKey());
            }
        }
    }

    private long newestTickInChunk(Map<String, EpisodicObservation> chunk) {
        long newest = Long.MIN_VALUE;
        for (EpisodicObservation obs : chunk.values()) {
            newest = Math.max(newest, obs.lastSeenTick());
        }
        return newest == Long.MIN_VALUE ? 0L : newest;
    }

    private long newestTickOfList(List<EpisodicObservation> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        long newest = 0L;
        for (EpisodicObservation obs : rows) {
            newest = Math.max(newest, obs.lastSeenTick());
        }
        return newest;
    }

    private long chunkKey(BlockPos pos) {
        long x = pos.getX() >> 4;
        long z = pos.getZ() >> 4;
        return (x & 0xffffffffL) << 32 | (z & 0xffffffffL);
    }

    private String shortId(String namespaced) {
        int idx = namespaced.indexOf(':');
        return idx >= 0 ? namespaced.substring(idx + 1) : namespaced;
    }
}
