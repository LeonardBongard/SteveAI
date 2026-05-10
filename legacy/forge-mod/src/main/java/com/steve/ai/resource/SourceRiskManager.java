package com.steve.ai.resource;

import com.steve.ai.config.SteveConfig;

/**
 * Central risk/utility evaluator for known resource sources (e.g., chest memory).
 */
public final class SourceRiskManager {
    private static final long DEFAULT_STALE_TICKS = 12_000L; // 10 minutes
    private static final long DEFAULT_VERY_STALE_TICKS = 36_000L; // 30 minutes

    private SourceRiskManager() {
    }

    public enum Urgency {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }

    public record Decision(boolean accept, double score, String reason) {
        public static Decision reject(String reason, double score) {
            return new Decision(false, score, reason);
        }

        public static Decision accept(String reason, double score) {
            return new Decision(true, score, reason);
        }
    }

    public static Decision evaluateChestCandidate(
        int availableCount,
        double distanceBlocks,
        long ageTicks,
        int neededCount,
        int radius,
        Urgency urgency
    ) {
        int available = Math.max(0, availableCount);
        int need = Math.max(1, neededCount);
        int safeRadius = Math.max(1, radius);
        Urgency safeUrgency = urgency == null ? Urgency.NORMAL : urgency;
        long staleTicks = configuredStaleTicks();
        long veryStaleTicks = configuredVeryStaleTicks(staleTicks);

        if (available <= 0) {
            return Decision.reject("no-available-items", -100.0);
        }
        if (distanceBlocks > safeRadius) {
            return Decision.reject("outside-radius", -100.0);
        }

        double maxDistance = Math.min(safeRadius, maxDistanceByUrgency(safeUrgency));
        if (distanceBlocks > maxDistance) {
            return Decision.reject("too-far-for-urgency", -50.0 - distanceBlocks);
        }

        if (ageTicks > veryStaleTicks && available < need * 2) {
            return Decision.reject("memory-too-stale", -30.0);
        }

        double quantityRatio = (double) available / (double) need;
        double distancePenalty = distanceBlocks / 8.0;
        double stalenessPenalty = ageTicks > staleTicks ? 2.0 : 0.0;
        if (ageTicks > veryStaleTicks) {
            stalenessPenalty += 2.0;
        }
        double urgencyPenalty = switch (safeUrgency) {
            case LOW -> 0.0;
            case NORMAL -> 0.5;
            case HIGH -> 1.5;
            case CRITICAL -> 3.0;
        };
        double score = (quantityRatio * 8.0) - distancePenalty - stalenessPenalty - urgencyPenalty;

        if (score < minimumScoreByUrgency(safeUrgency)) {
            return Decision.reject("low-value-vs-distance", score);
        }
        return Decision.accept("accepted", score);
    }

    private static double maxDistanceByUrgency(Urgency urgency) {
        return switch (urgency) {
            case CRITICAL -> configuredCriticalDistance();
            case HIGH -> configuredHighDistance();
            case NORMAL -> 50.0;
            case LOW -> 64.0;
        };
    }

    private static double minimumScoreByUrgency(Urgency urgency) {
        return switch (urgency) {
            case CRITICAL -> configuredCriticalMinScore();
            case HIGH -> 0.5;
            case NORMAL -> configuredNormalMinScore();
            case LOW -> -0.25;
        };
    }

    private static long configuredStaleTicks() {
        try {
            return Math.max(1L, SteveConfig.CHEST_SOURCE_STALE_TICKS.get());
        } catch (Exception ignored) {
            return DEFAULT_STALE_TICKS;
        }
    }

    private static long configuredVeryStaleTicks(long staleTicks) {
        try {
            long configured = Math.max(1L, SteveConfig.CHEST_SOURCE_VERY_STALE_TICKS.get());
            return Math.max(configured, staleTicks + 1L);
        } catch (Exception ignored) {
            return Math.max(DEFAULT_VERY_STALE_TICKS, staleTicks + 1L);
        }
    }

    private static double configuredCriticalDistance() {
        try {
            return Math.max(1.0, SteveConfig.CHEST_SOURCE_MAX_DISTANCE_CRITICAL.get());
        } catch (Exception ignored) {
            return 16.0;
        }
    }

    private static double configuredHighDistance() {
        try {
            return Math.max(1.0, SteveConfig.CHEST_SOURCE_MAX_DISTANCE_HIGH.get());
        } catch (Exception ignored) {
            return 24.0;
        }
    }

    private static double configuredNormalMinScore() {
        try {
            return SteveConfig.CHEST_SOURCE_MIN_SCORE_NORMAL.get();
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static double configuredCriticalMinScore() {
        try {
            return SteveConfig.CHEST_SOURCE_MIN_SCORE_CRITICAL.get();
        } catch (Exception ignored) {
            return 0.75;
        }
    }
}
