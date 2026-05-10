package com.steve.ai.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRiskManagerTest {
    @Test
    void rejectsFarChestWhenUrgencyIsCritical() {
        SourceRiskManager.Decision decision = SourceRiskManager.evaluateChestCandidate(
            8,
            30.0,
            100,
            4,
            50,
            SourceRiskManager.Urgency.CRITICAL
        );
        assertFalse(decision.accept());
    }

    @Test
    void acceptsNearbyChestWithAdequateValue() {
        SourceRiskManager.Decision decision = SourceRiskManager.evaluateChestCandidate(
            12,
            10.0,
            50,
            4,
            50,
            SourceRiskManager.Urgency.NORMAL
        );
        assertTrue(decision.accept());
    }

    @Test
    void rejectsVeryStaleLowValueSnapshot() {
        SourceRiskManager.Decision decision = SourceRiskManager.evaluateChestCandidate(
            1,
            12.0,
            40_000,
            2,
            50,
            SourceRiskManager.Urgency.NORMAL
        );
        assertFalse(decision.accept());
    }
}
