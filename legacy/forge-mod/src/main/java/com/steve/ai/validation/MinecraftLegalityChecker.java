package com.steve.ai.validation;

import com.steve.ai.action.Task;
import com.steve.ai.crafting.CraftingRecipeRegistry;
import com.steve.ai.crafting.CraftingStation;
import com.steve.ai.crafting.CraftingPlanner;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.mining.ToolCapabilityMap.ToolType;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;

public final class MinecraftLegalityChecker {
    public record CheckResult(boolean legal, String reason) {
        public static CheckResult ok() {
            return new CheckResult(true, "ok");
        }
    }

    private MinecraftLegalityChecker() {
    }

    public static CheckResult validateTaskDefinition(Task task) {
        if (task == null || task.getAction() == null || task.getAction().isBlank()) {
            return new CheckResult(false, "missing-action");
        }
        String action = task.getAction().toLowerCase();
        Map<String, Object> p = task.getParameters();
        if (p == null) {
            return new CheckResult(false, "missing-parameters");
        }

        return switch (action) {
            case "pathfind" -> hasAll(p, "x", "y", "z") ? CheckResult.ok() : new CheckResult(false, "pathfind-missing-coordinates");
            case "mine" -> {
                String block = firstNonBlank(stringVal(p.get("block")), stringVal(p.get("blockType")), stringVal(p.get("resource")));
                if (block == null || block.isBlank()) {
                    yield new CheckResult(false, "mine-missing-block");
                }
                Block parsed = ActionUtils.parseBlock(block);
                if (parsed == Blocks.AIR) {
                    yield new CheckResult(false, "mine-invalid-block");
                }
                yield CheckResult.ok();
            }
            case "place" -> {
                String block = stringVal(p.get("block"));
                if (block == null || block.isBlank()) {
                    yield new CheckResult(false, "place-missing-block");
                }
                Block parsed = ActionUtils.parseBlock(block);
                if (parsed == Blocks.AIR) {
                    yield new CheckResult(false, "place-invalid-block");
                }
                if (!hasAll(p, "x", "y", "z")) {
                    yield new CheckResult(false, "place-missing-coordinates");
                }
                yield CheckResult.ok();
            }
            case "craft" -> {
                String item = firstNonBlank(
                    stringVal(p.get("item")),
                    stringVal(p.get("recipe")),
                    stringVal(p.get("output")),
                    stringVal(p.get("target"))
                );
                if (item == null || item.isBlank()) {
                    yield new CheckResult(false, "craft-missing-item");
                }
                Item parsed = parseItem(item);
                if (parsed == null) {
                    yield new CheckResult(false, "craft-unknown-item");
                }
                String itemId = BuiltInRegistries.ITEM.getKey(parsed).toString();
                CraftingPlanner.RecipeSpec recipe = CraftingRecipeRegistry.getRecipe(itemId);
                if (recipe == null) {
                    yield new CheckResult(false, "craft-no-legal-recipe");
                }
                yield CheckResult.ok();
            }
            case "smelt" -> {
                String item = stringVal(p.get("item"));
                if (item == null || item.isBlank()) {
                    yield new CheckResult(false, "smelt-missing-item");
                }
                Item parsed = parseItem(item);
                if (parsed == null) {
                    yield new CheckResult(false, "smelt-unknown-item");
                }
                String itemId = BuiltInRegistries.ITEM.getKey(parsed).toString();
                CraftingPlanner.RecipeSpec recipe = CraftingRecipeRegistry.getRecipe(itemId);
                if (recipe == null || recipe.station() != CraftingStation.FURNACE) {
                    yield new CheckResult(false, "smelt-no-furnace-recipe");
                }
                yield CheckResult.ok();
            }
            case "farm", "feed", "follow", "gather", "attack", "retrieve_chest", "build" -> CheckResult.ok();
            default -> new CheckResult(false, "unknown-action");
        };
    }

    public static CheckResult validateTaskForExecution(SteveEntity steve, Task task) {
        CheckResult base = validateTaskDefinition(task);
        if (!base.legal()) {
            return base;
        }
        return CheckResult.ok();
    }

    public static boolean canTillFarmlandNow(SteveEntity steve) {
        return steve != null && !steve.findToolInInventory(ToolType.HOE).isEmpty();
    }

    public static boolean isCraftingStationSupported(CraftingStation station) {
        return station == CraftingStation.NONE
            || station == CraftingStation.CRAFTING_TABLE
            || station == CraftingStation.FURNACE
            || station == CraftingStation.STONECUTTER;
    }

    private static boolean hasAll(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            if (!params.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    private static String stringVal(Object val) {
        return val == null ? null : val.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Item parseItem(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.toLowerCase().replace(" ", "_");
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }
}
