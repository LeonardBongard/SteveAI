package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import com.mojang.serialization.Codec;
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
import java.util.Queue;

public class SteveMemory {
    private final SteveEntity steve;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private final Deque<ViewSample> viewSamples;
    private final PerceptionCache perceptionCache;
    private List<VisibleBlockEntry> visibleBlocks;
    private static final int MAX_RECENT_ACTIONS = 20;
    private static final int MAX_VIEW_SAMPLES = 160;
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
    public PerceptionCache getPerceptionCache() {
        return perceptionCache;
    }

    public List<VisibleBlock> getVisibleBlocksSnapshot() {
        return perceptionCache.getVisibleBlocks();
    }

    public void saveToNBT(ValueOutput output) {
        output.putString("CurrentGoal", currentGoal);

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
}
