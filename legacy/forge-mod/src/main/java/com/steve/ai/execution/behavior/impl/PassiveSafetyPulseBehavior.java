package com.steve.ai.execution.behavior.impl;

import com.steve.ai.execution.behavior.BehaviorContext;
import com.steve.ai.execution.behavior.BehaviorDefinition;

/**
 * Keeps safety state continuously refreshed independent of the foreground action loop.
 */
public class PassiveSafetyPulseBehavior implements BehaviorDefinition {
    @Override
    public String id() {
        return "passive_safety_pulse";
    }

    @Override
    public String lane() {
        return "critical.safety";
    }

    @Override
    public int lanePriority() {
        return 0;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public int budgetCost() {
        return 1;
    }

    @Override
    public int cooldownTicks() {
        return 20;
    }

    @Override
    public boolean shouldRun(BehaviorContext context) {
        return true;
    }

    @Override
    public void run(BehaviorContext context) {
        context.executor().sampleSafetySnapshot("background");
    }
}

