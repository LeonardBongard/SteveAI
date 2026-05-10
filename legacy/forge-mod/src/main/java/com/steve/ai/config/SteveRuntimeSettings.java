package com.steve.ai.config;

import com.steve.ai.SteveMod;

public final class SteveRuntimeSettings {
    private static int visibleScanRadius = 24;
    private static int visibleMaxEntries = 3840;
    private static int syncedWorkingPositions = 1024;
    private static int syncedEpisodicPositions = 1024;
    private static float memoryMarkerScale = 1.0F;

    private SteveRuntimeSettings() {}

    public static int getVisibleScanRadius() {
        return visibleScanRadius;
    }

    public static void setVisibleScanRadius(int value) {
        visibleScanRadius = clamp(value, 2, 24);
        SteveMod.LOGGER.info("Runtime settings updated: visibleScanRadius={}", visibleScanRadius);
    }

    public static int getVisibleMaxEntries() {
        return visibleMaxEntries;
    }

    public static void setVisibleMaxEntries(int value) {
        visibleMaxEntries = clamp(value, 120, 4096);
        SteveMod.LOGGER.info("Runtime settings updated: visibleMaxEntries={}", visibleMaxEntries);
    }

    public static int getSyncedWorkingPositions() {
        return syncedWorkingPositions;
    }

    public static void setSyncedWorkingPositions(int value) {
        syncedWorkingPositions = clamp(value, 32, 1024);
        SteveMod.LOGGER.info("Runtime settings updated: syncedWorkingPositions={}", syncedWorkingPositions);
    }

    public static int getSyncedEpisodicPositions() {
        return syncedEpisodicPositions;
    }

    public static void setSyncedEpisodicPositions(int value) {
        syncedEpisodicPositions = clamp(value, 32, 1024);
        SteveMod.LOGGER.info("Runtime settings updated: syncedEpisodicPositions={}", syncedEpisodicPositions);
    }

    public static float getMemoryMarkerScale() {
        return memoryMarkerScale;
    }

    public static void setMemoryMarkerScale(float value) {
        memoryMarkerScale = clamp(value, 0.25F, 4.0F);
        SteveMod.LOGGER.info("Runtime settings updated: memoryMarkerScale={}", memoryMarkerScale);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
