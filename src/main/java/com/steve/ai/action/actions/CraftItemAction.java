package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class CraftItemAction extends BaseAction {
    private String itemName;
    private int quantity;
    private int ticksRunning;

    public CraftItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;

        if (itemName == null || itemName.isBlank()) {
            result = ActionResult.failure("Missing item to craft");
            return;
        }

        Item outputItem = parseItem(itemName);
        if (outputItem == null) {
            result = ActionResult.failure("Unknown item: " + itemName);
            return;
        }

        Recipe recipe = RecipeBook.getRecipe(outputItem);
        if (recipe == null) {
            result = ActionResult.failure("No basic recipe for: " + itemName);
            return;
        }

        int batches = (int) Math.ceil((double) quantity / recipe.outputCount);
        Map<Item, Integer> required = new HashMap<>();
        for (var entry : recipe.inputs.entrySet()) {
            required.put(entry.getKey(), entry.getValue() * batches);
        }

        for (var entry : required.entrySet()) {
            Item input = entry.getKey();
            int needed = entry.getValue();
            if (!steve.hasItem(input, needed)) {
                enqueueGather(input, needed);
                result = ActionResult.success("Gathering ingredients for " + itemName);
                return;
            }
        }

        for (var entry : required.entrySet()) {
            steve.consumeItem(entry.getKey(), entry.getValue());
        }

        int toProduce = recipe.outputCount * batches;
        ItemStack produced = new ItemStack(outputItem, toProduce);
        ItemStack remaining = steve.addToInventory(produced);
        if (!remaining.isEmpty() && steve.level() instanceof ServerLevel serverLevel) {
            serverLevel.addFreshEntity(new ItemEntity(
                serverLevel,
                steve.getX(),
                steve.getY() + 0.5,
                steve.getZ(),
                remaining
            ));
        }

        result = ActionResult.success("Crafted " + toProduce + " " + outputItem.getName(produced).getString());
    }

    @Override
    protected void onTick() {
        ticksRunning++;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Craft " + quantity + " " + itemName;
    }

    private void enqueueGather(Item input, int needed) {
        String resource = itemIdPath(input);
        Map<String, Object> gatherParams = new HashMap<>();
        gatherParams.put("resource", resource);
        gatherParams.put("quantity", needed);
        steve.getActionExecutor().enqueueTask(new Task("gather", gatherParams));

        Map<String, Object> craftParams = new HashMap<>(task.getParameters());
        steve.getActionExecutor().enqueueTask(new Task("craft", craftParams));
    }

    private Item parseItem(String name) {
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

    private String itemIdPath(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "air" : id.getPath();
    }

    private record Recipe(int outputCount, Map<Item, Integer> inputs) {}

    private static class RecipeBook {
        private static final Map<Item, Recipe> RECIPES = new HashMap<>();

        static {
            Item oakLog = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:oak_log")).orElse(null);
            Item oakPlanks = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:oak_planks")).orElse(null);
            Item stick = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:stick")).orElse(null);
            Item craftingTable = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:crafting_table")).orElse(null);
            Item coal = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:coal")).orElse(null);
            Item torch = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:torch")).orElse(null);
            Item cobblestone = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:cobblestone")).orElse(null);
            Item stonePickaxe = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:stone_pickaxe")).orElse(null);

            if (oakLog != null && oakPlanks != null) {
                RECIPES.put(oakPlanks, new Recipe(4, Map.of(oakLog, 1)));
            }
            if (oakPlanks != null && stick != null) {
                RECIPES.put(stick, new Recipe(4, Map.of(oakPlanks, 2)));
            }
            if (oakPlanks != null && craftingTable != null) {
                RECIPES.put(craftingTable, new Recipe(1, Map.of(oakPlanks, 4)));
            }
            if (coal != null && stick != null && torch != null) {
                RECIPES.put(torch, new Recipe(4, Map.of(coal, 1, stick, 1)));
            }
            if (cobblestone != null && stick != null && stonePickaxe != null) {
                RECIPES.put(stonePickaxe, new Recipe(1, Map.of(cobblestone, 3, stick, 2)));
            }
        }

        static Recipe getRecipe(Item output) {
            return RECIPES.get(output);
        }
    }
}
