package com.steve.ai.execution;

import com.steve.ai.entity.SteveEntity;

public interface SafetyEvaluatorManager {
    SafetySnapshot evaluate(SteveEntity steve, ActionContext context);

    default SafetyDecision recommend(SafetySnapshot snapshot, String currentActionType) {
        if (snapshot == null) {
            return SafetyDecision.CONTINUE;
        }
        return snapshot.recommendedDecision();
    }

    default boolean isSafe(SafetySnapshot snapshot) {
        return snapshot == null || snapshot.isSafe();
    }
}

