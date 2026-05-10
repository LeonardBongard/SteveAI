package com.steve.ai.execution.behavior.impl;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.execution.behavior.BehaviorContext;
import com.steve.ai.execution.behavior.BehaviorDefinition;

/**
 * Periodically refreshes chest knowledge without interrupting foreground tasks.
 */
public class PassiveChestScanBehavior implements BehaviorDefinition {
    @Override
    public String id() {
        return "passive_chest_scan";
    }

    @Override
    public String lane() {
        return "background.knowledge";
    }

    @Override
    public int lanePriority() {
        return 60;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public int budgetCost() {
        return 5;
    }

    @Override
    public int cooldownTicks() {
        return 80;
    }

    @Override
    public boolean shouldRun(BehaviorContext context) {
        return true;
    }

    @Override
    public void run(BehaviorContext context) {
        int configured = SteveConfig.CHEST_SOURCE_RADIUS.get();
        int scanRadius = Math.max(6, Math.min(configured, 16));
        context.steve().scanNearbyChests(scanRadius);
    }
}

