package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.crafting.CraftingPlanner;
import com.steve.ai.crafting.CraftingRecipeRegistry;
import com.steve.ai.crafting.CraftingStation;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.gather.ResourceTargetResolver;
import com.steve.ai.mining.ToolCapabilityMap;
import com.steve.ai.mining.ToolCapabilityMap.ToolTier;
import com.steve.ai.mining.ToolCapabilityMap.ToolType;
import com.steve.ai.resource.FuelPolicy;
import com.steve.ai.resource.KnownResourceManager;
import com.steve.ai.validation.MinecraftLegalityChecker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CraftItemAction extends BaseAction {
    private String itemName;
    private String targetItemId;
    private int quantity;
    private boolean plannedExecution;
    private int stationAttempt;
    private static final int MAX_STATION_ATTEMPTS = 3;
    private static final int STATION_SCAN_RADIUS = 10;
    private static final int STATION_FAR_SCAN_RADIUS = 96;
    private static final int STATION_OWNED_SCAN_RADIUS = 256;
    private static final double STATION_INTERACT_RANGE_SQR = 20.25; // ~4.5 blocks
    private static final String WOODEN_PICKAXE_ID = "minecraft:wooden_pickaxe";
    private static final String STONE_PICKAXE_ID = "minecraft:stone_pickaxe";
    private static final String IRON_PICKAXE_ID = "minecraft:iron_pickaxe";
    private static final String DIAMOND_PICKAXE_ID = "minecraft:diamond_pickaxe";
    private static final String NETHERITE_PICKAXE_ID = "minecraft:netherite_pickaxe";
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
    private BlockPos activeStationPos;

    public CraftItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = positiveOrDefault(task.getIntParameter("quantity", -1), task.getIntParameter("count", 1));
        plannedExecution = toBoolean(task.getParameter("planned"));
        stationAttempt = Math.max(0, task.getIntParameter("station_attempt", 0));
        activeStationPos = null;

        if (itemName == null || itemName.isBlank() || quantity <= 0) {
            result = ActionResult.failure("Missing item to craft");
            return;
        }

        Item outputItem = parseItem(itemName);
        if (outputItem == null) {
            result = ActionResult.failure("Unknown item: " + itemName);
            return;
        }
        targetItemId = BuiltInRegistries.ITEM.getKey(outputItem).toString();

        CraftingPlanner.RecipeSpec recipe = CraftingRecipeRegistry.getRecipe(targetItemId);
        if (recipe == null) {
            result = ActionResult.failure("No basic recipe for: " + itemName);
            return;
        }
        if (!MinecraftLegalityChecker.isCraftingStationSupported(recipe.station())) {
            result = ActionResult.failure("Unsupported crafting station for: " + itemName);
            return;
        }

        int existingCount = steve.getItemCount(outputItem);
        int stillNeeded = Math.max(0, quantity - existingCount);
        if (stillNeeded <= 0) {
            result = ActionResult.success("Already have " + existingCount + " " + outputItem.getName(new ItemStack(outputItem)).getString());
            return;
        }
        if (recipe.station() == CraftingStation.FURNACE) {
            steve.getActionExecutor().enqueueTask(new Task("smelt", Map.of(
                "item", itemNameFromId(targetItemId),
                "quantity", stillNeeded,
                "smelt_attempt", 0
            )));
            result = ActionResult.success("Queued legal smelting for " + itemName + " (" + stillNeeded + ")");
            return;
        }

        if (!ensureStationReady(recipe.station())) {
            return;
        }

        Map<String, Integer> required = resolveDirectRequirements(recipe, stillNeeded);
        List<String> missing = findMissingIngredients(required);
        if (!missing.isEmpty()) {
            // If we already own raw_iron + fuel, prefer legal smelting immediately instead of
            // bouncing back to extra mining/crafting loops for iron_ingot requirements.
            if (plannedExecution && maybeQueueSmeltRecovery(required)) {
                result = ActionResult.success("Queued smelt-first recovery for " + itemName);
                return;
            }
            if (plannedExecution) {
                result = ActionResult.failure("Missing ingredients after planning: " + String.join(", ", missing));
                return;
            }

            int queued = enqueueCraftPlan(targetItemId, stillNeeded);
            if (queued <= 0) {
                result = ActionResult.failure("Could not build crafting pipeline for " + itemName);
                return;
            }
            result = ActionResult.success("Queued crafting plan for " + itemName + " (" + stillNeeded + ", tasks=" + queued + ")");
            return;
        }

        for (var entry : required.entrySet()) {
            consumeEquivalentItems(entry.getKey(), entry.getValue());
        }

        int toProduce = (int) Math.ceil((double) stillNeeded / recipe.outputCount()) * recipe.outputCount();
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
        maybePackOwnedCraftingTable(recipe.station());

        result = ActionResult.success("Crafted " + toProduce + " " + outputItem.getName(produced).getString());
    }

    @Override
    protected void onTick() {
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Craft " + quantity + " " + itemName;
    }

    private int enqueueCraftPlan(String outputItemId, int outputQuantity) {
        CraftingPlanner planner = new CraftingPlanner(CraftingRecipeRegistry::getRecipe);
        final CraftingPlanner.Plan plan;
        try {
            plan = planner.plan(
                outputItemId,
                outputQuantity,
                steve.getInventoryItemCountsById()
            );
        } catch (Exception e) {
            SteveMod.LOGGER.error(
                "[CRAFT] Steve '{}' failed to create craft plan for {} x{}",
                steve.getSteveName(),
                outputItemId,
                outputQuantity,
                e
            );
            return 0;
        }

        int inInventory = steve.getInventoryItemCountsById().getOrDefault(outputItemId, 0);
        int remainingOutput = Math.max(0, outputQuantity - inInventory);
        int chestRadius = KnownResourceManager.configuredChestRadius();
        int inKnownChests = KnownResourceManager.knownChestItemCount(
            steve,
            outputItemId,
            chestRadius,
            true
        );

        if (plan.gatherRequirements().isEmpty() && plan.craftSteps().isEmpty()) {
            if (remainingOutput > 0 && inKnownChests > 0) {
                int fromChest = Math.min(remainingOutput, inKnownChests);
                steve.getActionExecutor().enqueueTask(new Task("retrieve_chest", Map.of(
                    "item", itemNameFromId(outputItemId),
                    "quantity", fromChest,
                    "radius", chestRadius,
                    "fallback_to_gather", true
                )));
                return 1;
            }
            return 0;
        }

        List<Task> pipeline = new ArrayList<>();
        KnownResourceManager.GatherSourcingPlan sourcePlan =
            KnownResourceManager.splitGatherRequirementsWithChestMemory(
                steve,
                plan.gatherRequirements(),
                chestRadius
            );

        maybePrependPickaxeBootstrap(
            pipeline,
            sourcePlan.remainingGatherRequirements(),
            plan.craftSteps(),
            outputItemId
        );
        pipeline.addAll(sourcePlan.chestRetrieveTasks());
        appendFuelAcquisitionTasks(pipeline, plan.craftSteps());

        for (Map.Entry<String, Integer> gather : sourcePlan.remainingGatherRequirements().entrySet()) {
            List<ResourceTargetResolver.Candidate> candidates = ResourceTargetResolver.gatherCandidateEntriesForItemId(gather.getKey());
            if (candidates.isEmpty()) {
                continue;
            }
            ResourceTargetResolver.Candidate preferredCandidate = ResourceTargetResolver.chooseBestVisibleCandidate(steve, candidates);
            String preferred = preferredCandidate != null
                ? pathOf(preferredCandidate.blockId())
                : pathOf(candidates.get(0).blockId());
            if (preferred == null || preferred.isBlank()) {
                continue;
            }
            List<String> candidateIds = new ArrayList<>();
            for (ResourceTargetResolver.Candidate c : candidates) {
                candidateIds.add(c.blockId());
            }
            List<String> candidatePaths = ResourceTargetResolver.toPathList(candidateIds);
            List<String> alternatives = new ArrayList<>(candidatePaths);
            alternatives.remove(preferred);
            pipeline.add(new Task("gather", Map.of(
                "resource", preferred,
                "quantity", gather.getValue(),
                "alternatives", alternatives
            )));
        }
        for (CraftingPlanner.CraftStep step : plan.craftSteps()) {
            CraftingPlanner.RecipeSpec stepRecipe = CraftingRecipeRegistry.getRecipe(step.itemId());
            if (stepRecipe != null && stepRecipe.station() == CraftingStation.FURNACE) {
                pipeline.add(new Task("smelt", Map.of(
                    "item", itemNameFromId(step.itemId()),
                    "quantity", step.quantity(),
                    "smelt_attempt", 0
                )));
            } else {
                pipeline.add(new Task("craft", Map.of(
                    "item", itemNameFromId(step.itemId()),
                    "quantity", step.quantity(),
                    "planned", true,
                    "station_attempt", 0
                )));
            }
        }

        for (Task t : pipeline) {
            steve.getActionExecutor().enqueueTask(t);
        }
        SteveMod.LOGGER.info(
            "[CRAFT_PIPELINE] Steve '{}' queued {} tasks for {} x{}",
            steve.getSteveName(),
            pipeline.size(),
            outputItemId,
            outputQuantity
        );
        return pipeline.size();
    }

    private void maybePrependPickaxeBootstrap(
        List<Task> pipeline,
        Map<String, Integer> remainingGatherRequirements,
        List<CraftingPlanner.CraftStep> craftSteps,
        String outputItemId
    ) {
        if (pipeline == null || remainingGatherRequirements == null || remainingGatherRequirements.isEmpty()) {
            return;
        }
        if (WOODEN_PICKAXE_ID.equals(outputItemId)) {
            return;
        }
        if (!steve.findToolInInventory(ToolType.PICKAXE).isEmpty()) {
            return;
        }
        if (craftSteps != null) {
            for (CraftingPlanner.CraftStep step : craftSteps) {
                if (step != null && WOODEN_PICKAXE_ID.equals(step.itemId()) && step.quantity() > 0) {
                    return;
                }
            }
        }

        boolean needsPickaxeBootstrap = false;
        ToolTier minRequiredTier = ToolTier.WOOD;
        for (Map.Entry<String, Integer> entry : remainingGatherRequirements.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            List<ResourceTargetResolver.Candidate> candidates =
                ResourceTargetResolver.gatherCandidateEntriesForItemId(entry.getKey());
            if (candidates.isEmpty()) {
                continue;
            }

            boolean anyMineableNow = false;
            boolean anyRequiresPickaxe = false;
            ToolTier highestCandidateTier = ToolTier.NONE;
            for (ResourceTargetResolver.Candidate candidate : candidates) {
                String blockId = candidate.blockId();
                ToolCapabilityMap.ToolRequirement req = ToolCapabilityMap.getRequirement(blockId);
                if (req.required() == ToolType.PICKAXE) {
                    anyRequiresPickaxe = true;
                    if (req.minTier() != null && req.minTier().rank() > highestCandidateTier.rank()) {
                        highestCandidateTier = req.minTier();
                    }
                }
                if (steve.canMineBlockNow(blockId)) {
                    anyMineableNow = true;
                    break;
                }
            }

            if (!anyMineableNow && anyRequiresPickaxe) {
                needsPickaxeBootstrap = true;
                if (highestCandidateTier.rank() > minRequiredTier.rank()) {
                    minRequiredTier = highestCandidateTier;
                }
                SteveMod.LOGGER.info(
                    "[CRAFT_BOOTSTRAP] Steve '{}' gather item={} is pickaxe-gated; required tier={}",
                    steve.getSteveName(),
                    entry.getKey(),
                    highestCandidateTier
                );
            }
        }

        if (!needsPickaxeBootstrap) {
            return;
        }
        if (steve.hasPickaxeAtLeast(minRequiredTier)) {
            return;
        }
        String pickaxeId = pickaxeIdForTier(minRequiredTier);

        pipeline.add(new Task("craft", Map.of(
            "item", itemNameFromId(pickaxeId),
            "quantity", 1,
            "planned", false,
            "station_attempt", 0
        )));
    }

    private String pickaxeIdForTier(ToolTier tier) {
        if (tier == null) {
            return WOODEN_PICKAXE_ID;
        }
        return switch (tier) {
            case NETHERITE -> NETHERITE_PICKAXE_ID;
            case DIAMOND -> DIAMOND_PICKAXE_ID;
            case IRON -> IRON_PICKAXE_ID;
            case STONE -> STONE_PICKAXE_ID;
            default -> WOODEN_PICKAXE_ID;
        };
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

    private Map<String, Integer> resolveDirectRequirements(CraftingPlanner.RecipeSpec recipe, int targetQuantity) {
        int batches = (int) Math.ceil((double) targetQuantity / recipe.outputCount());
        Map<String, Integer> required = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : recipe.inputs().entrySet()) {
            String inputId = normalizeItemId(entry.getKey());
            if (inputId == null) {
                continue;
            }
            required.put(inputId, entry.getValue() * batches);
        }
        return required;
    }

    private List<String> findMissingIngredients(Map<String, Integer> required) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            String inputId = entry.getKey();
            int need = entry.getValue();
            int have = countEquivalentItems(inputId);
            if (have < need) {
                missing.add(itemNameFromId(inputId) + " (" + have + "/" + need + ")");
            }
        }
        return missing;
    }

    private int countEquivalentItems(String requiredItemId) {
        int total = 0;
        for (String id : equivalentItemIds(requiredItemId)) {
            Item item = parseItem(id);
            if (item == null) {
                continue;
            }
            total += steve.getItemCount(item);
        }
        return total;
    }

    private void consumeEquivalentItems(String requiredItemId, int amount) {
        int remaining = Math.max(0, amount);
        for (String id : equivalentItemIds(requiredItemId)) {
            if (remaining <= 0) {
                break;
            }
            Item item = parseItem(id);
            if (item == null) {
                continue;
            }
            int have = steve.getItemCount(item);
            if (have <= 0) {
                continue;
            }
            int take = Math.min(remaining, have);
            steve.consumeItem(item, take);
            remaining -= take;
        }
    }

    private String normalizeItemId(String raw) {
        Item item = parseItem(raw);
        if (item == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private List<String> equivalentItemIds(String itemId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        // Keep canonical id first, then append family-equivalent variants (wood/planks).
        ids.add(itemId);
        if (WOOD_LOG_EQUIVALENTS.contains(itemId)) {
            ids.addAll(WOOD_LOG_EQUIVALENTS);
        }
        if (WOOD_PLANK_EQUIVALENTS.contains(itemId)) {
            ids.addAll(WOOD_PLANK_EQUIVALENTS);
        }
        return new ArrayList<>(ids);
    }

    private boolean maybeQueueSmeltRecovery(Map<String, Integer> required) {
        if (required == null || required.isEmpty()) {
            return false;
        }
        int neededIngots = required.getOrDefault("minecraft:iron_ingot", 0);
        if (neededIngots <= 0) {
            return false;
        }
        int haveIngots = countEquivalentItems("minecraft:iron_ingot");
        int missingIngots = Math.max(0, neededIngots - haveIngots);
        if (missingIngots <= 0) {
            return false;
        }

        int rawIron = countEquivalentItems("minecraft:raw_iron");
        if (rawIron <= 0) {
            return false;
        }
        int fuelUnits = estimateFuelUnitsInInventory();
        if (fuelUnits <= 0) {
            return false;
        }

        int smeltQuantity = Math.min(missingIngots, rawIron);
        if (smeltQuantity <= 0) {
            return false;
        }

        if (!steve.getActionExecutor().hasQueuedTask("smelt", "item", "iron_ingot")) {
            steve.getActionExecutor().enqueueTask(new Task("smelt", Map.of(
                "item", "iron_ingot",
                "quantity", smeltQuantity,
                "smelt_attempt", 0
            )));
        }

        Map<String, Object> retry = new HashMap<>(task.getParameters());
        retry.put("item", itemName);
        retry.put("quantity", quantity);
        retry.put("planned", true);
        retry.put("station_attempt", stationAttempt);
        steve.getActionExecutor().enqueueTask(new Task("craft", retry));

        SteveMod.LOGGER.info(
            "[CRAFT] Steve '{}' smelt-first recovery: missingIngots={} rawIron={} fuelUnits={} for {}",
            steve.getSteveName(),
            missingIngots,
            rawIron,
            fuelUnits,
            itemName
        );
        return true;
    }

    private String itemNameFromId(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return itemId;
        }
        return id.getPath();
    }

    private String pathOf(String namespacedId) {
        int idx = namespacedId.indexOf(':');
        if (idx < 0 || idx >= namespacedId.length() - 1) {
            return namespacedId;
        }
        return namespacedId.substring(idx + 1);
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

    private int positiveOrDefault(int preferred, int fallback) {
        if (preferred > 0) {
            return preferred;
        }
        return Math.max(1, fallback);
    }

    private boolean ensureStationReady(CraftingStation station) {
        if (station == null || station == CraftingStation.NONE) {
            return true;
        }
        Block stationBlock = parseBlock(station.blockId());
        if (stationBlock == null) {
            result = ActionResult.failure("Unknown crafting station block: " + station.blockId());
            return false;
        }
        BlockPos ownedStationPos = steve.findNearestOwnedStation(station.blockId(), steve.blockPosition(), STATION_OWNED_SCAN_RADIUS);
        if (ownedStationPos != null) {
            double distSqr = steve.distanceToSqr(
                ownedStationPos.getX() + 0.5,
                ownedStationPos.getY() + 0.5,
                ownedStationPos.getZ() + 0.5
            );
            if (distSqr > STATION_INTERACT_RANGE_SQR) {
                if (!queueMoveToStationAndRetry(ownedStationPos)) {
                    enqueueStationSetup(station);
                    result = ActionResult.success("Owned station unreachable; queued station setup for " + pathOf(station.blockId()));
                    return false;
                }
                SteveMod.LOGGER.info(
                    "[CRAFT] Steve '{}' reusing owned station {} at {}",
                    steve.getSteveName(),
                    station.blockId(),
                    ownedStationPos
                );
                result = ActionResult.success("Moving to owned " + pathOf(station.blockId()) + " to craft " + itemName);
                return false;
            }
            activeStationPos = ownedStationPos.immutable();
            steve.getLookControl().setLookAt(
                ownedStationPos.getX() + 0.5,
                ownedStationPos.getY() + 0.5,
                ownedStationPos.getZ() + 0.5
            );
            SteveMod.LOGGER.info(
                "[CRAFT] Steve '{}' using owned station {} at {} (distSqr={})",
                steve.getSteveName(),
                station.blockId(),
                ownedStationPos,
                String.format(java.util.Locale.ROOT, "%.2f", distSqr)
            );
            return true;
        }
        BlockPos stationPos = findNearestStationPos(stationBlock, STATION_SCAN_RADIUS);
        if (stationPos != null) {
            double distSqr = steve.distanceToSqr(
                stationPos.getX() + 0.5,
                stationPos.getY() + 0.5,
                stationPos.getZ() + 0.5
            );
            if (distSqr > STATION_INTERACT_RANGE_SQR) {
                if (!queueMoveToStationAndRetry(stationPos)) {
                    enqueueStationSetup(station);
                    result = ActionResult.success("Station unreachable; queued station setup for " + pathOf(station.blockId()));
                    return false;
                }
                result = ActionResult.success("Moving to " + pathOf(station.blockId()) + " to craft " + itemName);
                return false;
            }
            activeStationPos = stationPos.immutable();
            steve.getLookControl().setLookAt(
                stationPos.getX() + 0.5,
                stationPos.getY() + 0.5,
                stationPos.getZ() + 0.5
            );
            SteveMod.LOGGER.info(
                "[CRAFT] Steve '{}' using station {} at {} (distSqr={})",
                steve.getSteveName(),
                station.blockId(),
                stationPos,
                String.format(java.util.Locale.ROOT, "%.2f", distSqr)
            );
            return true;
        }

        // Reuse an already placed station in a broader radius before crafting a new one.
        BlockPos farStationPos = findNearestStationPos(stationBlock, STATION_FAR_SCAN_RADIUS);
        if (farStationPos != null) {
            if (!queueMoveToStationAndRetry(farStationPos)) {
                enqueueStationSetup(station);
                result = ActionResult.success("Known station unreachable; queued station setup for " + pathOf(station.blockId()));
                return false;
            }
            SteveMod.LOGGER.info(
                "[CRAFT] Steve '{}' reusing distant station {} at {}",
                steve.getSteveName(),
                station.blockId(),
                farStationPos
            );
            result = ActionResult.success("Moving to known " + pathOf(station.blockId()) + " to craft " + itemName);
            return false;
        }

        if (stationAttempt >= MAX_STATION_ATTEMPTS) {
            result = ActionResult.failure("Missing required station: " + pathOf(station.blockId()));
            return false;
        }

        enqueueStationSetup(station);
        result = ActionResult.success("Queued station setup for " + pathOf(station.blockId()));
        return false;
    }

    private boolean queueMoveToStationAndRetry(BlockPos stationPos) {
        boolean queuedPath = steve.getActionExecutor().tryEnqueueTask(new Task("pathfind", Map.of(
            "x", stationPos.getX(),
            "y", stationPos.getY(),
            "z", stationPos.getZ(),
            "range", 5
        )));
        if (!queuedPath) {
            SteveMod.LOGGER.warn(
                "[CRAFT] Steve '{}' blocked repeated path task for station {} while crafting {}",
                steve.getSteveName(),
                stationPos,
                itemName
            );
            return false;
        }
        Map<String, Object> retry = new HashMap<>(task.getParameters());
        retry.put("item", itemName);
        retry.put("quantity", quantity);
        retry.put("planned", plannedExecution);
        retry.put("station_attempt", stationAttempt);
        return steve.getActionExecutor().tryEnqueueTask(new Task("craft", retry));
    }

    private void enqueueStationSetup(CraftingStation station) {
        String stationItem = pathOf(station.blockId());
        Block stationBlock = parseBlock(station.blockId());
        Item stationItemObj = parseItem(station.blockId());
        int hasCount = stationItemObj == null ? 0 : steve.getItemCount(stationItemObj);
        if (steve.getActionExecutor().hasQueuedTask("craft", "item", stationItem)
            || steve.getActionExecutor().hasQueuedTask("place", "block", stationItem)) {
            SteveMod.LOGGER.info(
                "[CRAFT] Steve '{}' skipped duplicate station setup for {} (already queued)",
                steve.getSteveName(),
                station.blockId()
            );
            return;
        }

        BlockPos existing = stationBlock == null ? null : findNearestStationPos(stationBlock, STATION_FAR_SCAN_RADIUS);
        if (existing != null) {
            SteveMod.LOGGER.info(
                "[CRAFT] Steve '{}' skipped station setup for {} because one already exists nearby",
                steve.getSteveName(),
                station.blockId()
            );
            if (queueMoveToStationAndRetry(existing)) {
                return;
            }
        }

        if (hasCount <= 0) {
            steve.getActionExecutor().enqueueTask(new Task("craft", Map.of(
                "item", stationItem,
                "quantity", 1,
                "planned", false,
                "station_attempt", stationAttempt + 1
            )));
        }

        BlockPos placePos = findNearbyPlacementPos();
        if (placePos != null && stationBlock != null) {
            steve.getActionExecutor().enqueueTask(new Task("place", Map.of(
                "block", pathOf(station.blockId()),
                "x", placePos.getX(),
                "y", placePos.getY(),
                "z", placePos.getZ(),
                "owned_station", true
            )));
        }

        Map<String, Object> retry = new HashMap<>(task.getParameters());
        retry.put("item", itemName);
        retry.put("quantity", quantity);
        retry.put("planned", plannedExecution);
        retry.put("station_attempt", stationAttempt + 1);
        steve.getActionExecutor().enqueueTask(new Task("craft", retry));
        SteveMod.LOGGER.info(
            "[CRAFT] Steve '{}' queued station setup station={} attempt={} placePos={}",
            steve.getSteveName(),
            station.blockId(),
            stationAttempt + 1,
            placePos
        );
    }

    private boolean isStationNearby(Block stationBlock, int radius) {
        BlockPos origin = steve.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (steve.level().getBlockState(pos).getBlock() == stationBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findNearestStationPos(Block stationBlock, int radius) {
        BlockPos origin = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (steve.level().getBlockState(pos).getBlock() != stationBlock) {
                        continue;
                    }
                    if (!isStationReachable(pos)) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isStationReachable(BlockPos stationPos) {
        if (stationPos == null) {
            return false;
        }
        double distSqr = steve.distanceToSqr(
            stationPos.getX() + 0.5,
            stationPos.getY() + 0.5,
            stationPos.getZ() + 0.5
        );
        if (distSqr <= STATION_INTERACT_RANGE_SQR) {
            return true;
        }
        Path path = steve.getNavigation().createPath(stationPos, 0);
        return path != null && path.canReach();
    }

    private BlockPos findNearbyPlacementPos() {
        BlockPos origin = steve.blockPosition();
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = origin.offset(dx, 0, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    BlockState below = steve.level().getBlockState(pos.below());
                    if (!state.isAir()) {
                        continue;
                    }
                    if (below.isAir()) {
                        continue;
                    }
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private void appendFuelAcquisitionTasks(List<Task> pipeline, List<CraftingPlanner.CraftStep> craftSteps) {
        if (pipeline == null || craftSteps == null || craftSteps.isEmpty()) {
            return;
        }
        int neededFuelUnits = 0;
        for (CraftingPlanner.CraftStep step : craftSteps) {
            CraftingPlanner.RecipeSpec recipe = CraftingRecipeRegistry.getRecipe(step.itemId());
            if (recipe == null || recipe.station() != CraftingStation.FURNACE) {
                continue;
            }
            neededFuelUnits += Math.max(0, step.quantity());
        }
        if (neededFuelUnits <= 0) {
            return;
        }

        int availableFuelUnits = estimateFuelUnitsInInventory();
        int missingFuelUnits = Math.max(0, neededFuelUnits - availableFuelUnits);
        if (missingFuelUnits <= 0) {
            return;
        }

        // Reserve with fuel categories:
        // 1) Prefer coal path for efficiency.
        // 2) Add wood-log fallback so smelting can proceed even when coal is absent.
        appendFuelAcquireTask(pipeline, FuelPolicy.COAL, missingFuelUnits);
        appendFuelAcquireTask(pipeline, FuelPolicy.OAK_LOG, missingFuelUnits);

        SteveMod.LOGGER.info(
            "[CRAFT] Steve '{}' reserved smelt fuel upfront (need={} units, missing={} units; fuels=[{},{}])",
            steve.getSteveName(),
            neededFuelUnits,
            missingFuelUnits,
            FuelPolicy.COAL,
            FuelPolicy.OAK_LOG
        );
    }

    private void appendFuelAcquireTask(List<Task> pipeline, String fuelItemId, int missingFuelUnits) {
        if (pipeline == null || fuelItemId == null || fuelItemId.isBlank() || missingFuelUnits <= 0) {
            return;
        }
        int fuelUnits = Math.max(1, FuelPolicy.fuelUnitsForItem(fuelItemId));
        int fuelItemsNeeded = Math.max(1, (int) Math.ceil((double) missingFuelUnits / fuelUnits));

        int chestRadius = KnownResourceManager.configuredChestRadius();
        int inKnownChests = KnownResourceManager.knownChestItemCount(steve, fuelItemId, chestRadius, true);
        int fromChest = Math.min(fuelItemsNeeded, Math.max(0, inKnownChests));
        if (fromChest > 0) {
            pipeline.add(new Task("retrieve_chest", Map.of(
                "item", pathOf(fuelItemId),
                "quantity", fromChest,
                "radius", chestRadius,
                "fallback_to_gather", true
            )));
        }

        int toGather = Math.max(0, fuelItemsNeeded - fromChest);
        if (toGather <= 0) {
            return;
        }
        List<ResourceTargetResolver.Candidate> candidates = ResourceTargetResolver.gatherCandidateEntriesForItemId(fuelItemId);
        if (candidates.isEmpty()) {
            return;
        }
        ResourceTargetResolver.Candidate preferredCandidate = ResourceTargetResolver.chooseBestVisibleCandidate(steve, candidates);
        String preferred = preferredCandidate != null
            ? pathOf(preferredCandidate.blockId())
            : pathOf(candidates.get(0).blockId());
        if (preferred == null || preferred.isBlank()) {
            return;
        }
        LinkedHashSet<String> alternatives = new LinkedHashSet<>();
        for (ResourceTargetResolver.Candidate c : candidates) {
            alternatives.add(pathOf(c.blockId()));
        }
        alternatives.remove(preferred);
        pipeline.add(new Task("gather", Map.of(
            "resource", preferred,
            "quantity", toGather,
            "alternatives", new ArrayList<>(alternatives)
        )));
    }

    private int estimateFuelUnitsInInventory() {
        int total = 0;
        for (String fuelId : FuelPolicy.inventoryFuelPriority()) {
            Item fuel = parseItem(fuelId);
            if (fuel == null) {
                continue;
            }
            int count = steve.getItemCount(fuel);
            if (count <= 0) {
                continue;
            }
            total += count * fuelUnitForItem(fuelId);
        }
        return total;
    }

    private int fuelUnitForItem(String itemId) {
        return FuelPolicy.fuelUnitsForItem(itemId);
    }

    private void maybePackOwnedCraftingTable(CraftingStation station) {
        if (station != CraftingStation.CRAFTING_TABLE || activeStationPos == null) {
            return;
        }
        if (!steve.isOwnedStation(CraftingStation.CRAFTING_TABLE.blockId(), activeStationPos)) {
            return;
        }
        if (steve.level().getBlockState(activeStationPos).getBlock() != Blocks.CRAFTING_TABLE) {
            steve.forgetOwnedStation(CraftingStation.CRAFTING_TABLE.blockId(), activeStationPos);
            activeStationPos = null;
            return;
        }
        steve.level().destroyBlock(activeStationPos, false);
        Item tableItem = parseItem(CraftingStation.CRAFTING_TABLE.blockId());
        if (tableItem != null) {
            steve.addToInventory(new ItemStack(tableItem, 1));
        }
        steve.forgetOwnedStation(CraftingStation.CRAFTING_TABLE.blockId(), activeStationPos);
        SteveMod.LOGGER.info(
            "[CRAFT] Steve '{}' packed owned crafting table from {}",
            steve.getSteveName(),
            activeStationPos
        );
        activeStationPos = null;
    }

    private Block parseBlock(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }
}
