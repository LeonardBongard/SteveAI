package com.steve.ai.crafting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CraftingPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingPlanner.class);
    private static final Set<String> WOOD_LOG_EQUIVALENTS = Set.of(
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:jungle_log",
        "minecraft:acacia_log",
        "minecraft:dark_oak_log",
        "minecraft:mangrove_log",
        "minecraft:cherry_log",
        "minecraft:oak_wood",
        "minecraft:spruce_wood",
        "minecraft:birch_wood",
        "minecraft:jungle_wood",
        "minecraft:acacia_wood",
        "minecraft:dark_oak_wood",
        "minecraft:mangrove_wood",
        "minecraft:cherry_wood",
        "minecraft:crimson_stem",
        "minecraft:warped_stem",
        "minecraft:crimson_hyphae",
        "minecraft:warped_hyphae",
        "minecraft:bamboo_block"
    );
    private static final Set<String> WOOD_PLANK_EQUIVALENTS = Set.of(
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:jungle_planks",
        "minecraft:acacia_planks",
        "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks",
        "minecraft:cherry_planks",
        "minecraft:bamboo_planks",
        "minecraft:crimson_planks",
        "minecraft:warped_planks"
    );

    public record RecipeSpec(int outputCount, Map<String, Integer> inputs, CraftingStation station) {
        public RecipeSpec(int outputCount, Map<String, Integer> inputs) {
            this(outputCount, inputs, CraftingStation.NONE);
        }
    }

    public interface RecipeLookup {
        RecipeSpec find(String itemId);
    }

    public record CraftStep(String itemId, int quantity) {}

    public record Plan(Map<String, Integer> gatherRequirements, List<CraftStep> craftSteps) {}

    private final RecipeLookup recipeLookup;

    public CraftingPlanner(RecipeLookup recipeLookup) {
        this.recipeLookup = recipeLookup;
    }

    public Plan plan(String targetItemId, int quantity, Map<String, Integer> inventoryCounts) {
        if (targetItemId == null || targetItemId.isBlank() || quantity <= 0) {
            return new Plan(Map.of(), List.of());
        }

        Map<String, Integer> inventory = new HashMap<>();
        if (inventoryCounts != null) {
            inventory.putAll(inventoryCounts);
        }

        Map<String, Integer> gather = new LinkedHashMap<>();
        List<CraftStep> craftSteps = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> active = new HashSet<>();

        resolve(targetItemId, quantity, inventory, gather, craftSteps, stack, active);
        return new Plan(gather, compressSteps(craftSteps));
    }

    private void resolve(
        String itemId,
        int needed,
        Map<String, Integer> inventory,
        Map<String, Integer> gather,
        List<CraftStep> craftSteps,
        Deque<String> stack,
        Set<String> active
    ) {
        int remaining = consumeFromEquivalentInventory(itemId, needed, inventory);
        if (remaining <= 0) {
            return;
        }

        RecipeSpec recipe = recipeLookup.find(itemId);
        if (recipe == null) {
            gather.merge(itemId, remaining, Integer::sum);
            return;
        }

        if (!active.add(itemId)) {
            // Guard against recipe loops (e.g., raw_iron <-> raw_iron_block).
            // Degrade to gather so planning continues without crashing the game.
            LOGGER.warn("[CRAFT_PLAN] Cycle detected: {} -> {}. Falling back to gather.", stack, itemId);
            gather.merge(itemId, remaining, Integer::sum);
            return;
        }
        stack.push(itemId);
        try {
            int outputCount = Math.max(1, recipe.outputCount());
            int batches = (int) Math.ceil((double) remaining / outputCount);

            for (Map.Entry<String, Integer> entry : recipe.inputs().entrySet()) {
                String inputId = entry.getKey();
                int inputAmount = Math.max(0, entry.getValue()) * batches;
                if (inputAmount <= 0) {
                    continue;
                }
                resolve(inputId, inputAmount, inventory, gather, craftSteps, stack, active);
            }

            int produced = outputCount * batches;
            int leftover = produced - remaining;
            if (leftover > 0) {
                inventory.merge(itemId, leftover, Integer::sum);
            }
            craftSteps.add(new CraftStep(itemId, produced));
        } finally {
            stack.pop();
            active.remove(itemId);
        }
    }

    private List<CraftStep> compressSteps(List<CraftStep> craftSteps) {
        if (craftSteps.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        for (CraftStep step : craftSteps) {
            totals.merge(step.itemId(), step.quantity(), Integer::sum);
        }
        List<CraftStep> compressed = new ArrayList<>(totals.size());
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            compressed.add(new CraftStep(entry.getKey(), entry.getValue()));
        }
        return compressed;
    }

    private int consumeFromEquivalentInventory(String itemId, int needed, Map<String, Integer> inventory) {
        if (needed <= 0) {
            return 0;
        }
        int remaining = needed;
        // Consume across equivalent ids so generic recipes ("oak_planks") can be satisfied
        // by any wood/plank family variant present in inventory.
        for (String equivalentId : equivalentItemIds(itemId)) {
            int available = inventory.getOrDefault(equivalentId, 0);
            if (available <= 0) {
                continue;
            }
            int used = Math.min(remaining, available);
            inventory.put(equivalentId, available - used);
            remaining -= used;
            if (remaining <= 0) {
                break;
            }
        }
        return remaining;
    }

    private List<String> equivalentItemIds(String itemId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        ids.add(itemId);
        if (WOOD_LOG_EQUIVALENTS.contains(itemId)) {
            ids.addAll(WOOD_LOG_EQUIVALENTS);
        }
        if (WOOD_PLANK_EQUIVALENTS.contains(itemId)) {
            ids.addAll(WOOD_PLANK_EQUIVALENTS);
        }
        return new ArrayList<>(ids);
    }
}
