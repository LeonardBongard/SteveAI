package com.steve.ai.action;

import com.steve.ai.crafting.CraftingPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPlannerTest {

    @Test
    void plansRecursiveDependenciesAndGatherRequirements() {
        Map<String, CraftingPlanner.RecipeSpec> recipes = Map.of(
            "minecraft:oak_planks", new CraftingPlanner.RecipeSpec(4, Map.of("minecraft:oak_log", 1)),
            "minecraft:stick", new CraftingPlanner.RecipeSpec(4, Map.of("minecraft:oak_planks", 2)),
            "minecraft:torch", new CraftingPlanner.RecipeSpec(4, Map.of("minecraft:coal", 1, "minecraft:stick", 1))
        );
        CraftingPlanner planner = new CraftingPlanner(recipes::get);

        CraftingPlanner.Plan plan = planner.plan("minecraft:torch", 8, Map.of());

        assertEquals(1, plan.gatherRequirements().getOrDefault("minecraft:oak_log", 0));
        assertEquals(2, plan.gatherRequirements().getOrDefault("minecraft:coal", 0));
        assertTrue(plan.craftSteps().stream().anyMatch(step -> step.itemId().equals("minecraft:oak_planks")));
        assertTrue(plan.craftSteps().stream().anyMatch(step -> step.itemId().equals("minecraft:stick")));
        assertTrue(plan.craftSteps().stream().anyMatch(step -> step.itemId().equals("minecraft:torch")));
    }

    @Test
    void usesAvailableInventoryBeforePlanningMoreWork() {
        Map<String, CraftingPlanner.RecipeSpec> recipes = Map.of(
            "minecraft:bread", new CraftingPlanner.RecipeSpec(1, Map.of("minecraft:wheat", 3))
        );
        CraftingPlanner planner = new CraftingPlanner(recipes::get);

        CraftingPlanner.Plan plan = planner.plan("minecraft:bread", 2, Map.of("minecraft:wheat", 6));

        assertEquals(Map.of(), plan.gatherRequirements());
        assertEquals(List.of(new CraftingPlanner.CraftStep("minecraft:bread", 2)), plan.craftSteps());
    }

    @Test
    void cycleFallsBackToGatherInsteadOfThrowing() {
        Map<String, CraftingPlanner.RecipeSpec> recipes = Map.of(
            "minecraft:raw_iron", new CraftingPlanner.RecipeSpec(9, Map.of("minecraft:raw_iron_block", 1)),
            "minecraft:raw_iron_block", new CraftingPlanner.RecipeSpec(1, Map.of("minecraft:raw_iron", 9)),
            "minecraft:iron_ingot", new CraftingPlanner.RecipeSpec(1, Map.of("minecraft:raw_iron", 1)),
            "minecraft:iron_pickaxe", new CraftingPlanner.RecipeSpec(1, Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2))
        );
        CraftingPlanner planner = new CraftingPlanner(recipes::get);

        CraftingPlanner.Plan plan = planner.plan("minecraft:iron_pickaxe", 1, Map.of());

        assertTrue(plan.gatherRequirements().getOrDefault("minecraft:raw_iron", 0) > 0);
        assertTrue(plan.gatherRequirements().getOrDefault("minecraft:stick", 0) > 0);
    }
}
