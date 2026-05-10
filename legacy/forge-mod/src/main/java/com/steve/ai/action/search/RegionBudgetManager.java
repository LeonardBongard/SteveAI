package com.steve.ai.action.search;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public final class RegionBudgetManager {
    private final int noTargetBudgetTicks;
    private final int failureBudget;
    private final int nonProgressMineBudget;
    private final int exhaustedTtlTicks;
    private final Map<Long, RegionStats> statsByRegion = new HashMap<>();

    public RegionBudgetManager(int noTargetBudgetTicks, int failureBudget, int nonProgressMineBudget, int exhaustedTtlTicks) {
        this.noTargetBudgetTicks = Math.max(1, noTargetBudgetTicks);
        this.failureBudget = Math.max(1, failureBudget);
        this.nonProgressMineBudget = Math.max(1, nonProgressMineBudget);
        this.exhaustedTtlTicks = Math.max(20, exhaustedTtlTicks);
    }

    public void reset() {
        statsByRegion.clear();
    }

    public void noteNoTarget(BlockPos pos, long tick) {
        RegionStats stats = statsFor(pos);
        if (stats == null) {
            return;
        }
        if (tick > stats.lastNoTargetTick) {
            stats.lastNoTargetTick = tick;
            stats.noTargetTicks++;
        }
    }

    public void noteFailure(BlockPos pos) {
        RegionStats stats = statsFor(pos);
        if (stats == null) {
            return;
        }
        stats.failureCount++;
    }

    public void noteNonProgressMine(BlockPos pos) {
        RegionStats stats = statsFor(pos);
        if (stats == null) {
            return;
        }
        stats.nonProgressMines++;
    }

    public void noteProgress(BlockPos pos) {
        RegionStats stats = statsFor(pos);
        if (stats == null) {
            return;
        }
        stats.noTargetTicks = 0;
        stats.failureCount = 0;
        stats.nonProgressMines = 0;
    }

    public boolean shouldExhaust(BlockPos pos, long tick) {
        RegionStats stats = statsFor(pos);
        if (stats == null || isExhausted(pos, tick)) {
            return false;
        }
        return stats.noTargetTicks >= noTargetBudgetTicks
            || stats.failureCount >= failureBudget
            || stats.nonProgressMines >= nonProgressMineBudget;
    }

    public void exhaust(BlockPos pos, long tick) {
        RegionStats stats = statsFor(pos);
        if (stats == null) {
            return;
        }
        stats.exhaustedUntilTick = tick + exhaustedTtlTicks;
        stats.noTargetTicks = 0;
        stats.failureCount = 0;
        stats.nonProgressMines = 0;
    }

    public boolean isExhausted(BlockPos pos, long tick) {
        RegionStats stats = statsFor(pos);
        return stats != null && stats.exhaustedUntilTick > tick;
    }

    private RegionStats statsFor(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        long key = regionKey(pos);
        return statsByRegion.computeIfAbsent(key, ignored -> new RegionStats());
    }

    private long regionKey(BlockPos pos) {
        long chunkX = pos.getX() >> 4;
        long chunkY = pos.getY() >> 4;
        long chunkZ = pos.getZ() >> 4;
        long x = chunkX & 0x1FFFFFL;
        long y = chunkY & 0x3FFFL;
        long z = chunkZ & 0x1FFFFFL;
        return (x << 35) | (y << 21) | z;
    }

    private static final class RegionStats {
        private long lastNoTargetTick = Long.MIN_VALUE / 4;
        private int noTargetTicks = 0;
        private int failureCount = 0;
        private int nonProgressMines = 0;
        private long exhaustedUntilTick = 0L;
    }
}

