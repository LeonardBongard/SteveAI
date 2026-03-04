package com.steve.ai.execution;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.List;

public record SafetySnapshot(
    SafetyState state,
    int safetyScore,
    SafetyDecision recommendedDecision,
    PanicLevel panicLevel,
    int panicScore,
    List<String> reasons,
    BlockPos retreatTarget
) {
    public SafetySnapshot {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static SafetySnapshot safe() {
        return new SafetySnapshot(
            SafetyState.SAFE,
            100,
            SafetyDecision.CONTINUE,
            PanicLevel.NONE,
            0,
            Collections.emptyList(),
            null
        );
    }

    public boolean isSafe() {
        return state == SafetyState.SAFE || state == SafetyState.CAUTION;
    }
}
