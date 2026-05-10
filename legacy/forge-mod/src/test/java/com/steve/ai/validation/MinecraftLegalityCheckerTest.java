package com.steve.ai.validation;

import com.steve.ai.action.Task;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftLegalityCheckerTest {
    @Test
    void rejectsUnknownAction() {
        Task task = new Task("illegal_action", Map.of());
        MinecraftLegalityChecker.CheckResult result = MinecraftLegalityChecker.validateTaskDefinition(task);
        assertFalse(result.legal());
    }

    @Test
    void rejectsPathfindWithoutCoordinates() {
        Task task = new Task("pathfind", Map.of("x", 1, "y", 64));
        MinecraftLegalityChecker.CheckResult result = MinecraftLegalityChecker.validateTaskDefinition(task);
        assertFalse(result.legal());
    }

    @Test
    void acceptsValidPathfindTask() {
        Task task = new Task("pathfind", Map.of("x", 1, "y", 64, "z", 1));
        MinecraftLegalityChecker.CheckResult result = MinecraftLegalityChecker.validateTaskDefinition(task);
        assertTrue(result.legal());
    }
}
