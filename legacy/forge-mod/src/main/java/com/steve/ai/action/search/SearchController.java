package com.steve.ai.action.search;

import net.minecraft.core.BlockPos;

public final class SearchController {
    private final int retargetCooldownTicks;
    private final int commitmentTicks;
    private long lastSelectionTick = Long.MIN_VALUE / 4;
    private BlockPos committedTarget;
    private long committedUntilTick;

    public SearchController(int retargetCooldownTicks, int commitmentTicks) {
        this.retargetCooldownTicks = Math.max(0, retargetCooldownTicks);
        this.commitmentTicks = Math.max(0, commitmentTicks);
    }

    public void reset() {
        committedTarget = null;
        committedUntilTick = 0L;
        lastSelectionTick = Long.MIN_VALUE / 4;
    }

    public void clearCommitment() {
        committedTarget = null;
        committedUntilTick = 0L;
    }

    public BlockPos committedTargetAt(long tick) {
        if (committedTarget == null) {
            return null;
        }
        if (tick >= committedUntilTick) {
            clearCommitment();
            return null;
        }
        return committedTarget;
    }

    public boolean canSelectNewTarget(long tick) {
        if (committedTargetAt(tick) != null) {
            return false;
        }
        return tick - lastSelectionTick >= retargetCooldownTicks;
    }

    public void commitTarget(BlockPos target, long tick) {
        if (target == null) {
            return;
        }
        committedTarget = target.immutable();
        committedUntilTick = tick + commitmentTicks;
        lastSelectionTick = tick;
    }
}

