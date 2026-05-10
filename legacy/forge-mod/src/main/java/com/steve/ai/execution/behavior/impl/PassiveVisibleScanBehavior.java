package com.steve.ai.execution.behavior.impl;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.config.SteveRuntimeSettings;
import com.steve.ai.execution.behavior.BehaviorContext;
import com.steve.ai.execution.behavior.BehaviorDefinition;

/**
 * Keeps world perception fresh even while foreground actions are running.
 */
public class PassiveVisibleScanBehavior implements BehaviorDefinition {
    @Override
    public String id() {
        return "passive_visible_scan";
    }

    @Override
    public String lane() {
        return "background.observe";
    }

    @Override
    public int lanePriority() {
        return 40;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public int budgetCost() {
        return 3;
    }

    @Override
    public int cooldownTicks() {
        return Math.max(2, SteveConfig.DEBUG_VISIBLE_BLOCK_TICK_INTERVAL.get());
    }

    @Override
    public boolean shouldRun(BehaviorContext context) {
        int scanAge = context.steve().getVisibleScanAge();
        return scanAge < 0 || scanAge >= cooldownTicks();
    }

    @Override
    public void run(BehaviorContext context) {
        context.steve().forceVisibleScan(SteveRuntimeSettings.getVisibleScanRadius());
    }
}

