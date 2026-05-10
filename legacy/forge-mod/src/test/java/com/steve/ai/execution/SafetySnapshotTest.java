package com.steve.ai.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetySnapshotTest {
    @Test
    void safeFactoryIncludesNoOpPanicDefaults() {
        SafetySnapshot snapshot = SafetySnapshot.safe();

        assertEquals(SafetyState.SAFE, snapshot.state());
        assertEquals(100, snapshot.safetyScore());
        assertEquals(SafetyDecision.CONTINUE, snapshot.recommendedDecision());
        assertEquals(PanicLevel.NONE, snapshot.panicLevel());
        assertEquals(0, snapshot.panicScore());
        assertNotNull(snapshot.reasons());
        assertTrue(snapshot.reasons().isEmpty());
        assertTrue(snapshot.isSafe());
    }

    @Test
    void panicFieldsDoNotChangeIsSafeContract() {
        SafetySnapshot caution = new SafetySnapshot(
            SafetyState.CAUTION,
            70,
            SafetyDecision.CONTINUE,
            PanicLevel.CRITICAL,
            100,
            List.of("test"),
            null
        );
        SafetySnapshot danger = new SafetySnapshot(
            SafetyState.DANGER,
            40,
            SafetyDecision.RETREAT,
            PanicLevel.NONE,
            0,
            List.of("test"),
            null
        );

        assertTrue(caution.isSafe());
        assertFalse(danger.isSafe());
    }

    @Test
    void reasonsAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>();
        mutable.add("x");

        SafetySnapshot snapshot = new SafetySnapshot(
            SafetyState.SAFE,
            100,
            SafetyDecision.CONTINUE,
            PanicLevel.NONE,
            0,
            mutable,
            null
        );

        mutable.add("y");
        assertEquals(List.of("x"), snapshot.reasons());
    }
}
