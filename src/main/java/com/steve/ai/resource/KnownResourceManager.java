package com.steve.ai.resource;

import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Core source manager for known non-world resources (currently chest memory).
 *
 * Keeps chest-memory sourcing logic centralized so actions can reuse one policy.
 */
public final class KnownResourceManager {
    public static final int DEFAULT_CHEST_RADIUS = 50;

    private KnownResourceManager() {
    }

    public record GatherSourcingPlan(List<Task> chestRetrieveTasks, Map<String, Integer> remainingGatherRequirements) {
    }

    public static int configuredChestRadius() {
        try {
            return Math.max(1, SteveConfig.CHEST_SOURCE_RADIUS.get());
        } catch (Exception ignored) {
            return DEFAULT_CHEST_RADIUS;
        }
    }

    public static GatherSourcingPlan splitGatherRequirementsWithChestMemory(
        SteveEntity steve,
        Map<String, Integer> gatherRequirements,
        int chestRadius
    ) {
        if (steve == null || gatherRequirements == null || gatherRequirements.isEmpty()) {
            return new GatherSourcingPlan(List.of(), Map.of());
        }

        int radius = Math.max(1, chestRadius);
        steve.scanNearbyChests(radius);

        List<Task> chestTasks = new ArrayList<>();
        Map<String, Integer> remaining = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> req : gatherRequirements.entrySet()) {
            String itemId = normalizeItemId(req.getKey());
            int needed = Math.max(0, req.getValue());
            if (itemId == null || needed <= 0) {
                continue;
            }

            int knownInChests = steve.getKnownChestItemCount(itemId, radius);
            int fromChest = Math.min(needed, Math.max(0, knownInChests));
            int toGather = Math.max(0, needed - fromChest);

            if (fromChest > 0) {
                chestTasks.add(new Task("retrieve_chest", Map.of(
                    "item", pathOf(itemId),
                    "quantity", fromChest,
                    "radius", radius,
                    "fallback_to_gather", true
                )));
            }
            if (toGather > 0) {
                remaining.put(itemId, toGather);
            }
        }

        return new GatherSourcingPlan(List.copyOf(chestTasks), Map.copyOf(remaining));
    }

    public static int knownChestItemCount(SteveEntity steve, String itemId, int chestRadius, boolean refreshScan) {
        if (steve == null) {
            return 0;
        }
        String normalized = normalizeItemId(itemId);
        if (normalized == null) {
            return 0;
        }
        int radius = Math.max(1, chestRadius);
        if (refreshScan) {
            steve.scanNearbyChests(radius);
        }
        return steve.getKnownChestItemCount(normalized, radius);
    }

    private static String normalizeItemId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String pathOf(String namespacedId) {
        int idx = namespacedId.indexOf(':');
        if (idx < 0 || idx >= namespacedId.length() - 1) {
            return namespacedId;
        }
        return namespacedId.substring(idx + 1);
    }
}
