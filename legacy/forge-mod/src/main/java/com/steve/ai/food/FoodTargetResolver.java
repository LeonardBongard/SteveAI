package com.steve.ai.food;

import com.steve.ai.action.Task;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FoodTargetResolver {
    private static final Set<String> PROTECTED_AUTO_EAT_ITEMS = Set.of(
        "minecraft:golden_apple",
        "minecraft:enchanted_golden_apple"
    );
    private static final Set<String> AVOID_AUTO_EAT_ITEMS = Set.of(
        "minecraft:rotten_flesh"
    );

    private FoodTargetResolver() {
    }

    public static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }

    public static boolean isEdibleByPolicy(ItemStack stack, boolean allowProtected, boolean allowUnsafe) {
        if (!isFood(stack)) {
            return false;
        }
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!allowProtected && PROTECTED_AUTO_EAT_ITEMS.contains(itemId)) {
            return false;
        }
        if (!allowUnsafe && AVOID_AUTO_EAT_ITEMS.contains(itemId)) {
            return false;
        }
        return true;
    }

    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    public static float saturation(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0.0F : food.saturation();
    }

    /**
     * Fallback recovery plan when no nearby stored food is available.
     * Ordered from "direct edible harvest" to "craft conversion" options.
     */
    public static List<Task> buildFallbackFoodTasks() {
        List<Task> tasks = new ArrayList<>();
        tasks.add(new Task("farm", Map.of("crop", "wheat", "quantity", 12)));
        tasks.add(new Task("farm", Map.of("crop", "carrot", "quantity", 8)));
        tasks.add(new Task("farm", Map.of("crop", "potato", "quantity", 8)));
        tasks.add(new Task("gather", Map.of("resource", "carrots", "quantity", 8)));
        tasks.add(new Task("gather", Map.of("resource", "potatoes", "quantity", 8)));
        tasks.add(new Task("gather", Map.of("resource", "sweet_berry_bush", "quantity", 10)));
        tasks.add(new Task("gather", Map.of("resource", "melon", "quantity", 8)));
        tasks.add(new Task("gather", Map.of("resource", "wheat", "quantity", 9)));
        tasks.add(new Task("craft", Map.of("item", "bread", "quantity", 3)));
        return tasks;
    }
}
