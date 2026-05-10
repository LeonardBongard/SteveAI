package com.steve.ai.execution.behavior;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BehaviorSchedulerTest {

    @Test
    void runsInLaneAndPriorityOrder() {
        BehaviorRegistry registry = new BehaviorRegistry();
        List<String> runOrder = new ArrayList<>();

        registry.register(new LoggingBehavior("b", "background", 50, 10, 1, 0, runOrder));
        registry.register(new LoggingBehavior("a", "critical", 0, 50, 1, 0, runOrder));
        registry.register(new LoggingBehavior("c", "background", 50, 5, 1, 0, runOrder));

        BehaviorScheduler scheduler = new BehaviorScheduler(registry);
        scheduler.tick(testContext(100), 10);

        assertEquals(List.of("a", "c", "b"), runOrder);
    }

    @Test
    void honorsTickBudget() {
        BehaviorRegistry registry = new BehaviorRegistry();
        List<String> runOrder = new ArrayList<>();

        registry.register(new LoggingBehavior("a", "critical", 0, 10, 2, 0, runOrder));
        registry.register(new LoggingBehavior("b", "critical", 0, 20, 2, 0, runOrder));
        registry.register(new LoggingBehavior("c", "critical", 0, 30, 2, 0, runOrder));

        BehaviorScheduler scheduler = new BehaviorScheduler(registry);
        scheduler.tick(testContext(100), 5);

        assertEquals(List.of("a", "b"), runOrder);
    }

    @Test
    void respectsCooldownAcrossTicks() {
        BehaviorRegistry registry = new BehaviorRegistry();
        AtomicInteger runs = new AtomicInteger();

        registry.register(new BehaviorDefinition() {
            @Override
            public String id() {
                return "cooldown";
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
                runs.incrementAndGet();
            }
        });

        BehaviorScheduler scheduler = new BehaviorScheduler(registry);
        scheduler.tick(testContext(100), 5);
        scheduler.tick(testContext(110), 5);
        scheduler.tick(testContext(121), 5);

        assertEquals(2, runs.get());
    }

    @Test
    void isolatesBehaviorExceptions() {
        BehaviorRegistry registry = new BehaviorRegistry();
        List<String> runOrder = new ArrayList<>();

        registry.register(new BehaviorDefinition() {
            @Override
            public String id() {
                return "bad";
            }

            @Override
            public int lanePriority() {
                return 0;
            }

            @Override
            public boolean shouldRun(BehaviorContext context) {
                return true;
            }

            @Override
            public void run(BehaviorContext context) {
                throw new RuntimeException("boom");
            }
        });
        registry.register(new LoggingBehavior("good", "background", 50, 10, 1, 0, runOrder));

        BehaviorScheduler scheduler = new BehaviorScheduler(registry);
        scheduler.tick(testContext(100), 5);

        assertEquals(List.of("good"), runOrder);
    }

    @Test
    void shouldRunFalseSkipsBehavior() {
        BehaviorRegistry registry = new BehaviorRegistry();
        AtomicInteger runs = new AtomicInteger();

        registry.register(new BehaviorDefinition() {
            @Override
            public String id() {
                return "skip";
            }

            @Override
            public boolean shouldRun(BehaviorContext context) {
                return false;
            }

            @Override
            public void run(BehaviorContext context) {
                runs.incrementAndGet();
            }
        });

        BehaviorScheduler scheduler = new BehaviorScheduler(registry);
        scheduler.tick(testContext(100), 5);

        assertEquals(0, runs.get());
    }

    private static BehaviorContext testContext(long gameTime) {
        return new BehaviorContext(null, null, gameTime) {
            @Override
            public boolean isServerSide() {
                return true;
            }
        };
    }

    private static final class LoggingBehavior implements BehaviorDefinition {
        private final String id;
        private final String lane;
        private final int lanePriority;
        private final int priority;
        private final int cost;
        private final int cooldown;
        private final List<String> sink;

        private LoggingBehavior(
            String id,
            String lane,
            int lanePriority,
            int priority,
            int cost,
            int cooldown,
            List<String> sink
        ) {
            this.id = id;
            this.lane = lane;
            this.lanePriority = lanePriority;
            this.priority = priority;
            this.cost = cost;
            this.cooldown = cooldown;
            this.sink = sink;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String lane() {
            return lane;
        }

        @Override
        public int lanePriority() {
            return lanePriority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int budgetCost() {
            return cost;
        }

        @Override
        public int cooldownTicks() {
            return cooldown;
        }

        @Override
        public boolean shouldRun(BehaviorContext context) {
            return true;
        }

        @Override
        public void run(BehaviorContext context) {
            sink.add(id);
        }
    }
}

