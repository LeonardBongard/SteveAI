package com.steve.ai.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSafetyEvaluatorManagerTest {
    private final DefaultSafetyEvaluatorManager manager = new DefaultSafetyEvaluatorManager();

    @Test
    void retreatsOnHighPanicEvenWhenBaseDecisionIsContinue() {
        SafetySnapshot snapshot = new SafetySnapshot(
            SafetyState.CAUTION,
            72,
            SafetyDecision.CONTINUE,
            PanicLevel.HIGH,
            70,
            List.of("test"),
            null
        );

        assertEquals(SafetyDecision.RETREAT, manager.recommend(snapshot, "gather"));
    }

    @Test
    void retreatsOnMediumPanicWhenAttacking() {
        SafetySnapshot snapshot = new SafetySnapshot(
            SafetyState.CAUTION,
            72,
            SafetyDecision.CONTINUE,
            PanicLevel.MEDIUM,
            45,
            List.of("test"),
            null
        );

        assertEquals(SafetyDecision.RETREAT, manager.recommend(snapshot, "attack"));
    }

    @Test
    void allowsNonCombatOnMediumPanic() {
        SafetySnapshot snapshot = new SafetySnapshot(
            SafetyState.CAUTION,
            72,
            SafetyDecision.CONTINUE,
            PanicLevel.MEDIUM,
            45,
            List.of("test"),
            null
        );

        assertEquals(SafetyDecision.CONTINUE, manager.recommend(snapshot, "gather"));
    }

    @Test
    void keepsSnapshotDecisionWhenPanicIsLow() {
        SafetySnapshot snapshot = new SafetySnapshot(
            SafetyState.DANGER,
            40,
            SafetyDecision.RETREAT,
            PanicLevel.LOW,
            25,
            List.of("test"),
            null
        );

        assertEquals(SafetyDecision.RETREAT, manager.recommend(snapshot, "mine"));
    }
}
