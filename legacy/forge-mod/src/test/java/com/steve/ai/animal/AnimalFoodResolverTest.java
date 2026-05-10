package com.steve.ai.animal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimalFoodResolverTest {
    @Test
    void unknownSpeciesHasNoPreferredFood() {
        List<net.minecraft.world.item.Item> foods = AnimalFoodResolver.preferredFoods("unknown_species");
        assertEquals(0, foods.size());
    }

    @Test
    void namespacedUnknownSpeciesAlsoHasNoPreferredFood() {
        List<net.minecraft.world.item.Item> foods = AnimalFoodResolver.preferredFoods("minecraft:unknown_species");
        assertEquals(0, foods.size());
    }
}
