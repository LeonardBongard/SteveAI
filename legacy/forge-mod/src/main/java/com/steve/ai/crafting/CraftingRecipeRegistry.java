package com.steve.ai.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CraftingRecipeRegistry {
    private static final Map<String, CraftingPlanner.RecipeSpec> RECIPES = new HashMap<>();
    private static final String GENERATED_RECIPES_PATH = "/steve/crafting_recipes.csv";

    static {
        // Generated recipes first, then manual registrations below can override for curated behavior.
        loadGeneratedRecipes();
        pruneNonProductiveRecipes();

        // Core early-game wood chain.
        register("minecraft:oak_planks", 4, Map.of("minecraft:oak_log", 1));
        register("minecraft:stick", 4, Map.of("minecraft:oak_planks", 2));
        register("minecraft:crafting_table", 1, Map.of("minecraft:oak_planks", 4));
        register("minecraft:chest", 1, Map.of("minecraft:oak_planks", 8), CraftingStation.CRAFTING_TABLE);

        // Utility / station processing.
        register("minecraft:furnace", 1, Map.of("minecraft:cobblestone", 8), CraftingStation.CRAFTING_TABLE);
        register("minecraft:stonecutter", 1, Map.of("minecraft:iron_ingot", 1, "minecraft:stone", 3), CraftingStation.CRAFTING_TABLE);
        register("minecraft:torch", 4, Map.of("minecraft:coal", 1, "minecraft:stick", 1));
        register("minecraft:bread", 1, Map.of("minecraft:wheat", 3), CraftingStation.CRAFTING_TABLE);
        register("minecraft:glass", 1, Map.of("minecraft:sand", 1), CraftingStation.FURNACE);
        register("minecraft:smooth_stone", 1, Map.of("minecraft:stone", 1), CraftingStation.FURNACE);
        register("minecraft:iron_ingot", 1, Map.of("minecraft:raw_iron", 1), CraftingStation.FURNACE);
        register("minecraft:gold_ingot", 1, Map.of("minecraft:raw_gold", 1), CraftingStation.FURNACE);
        register("minecraft:copper_ingot", 1, Map.of("minecraft:raw_copper", 1), CraftingStation.FURNACE);
        register("minecraft:stone_bricks", 1, Map.of("minecraft:stone", 1), CraftingStation.STONECUTTER);

        // Wooden tools.
        register("minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:wooden_axe", 1, Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:wooden_shovel", 1, Map.of("minecraft:oak_planks", 1, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:wooden_hoe", 1, Map.of("minecraft:oak_planks", 2, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:wooden_sword", 1, Map.of("minecraft:oak_planks", 2, "minecraft:stick", 1), CraftingStation.CRAFTING_TABLE);

        // Stone tools.
        register("minecraft:stone_pickaxe", 1, Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:stone_axe", 1, Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:stone_shovel", 1, Map.of("minecraft:cobblestone", 1, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:stone_hoe", 1, Map.of("minecraft:cobblestone", 2, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:stone_sword", 1, Map.of("minecraft:cobblestone", 2, "minecraft:stick", 1), CraftingStation.CRAFTING_TABLE);

        // Iron tools.
        register("minecraft:iron_pickaxe", 1, Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:iron_axe", 1, Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:iron_shovel", 1, Map.of("minecraft:iron_ingot", 1, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:iron_hoe", 1, Map.of("minecraft:iron_ingot", 2, "minecraft:stick", 2), CraftingStation.CRAFTING_TABLE);
        register("minecraft:iron_sword", 1, Map.of("minecraft:iron_ingot", 2, "minecraft:stick", 1), CraftingStation.CRAFTING_TABLE);
    }

    private CraftingRecipeRegistry() {
    }

    public static CraftingPlanner.RecipeSpec getRecipe(String itemId) {
        if (itemId == null) {
            return null;
        }
        return RECIPES.get(itemId);
    }

    private static void register(String outputItemId, int outputCount, Map<String, Integer> inputs) {
        register(outputItemId, outputCount, inputs, CraftingStation.NONE);
    }

    private static void register(String outputItemId, int outputCount, Map<String, Integer> inputs, CraftingStation station) {
        Item output = parseItem(outputItemId);
        if (output == null) {
            return;
        }
        Map<String, Integer> sanitized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : inputs.entrySet()) {
            Item input = parseItem(entry.getKey());
            if (input == null) {
                continue;
            }
            String inputId = BuiltInRegistries.ITEM.getKey(input).toString();
            int amount = Math.max(1, entry.getValue());
            sanitized.put(inputId, amount);
        }
        if (sanitized.isEmpty()) {
            return;
        }
        String outputId = BuiltInRegistries.ITEM.getKey(output).toString();
        RECIPES.put(
            outputId,
            new CraftingPlanner.RecipeSpec(
                Math.max(1, outputCount),
                Collections.unmodifiableMap(sanitized),
                station == null ? CraftingStation.NONE : station
            )
        );
    }

    private static Item parseItem(String rawId) {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private static synchronized void loadGeneratedRecipes() {
        try (InputStream stream = CraftingRecipeRegistry.class.getResourceAsStream(GENERATED_RECIPES_PATH)) {
            if (stream == null) {
                return;
            }
            Map<RecipeKey, RecipeAccumulator> grouped = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    if (parts.length < 6) {
                        continue;
                    }
                    String outputId = parts[0].trim();
                    int outputCount = parsePositive(parts[1], 1);
                    String inputId = parts[2].trim();
                    int inputCount = parsePositive(parts[3], 1);
                    CraftingStation station = parseStation(parts[4]);
                    String recipeId = parts[5].trim();

                    if (outputId.isBlank() || inputId.isBlank() || recipeId.isBlank()) {
                        continue;
                    }
                    Item outputItem = parseItem(outputId);
                    Item inputItem = parseItem(inputId);
                    if (outputItem == null || inputItem == null) {
                        continue;
                    }
                    String normalizedOutputId = BuiltInRegistries.ITEM.getKey(outputItem).toString();
                    String normalizedInputId = BuiltInRegistries.ITEM.getKey(inputItem).toString();
                    RecipeKey key = new RecipeKey(normalizedOutputId, recipeId, station);
                    RecipeAccumulator acc = grouped.computeIfAbsent(key, ignored -> new RecipeAccumulator(outputCount));
                    acc.outputCount = Math.max(acc.outputCount, outputCount);
                    acc.inputs.merge(normalizedInputId, Math.max(1, inputCount), Integer::sum);
                }
            }

            Map<String, CraftingPlanner.RecipeSpec> bestByOutput = new HashMap<>();
            Map<String, Integer> bestScoreByOutput = new HashMap<>();
            for (Map.Entry<RecipeKey, RecipeAccumulator> e : grouped.entrySet()) {
                RecipeKey key = e.getKey();
                RecipeAccumulator acc = e.getValue();
                if (acc.inputs.isEmpty()) {
                    continue;
                }
                Map<String, Integer> inputs = Collections.unmodifiableMap(new HashMap<>(acc.inputs));
                CraftingPlanner.RecipeSpec spec = new CraftingPlanner.RecipeSpec(acc.outputCount, inputs, key.station);
                int score = acc.inputs.values().stream().mapToInt(Integer::intValue).sum();
                Integer current = bestScoreByOutput.get(key.outputId);
                if (current == null || score < current) {
                    bestByOutput.put(key.outputId, spec);
                    bestScoreByOutput.put(key.outputId, score);
                }
            }

            RECIPES.putAll(bestByOutput);
        } catch (Exception ignored) {
            // Keep startup resilient: curated registrations still provide core behavior.
        }
    }

    private static void pruneNonProductiveRecipes() {
        // Prevent recursive raw-resource pack/unpack loops from taking planner precedence.
        RECIPES.remove("minecraft:raw_iron");
        RECIPES.remove("minecraft:raw_gold");
        RECIPES.remove("minecraft:raw_copper");
    }

    private static int parsePositive(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(1, v);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static CraftingStation parseStation(String raw) {
        if (raw == null || raw.isBlank()) {
            return CraftingStation.NONE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRAFTING_TABLE" -> CraftingStation.CRAFTING_TABLE;
            case "FURNACE" -> CraftingStation.FURNACE;
            case "STONECUTTER" -> CraftingStation.STONECUTTER;
            default -> CraftingStation.NONE;
        };
    }

    private record RecipeKey(String outputId, String recipeId, CraftingStation station) {
    }

    private static final class RecipeAccumulator {
        private int outputCount;
        private final Map<String, Integer> inputs = new HashMap<>();

        private RecipeAccumulator(int outputCount) {
            this.outputCount = Math.max(1, outputCount);
        }
    }
}
