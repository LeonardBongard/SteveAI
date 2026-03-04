package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.crafting.CraftingRecipeRegistry;
import com.steve.ai.crafting.CraftingPlanner;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PlaceBlockAction extends BaseAction {
    private Block blockToPlace;
    private BlockPos targetPos;
    private int ticksRunning;
    private boolean requestedMaterials = false;
    private int placeAttempt;
    private static final int MAX_TICKS = 200;
    private static final int MAX_PLACE_ATTEMPTS = 3;

    public PlaceBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        placeAttempt = Math.max(0, task.getIntParameter("place_attempt", 0));
        
        blockToPlace = parseBlock(blockName);
        
        if (blockToPlace == null || blockToPlace == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }
        
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Place block timeout");
            return;
        }
        
        if (!steve.blockPosition().closerThan(targetPos, 5.0)) {
            steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            return;
        }
        
        BlockState currentState = steve.level().getBlockState(targetPos);
        if (currentState.getBlock() == blockToPlace) {
            // Idempotent placement: target already contains requested block.
            result = ActionResult.success("Target already has " + blockToPlace.getName().getString());
            return;
        }
        if (!currentState.isAir() && !currentState.liquid()) {
            result = ActionResult.failure("Position is not empty");
            return;
        }

        if (!steve.consumeItem(blockToPlace.asItem(), 1)) {
            if (!requestedMaterials) {
                if (placeAttempt >= MAX_PLACE_ATTEMPTS) {
                    result = ActionResult.failure("Missing item after retries: " + blockToPlace.getName().getString());
                    return;
                }
                requestedMaterials = true;
                String itemPath = itemPathForBlock(blockToPlace);
                if (itemPath != null && hasCraftRecipeFor(itemPath)) {
                    java.util.Map<String, Object> craftParams = new java.util.HashMap<>();
                    craftParams.put("item", itemPath);
                    craftParams.put("quantity", 1);
                    craftParams.put("planned", false);
                    steve.getActionExecutor().enqueueTask(new com.steve.ai.action.Task("craft", craftParams));
                } else {
                    java.util.Map<String, Object> gatherParams = new java.util.HashMap<>();
                    gatherParams.put("resource", itemPath != null ? itemPath : blockToPlace.getName().getString().toLowerCase().replace(" ", "_"));
                    gatherParams.put("quantity", 1);
                    steve.getActionExecutor().enqueueTask(new com.steve.ai.action.Task("gather", gatherParams));
                }

                java.util.Map<String, Object> placeParams = new java.util.HashMap<>(task.getParameters());
                placeParams.remove("__task_node_id");
                placeParams.remove("__task_parent_id");
                placeParams.put("place_attempt", placeAttempt + 1);
                steve.getActionExecutor().enqueueTask(new com.steve.ai.action.Task("place", placeParams));
                result = ActionResult.success(
                    "Queued material acquisition for " + blockToPlace.getName().getString()
                        + " (place retry " + (placeAttempt + 1) + "/" + MAX_PLACE_ATTEMPTS + ")"
                );
            } else {
                result = ActionResult.failure("Missing item: " + blockToPlace.getName().getString());
            }
            return;
        }
        
        steve.level().setBlock(targetPos, blockToPlace.defaultBlockState(), 3);
        if (toBoolean(task.getParameter("owned_station"))) {
            String blockId = BuiltInRegistries.BLOCK.getKey(blockToPlace).toString();
            steve.rememberOwnedStation(blockId, targetPos);
            SteveMod.LOGGER.info(
                "[STATION] Steve '{}' registered owned station {} at {}",
                steve.getSteveName(),
                blockId,
                targetPos
            );
        }
        result = ActionResult.success("Placed " + blockToPlace.getName().getString());
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Place " + blockToPlace.getName().getString() + " at " + targetPos;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        Identifier identifier = Identifier.tryParse(blockName);
        if (identifier == null) {
            return Blocks.AIR;
        }
        return BuiltInRegistries.BLOCK.getOptional(identifier).orElse(Blocks.AIR);
    }

    private String itemPathForBlock(Block block) {
        if (block == null) {
            return null;
        }
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (blockId == null) {
            return null;
        }
        return blockId.getPath();
    }

    private boolean hasCraftRecipeFor(String itemPath) {
        if (itemPath == null || itemPath.isBlank()) {
            return false;
        }
        String itemId = itemPath.contains(":") ? itemPath : "minecraft:" + itemPath;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            return false;
        }
        String normalized = BuiltInRegistries.ITEM.getKey(item).toString();
        CraftingPlanner.RecipeSpec recipe = CraftingRecipeRegistry.getRecipe(normalized);
        return recipe != null;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }
}
