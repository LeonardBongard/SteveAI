package com.steve.ai.execution.behavior;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BehaviorScheduler {
    private static final Logger LOGGER = LogManager.getLogger(BehaviorScheduler.class);
    private final BehaviorRegistry registry;
    private final Map<String, Long> lastRunTickByBehavior = new HashMap<>();
    private final Map<String, Integer> runCountByBehavior = new HashMap<>();
    private long lastSummaryTick = Long.MIN_VALUE;

    public BehaviorScheduler(BehaviorRegistry registry) {
        this.registry = registry;
    }

    public void tick(BehaviorContext context, int tickBudget) {
        if (context == null || tickBudget <= 0 || !context.isServerSide()) {
            return;
        }

        List<BehaviorDefinition> ordered = registry.snapshot();
        ordered.sort(Comparator
            .comparingInt(BehaviorDefinition::lanePriority)
            .thenComparing(BehaviorDefinition::lane)
            .thenComparingInt(BehaviorDefinition::priority)
            .thenComparing(BehaviorDefinition::id));

        int budgetLeft = tickBudget;
        int ran = 0;
        long now = context.gameTime();

        for (BehaviorDefinition behavior : ordered) {
            int cost = Math.max(1, behavior.budgetCost());
            if (cost > budgetLeft) {
                continue;
            }
            if (!cooldownReady(behavior, now)) {
                continue;
            }
            boolean shouldRun;
            try {
                shouldRun = behavior.shouldRun(context);
            } catch (Exception e) {
                LOGGER.warn(
                    "[BEHAVIOR] Steve '{}' shouldRun failed for '{}' ({})",
                    safeSteveName(context),
                    behavior.id(),
                    e.toString()
                );
                continue;
            }
            if (!shouldRun) {
                continue;
            }
            try {
                behavior.run(context);
                budgetLeft -= cost;
                ran++;
                lastRunTickByBehavior.put(behavior.id(), now);
                runCountByBehavior.merge(behavior.id(), 1, Integer::sum);
            } catch (Exception e) {
                LOGGER.warn(
                    "[BEHAVIOR] Steve '{}' run failed for '{}' ({})",
                    safeSteveName(context),
                    behavior.id(),
                    e.toString()
                );
            }
        }

        if (ran > 0 && (lastSummaryTick == Long.MIN_VALUE || now - lastSummaryTick >= 200)) {
            lastSummaryTick = now;
            LOGGER.debug(
                "[BEHAVIOR] Steve '{}' ran {} passive behavior(s), budgetLeft={}, counters={}",
                safeSteveName(context),
                ran,
                budgetLeft,
                runCountByBehavior
            );
        }
    }

    private boolean cooldownReady(BehaviorDefinition behavior, long now) {
        long lastTick = lastRunTickByBehavior.getOrDefault(behavior.id(), Long.MIN_VALUE);
        int cooldown = Math.max(0, behavior.cooldownTicks());
        if (lastTick == Long.MIN_VALUE) {
            return true;
        }
        return now - lastTick >= cooldown;
    }

    private String safeSteveName(BehaviorContext context) {
        if (context == null || context.steve() == null) {
            return "unknown";
        }
        String name = context.steve().getSteveName();
        return (name == null || name.isBlank()) ? "unknown" : name;
    }
}
