package com.steve.ai.animal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AnimalFoodResolver {
    private static final Map<String, List<String>> SPECIES_FOOD = Map.ofEntries(
        Map.entry("cow", List.of("minecraft:wheat")),
        Map.entry("sheep", List.of("minecraft:wheat")),
        Map.entry("goat", List.of("minecraft:wheat")),
        Map.entry("mooshroom", List.of("minecraft:wheat")),
        Map.entry("pig", List.of("minecraft:carrot", "minecraft:potato", "minecraft:beetroot")),
        Map.entry("chicken", List.of("minecraft:wheat_seeds", "minecraft:melon_seeds", "minecraft:pumpkin_seeds", "minecraft:beetroot_seeds")),
        Map.entry("rabbit", List.of("minecraft:carrot", "minecraft:dandelion")),
        Map.entry("horse", List.of("minecraft:golden_carrot", "minecraft:golden_apple", "minecraft:apple", "minecraft:carrot", "minecraft:wheat")),
        Map.entry("donkey", List.of("minecraft:golden_carrot", "minecraft:golden_apple", "minecraft:apple", "minecraft:carrot", "minecraft:wheat")),
        Map.entry("mule", List.of("minecraft:golden_carrot", "minecraft:golden_apple", "minecraft:apple", "minecraft:carrot", "minecraft:wheat")),
        Map.entry("llama", List.of("minecraft:hay_block", "minecraft:wheat"))
    );

    private AnimalFoodResolver() {
    }

    public static List<Item> preferredFoods(String species) {
        List<String> ids = SPECIES_FOOD.getOrDefault(normalizeSpecies(species), List.of());
        List<Item> out = new ArrayList<>();
        for (String idRaw : ids) {
            Identifier id = Identifier.tryParse(idRaw);
            if (id == null) {
                continue;
            }
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(out::add);
        }
        return out;
    }

    public static boolean speciesMatches(Animal animal, String requestedSpecies) {
        if (animal == null) {
            return false;
        }
        String normalized = normalizeSpecies(requestedSpecies);
        if (normalized.equals("any") || normalized.isBlank()) {
            return true;
        }
        EntityType<?> type = animal.getType();
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.equals(normalized);
    }

    public static boolean canFeed(Animal animal, ItemStack stack) {
        return animal != null && stack != null && !stack.isEmpty() && animal.isFood(stack);
    }

    public static List<Item> allKnownFoodItems() {
        Set<Item> items = new LinkedHashSet<>();
        for (List<String> ids : SPECIES_FOOD.values()) {
            for (String idRaw : ids) {
                Identifier id = Identifier.tryParse(idRaw);
                if (id == null) {
                    continue;
                }
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(items::add);
            }
        }
        return List.copyOf(items);
    }

    private static String normalizeSpecies(String raw) {
        if (raw == null || raw.isBlank()) {
            return "any";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        return colon >= 0 ? normalized.substring(colon + 1) : normalized;
    }
}
