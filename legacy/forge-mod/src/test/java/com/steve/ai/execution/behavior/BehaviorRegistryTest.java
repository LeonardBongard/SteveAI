package com.steve.ai.execution.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorRegistryTest {

    @Test
    void registerAndUnregisterWorks() {
        BehaviorRegistry registry = new BehaviorRegistry();
        registry.register(new TestBehavior("a"));
        registry.register(new TestBehavior("b"));
        assertEquals(2, registry.snapshot().size());

        registry.unregister("a");
        assertEquals(1, registry.snapshot().size());
        assertEquals("b", registry.snapshot().get(0).id());
    }

    @Test
    void sameIdReplacesPreviousBehavior() {
        BehaviorRegistry registry = new BehaviorRegistry();
        registry.register(new TestBehavior("dup", 100));
        registry.register(new TestBehavior("dup", 1));

        assertEquals(1, registry.snapshot().size());
        assertEquals(1, registry.snapshot().get(0).priority());
    }

    @Test
    void invalidIdsAreIgnored() {
        BehaviorRegistry registry = new BehaviorRegistry();
        registry.register(new TestBehavior(""));
        registry.register(new TestBehavior(" "));

        assertTrue(registry.snapshot().isEmpty());
    }

    private static final class TestBehavior implements BehaviorDefinition {
        private final String id;
        private final int priority;

        private TestBehavior(String id) {
            this(id, 100);
        }

        private TestBehavior(String id, int priority) {
            this.id = id;
            this.priority = priority;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean shouldRun(BehaviorContext context) {
            return true;
        }

        @Override
        public void run(BehaviorContext context) {
            // no-op
        }
    }
}

