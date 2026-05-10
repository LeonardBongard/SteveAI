package com.steve.ai.execution.behavior;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BehaviorRegistry {
    private final Map<String, BehaviorDefinition> behaviors = new LinkedHashMap<>();

    public void register(BehaviorDefinition behavior) {
        if (behavior == null || behavior.id() == null || behavior.id().isBlank()) {
            return;
        }
        behaviors.put(behavior.id(), behavior);
    }

    public void unregister(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        behaviors.remove(id);
    }

    public List<BehaviorDefinition> snapshot() {
        return new ArrayList<>(behaviors.values());
    }
}

