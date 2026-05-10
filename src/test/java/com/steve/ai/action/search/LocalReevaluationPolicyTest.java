package com.steve.ai.action.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalReevaluationPolicyTest {

    private final LocalReevaluationPolicy policy = new LocalReevaluationPolicy(8, 20, 12, 4.0, 16.0);

    @Test
    void switchesWhenCurrentTargetIsUnreachable() {
        LocalReevaluationPolicy.Decision decision = policy.evaluate(new LocalReevaluationPolicy.Context(
            true,
            true,
            false,
            false,
            100.0,
            36.0,
            0,
            0,
            100,
            0
        ));

        assertTrue(decision.shouldSwitch());
        assertEquals(StuckReason.UNREACHABLE_TARGET, decision.reason());
    }

    @Test
    void switchesWhenVisibleCandidateBeatsStaleTarget() {
        LocalReevaluationPolicy.Decision decision = policy.evaluate(new LocalReevaluationPolicy.Context(
            true,
            true,
            false,
            true,
            144.0,
            49.0,
            25,
            0,
            100,
            0
        ));

        assertTrue(decision.shouldSwitch());
        assertEquals(StuckReason.STALE_TARGET, decision.reason());
    }

    @Test
    void respectsCooldownBeforeSwitching() {
        LocalReevaluationPolicy.Decision decision = policy.evaluate(new LocalReevaluationPolicy.Context(
            true,
            true,
            false,
            false,
            100.0,
            25.0,
            30,
            15,
            100,
            96
        ));

        assertFalse(decision.shouldSwitch());
    }

    @Test
    void keepsCurrentTargetWhenGainIsTooSmall() {
        LocalReevaluationPolicy.Decision decision = policy.evaluate(new LocalReevaluationPolicy.Context(
            true,
            true,
            true,
            true,
            49.0,
            46.0,
            0,
            0,
            100,
            0
        ));

        assertFalse(decision.shouldSwitch());
        assertEquals(StuckReason.NONE, decision.reason());
    }
}
