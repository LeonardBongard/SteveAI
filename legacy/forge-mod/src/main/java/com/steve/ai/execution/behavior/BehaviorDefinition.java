package com.steve.ai.execution.behavior;

/**
 * Modular scheduled behavior that can run alongside task execution.
 *
 * <p>Behaviors are grouped by lane and ordered by lane priority + behavior priority.
 * Lower values run first.</p>
 */
public interface BehaviorDefinition {
    String id();

    default String lane() {
        return "background";
    }

    default int lanePriority() {
        return 100;
    }

    default int priority() {
        return 100;
    }

    default int budgetCost() {
        return 1;
    }

    default int cooldownTicks() {
        return 20;
    }

    boolean shouldRun(BehaviorContext context);

    void run(BehaviorContext context);
}

