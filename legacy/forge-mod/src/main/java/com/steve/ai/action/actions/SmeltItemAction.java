package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.crafting.CraftingPlanner;
import com.steve.ai.crafting.CraftingRecipeRegistry;
import com.steve.ai.crafting.CraftingStation;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.gather.ResourceTargetResolver;
import com.steve.ai.resource.FuelPolicy;
import com.steve.ai.resource.KnownResourceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SmeltItemAction extends BaseAction {
    private static final int MAX_TICKS = 20 * 180; // 3 minutes
    private static final int MAX_ATTEMPTS = 4;
    private static final int STATION_SCAN_RADIUS = 10;
    private static final int STATION_FAR_SCAN_RADIUS = 96;
    private static final int STATION_OWNED_SCAN_RADIUS = 256;
    private static final double STATION_INTERACT_RANGE_SQR = 20.25; // ~4.5 blocks
    private static final int RETRY_DELAY_TICKS = 10;
    private static final int MAX_STATION_SETUP_ATTEMPTS = 3;

    private String itemName;
    private String targetItemId;
    private int quantity;
    private int attempt;
    private int ticksRunning;
    private int produced;
    private int lastProgressTick;
    private Item targetItem;
    private Item inputItem;
    private int inputNeededTotal;
    private BlockPos stationPos;

    public SmeltItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = positiveOrDefault(task.getIntParameter("quantity", -1), task.getIntParameter("count", 1));
        attempt = Math.max(0, task.getIntParameter("smelt_attempt", 0));
        ticksRunning = 0;
        produced = 0;
        lastProgressTick = 0;

        if (itemName == null || itemName.isBlank() || quantity <= 0) {
            result = ActionResult.failure("Missing item to smelt");
            return;
        }

        targetItem = parseItem(itemName);
        if (targetItem == null) {
            result = ActionResult.failure("Unknown smelt item: " + itemName);
            return;
        }
        targetItemId = BuiltInRegistries.ITEM.getKey(targetItem).toString();
        int existing = steve.getItemCount(targetItem);
        int stillNeeded = Math.max(0, quantity - existing);
        if (stillNeeded <= 0) {
            result = ActionResult.success("Already have " + existing + " " + itemNameFromId(targetItemId));
            return;
        }

        CraftingPlanner.RecipeSpec recipe = CraftingRecipeRegistry.getRecipe(targetItemId);
        if (recipe == null || recipe.station() != CraftingStation.FURNACE) {
            result = ActionResult.failure("No furnace recipe for: " + itemName);
            return;
        }

        Map<Item, Integer> requiredInputs = resolveDirectRequirements(recipe, stillNeeded);
        if (requiredInputs.isEmpty()) {
            result = ActionResult.failure("Invalid smelt recipe inputs for: " + itemName);
            return;
        }
        Map.Entry<Item, Integer> selected = selectBestInput(requiredInputs);
        inputItem = selected.getKey();
        inputNeededTotal = selected.getValue();

        if (!ensureStationReady()) {
            return;
        }

        if (!ensureInputAndFuelReady(stillNeeded)) {
            return;
        }

        prepareFurnace();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Smelting timeout for " + itemName + " (" + produced + "/" + quantity + ")");
            return;
        }
        if (stationPos == null || steve.level().getBlockState(stationPos).isAir()) {
            if (attempt >= MAX_ATTEMPTS) {
                result = ActionResult.failure("Smelting station disappeared for " + itemName);
                return;
            }
            enqueueRetry(attempt + 1);
            result = ActionResult.success("Station missing; queued smelt retry for " + itemName);
            return;
        }

        double distSqr = steve.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5);
        if (distSqr > STATION_INTERACT_RANGE_SQR) {
            steve.getNavigation().moveTo(stationPos.getX() + 0.5, stationPos.getY(), stationPos.getZ() + 0.5, 1.1);
            return;
        }
        steve.getLookControl().setLookAt(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5);

        Container furnace = getContainerAt(stationPos);
        if (furnace == null) {
            if (attempt >= MAX_ATTEMPTS) {
                result = ActionResult.failure("Failed to access furnace container for " + itemName);
                return;
            }
            enqueueRetry(attempt + 1);
            result = ActionResult.success("Furnace access failed; queued retry for " + itemName);
            return;
        }

        int extracted = extractOutput(furnace);
        if (extracted > 0) {
            produced += extracted;
            lastProgressTick = ticksRunning;
            SteveMod.LOGGER.info(
                "[SMELT] Steve '{}' extracted {} {} (progress={}/{})",
                steve.getSteveName(),
                extracted,
                itemNameFromId(targetItemId),
                produced,
                quantity
            );
        }

        int currentTotal = steve.getItemCount(targetItem);
        if (currentTotal >= quantity || produced >= quantity) {
            result = ActionResult.success("Smelted " + Math.max(produced, quantity) + " " + itemNameFromId(targetItemId));
            return;
        }

        if (ticksRunning % RETRY_DELAY_TICKS == 0) {
            topUpInputAndFuel(furnace);
        }

        if (ticksRunning - lastProgressTick > 20 * 25) {
            if (attempt >= MAX_ATTEMPTS) {
                result = ActionResult.failure("Smelting stalled for " + itemName + " (" + produced + "/" + quantity + ")");
                return;
            }
            enqueueRetry(attempt + 1);
            result = ActionResult.success("Smelting stalled; queued retry for " + itemName);
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Smelt " + quantity + " " + itemName;
    }

    private boolean ensureStationReady() {
        BlockPos owned = steve.findNearestOwnedStation("minecraft:furnace", steve.blockPosition(), STATION_OWNED_SCAN_RADIUS);
        if (owned == null) {
            owned = steve.findNearestOwnedStation("minecraft:blast_furnace", steve.blockPosition(), STATION_OWNED_SCAN_RADIUS);
        }
        if (owned != null) {
            stationPos = owned;
        } else {
            stationPos = findBestSmeltStationPos(STATION_SCAN_RADIUS);
            if (stationPos == null) {
                stationPos = findBestSmeltStationPos(STATION_FAR_SCAN_RADIUS);
            }
        }
        if (stationPos != null) {
            double distSqr = steve.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5);
            if (distSqr > STATION_INTERACT_RANGE_SQR) {
                boolean queuedPath = steve.getActionExecutor().tryEnqueueTask(new Task("pathfind", Map.of(
                    "x", stationPos.getX(),
                    "y", stationPos.getY(),
                    "z", stationPos.getZ(),
                    "range", 5
                )));
                if (!queuedPath) {
                    queueStationSetup();
                    result = ActionResult.success("Station path blocked; queued local furnace setup for " + itemName);
                    return false;
                }
                enqueueRetry(attempt);
                result = ActionResult.success("Moving to smelt station for " + itemName);
                return false;
            }
            return true;
        }

        if (attempt >= MAX_ATTEMPTS) {
            result = ActionResult.failure("Missing required smelt station (furnace)");
            return false;
        }

        queueStationSetup();
        result = ActionResult.success("Queued furnace setup for smelting " + itemName);
        return false;
    }

    private boolean ensureInputAndFuelReady(int stillNeeded) {
        int haveInput = steve.getItemCount(inputItem);
        if (haveInput < inputNeededTotal) {
            if (attempt >= MAX_ATTEMPTS) {
                result = ActionResult.failure(
                    "Missing smelt input: " + itemNameFromId(BuiltInRegistries.ITEM.getKey(inputItem).toString())
                    + " (" + haveInput + "/" + inputNeededTotal + ")"
                );
                return false;
            }
            queueAcquireItem(BuiltInRegistries.ITEM.getKey(inputItem).toString(), inputNeededTotal - haveInput);
            enqueueRetry(attempt + 1);
            result = ActionResult.success("Queued smelt input acquisition for " + itemName);
            return false;
        }

        int requiredFuelUnits = Math.max(1, stillNeeded);
        int availableFuelUnits = estimateFuelUnitsInInventory();
        if (availableFuelUnits < requiredFuelUnits) {
            if (attempt >= MAX_ATTEMPTS) {
                result = ActionResult.failure("Missing smelt fuel for " + itemName + " (need units=" + requiredFuelUnits + ")");
                return false;
            }
            queueAcquireFuel(requiredFuelUnits - availableFuelUnits);
            enqueueRetry(attempt + 1);
            result = ActionResult.success("Queued fuel acquisition for smelting " + itemName);
            return false;
        }
        return true;
    }

    private void prepareFurnace() {
        if (stationPos == null) {
            result = ActionResult.failure("No furnace found for " + itemName);
            return;
        }
        Container furnace = getContainerAt(stationPos);
        if (furnace == null) {
            result = ActionResult.failure("Cannot access furnace for " + itemName);
            return;
        }
        topUpInputAndFuel(furnace);
        lastProgressTick = 0;
        SteveMod.LOGGER.info(
            "[SMELT] Steve '{}' started smelting item={} quantity={} input={} station={}",
            steve.getSteveName(),
            targetItemId,
            quantity,
            BuiltInRegistries.ITEM.getKey(inputItem),
            stationPos
        );
    }

    private void topUpInputAndFuel(Container furnace) {
        if (furnace == null) {
            return;
        }
        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty() && output.getItem() != targetItem) {
            return;
        }

        // Input slot (0)
        ItemStack inputSlot = furnace.getItem(0);
        int neededInputInFurnace = Math.max(0, quantity - produced);
        if (neededInputInFurnace > 0) {
            if (inputSlot.isEmpty()) {
                ItemStack moved = steve.extractItem(inputItem, Math.min(neededInputInFurnace, 16));
                if (!moved.isEmpty()) {
                    furnace.setItem(0, moved);
                    furnace.setChanged();
                }
            } else if (inputSlot.getItem() == inputItem) {
                int space = inputSlot.getMaxStackSize() - inputSlot.getCount();
                if (space > 0) {
                    ItemStack moved = steve.extractItem(inputItem, Math.min(space, Math.min(neededInputInFurnace, 16)));
                    if (!moved.isEmpty()) {
                        inputSlot.grow(moved.getCount());
                        furnace.setItem(0, inputSlot);
                        furnace.setChanged();
                    }
                }
            }
        }

        // Fuel slot (1) with basic policy.
        ItemStack fuelSlot = furnace.getItem(1);
        int neededFuelUnits = Math.max(1, quantity - produced);
        int existingFuelUnits = fuelUnitsOfStack(fuelSlot);
        int missingFuelUnits = Math.max(0, neededFuelUnits - existingFuelUnits);
        if (missingFuelUnits <= 0) {
            return;
        }

        Item fuelItemToUse = chooseFuelItemFromInventory(fuelSlot.isEmpty() ? null : fuelSlot.getItem());
        if (fuelItemToUse == null) {
            return;
        }
        int unitPerItem = fuelUnitForItem(BuiltInRegistries.ITEM.getKey(fuelItemToUse).toString());
        if (unitPerItem <= 0) {
            return;
        }
        int itemsNeeded = Math.max(1, (int) Math.ceil((double) missingFuelUnits / unitPerItem));
        if (fuelSlot.isEmpty()) {
            ItemStack moved = steve.extractItem(fuelItemToUse, itemsNeeded);
            if (!moved.isEmpty()) {
                furnace.setItem(1, moved);
                furnace.setChanged();
            }
            return;
        }
        if (fuelSlot.getItem() != fuelItemToUse) {
            return;
        }
        int space = fuelSlot.getMaxStackSize() - fuelSlot.getCount();
        if (space <= 0) {
            return;
        }
        ItemStack moved = steve.extractItem(fuelItemToUse, Math.min(space, itemsNeeded));
        if (moved.isEmpty()) {
            return;
        }
        fuelSlot.grow(moved.getCount());
        furnace.setItem(1, fuelSlot);
        furnace.setChanged();
    }

    private int extractOutput(Container furnace) {
        ItemStack output = furnace.getItem(2);
        if (output == null || output.isEmpty() || output.getItem() != targetItem) {
            return 0;
        }
        int moveCount = Math.min(output.getCount(), Math.max(1, quantity - produced));
        ItemStack moved = output.copy();
        moved.setCount(moveCount);
        ItemStack remainder = steve.addToInventory(moved);
        int inserted = moveCount - remainder.getCount();
        if (inserted <= 0) {
            return 0;
        }
        output.shrink(inserted);
        if (output.isEmpty()) {
            furnace.setItem(2, ItemStack.EMPTY);
        } else {
            furnace.setItem(2, output);
        }
        furnace.setChanged();
        return inserted;
    }

    private BlockPos findBestSmeltStationPos(int radius) {
        BlockPos origin = steve.blockPosition();
        boolean preferBlastFurnace = isOreLikeSmelt();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    Block block = steve.level().getBlockState(pos).getBlock();
                    boolean isFurnace = block == Blocks.FURNACE;
                    boolean isBlast = block == Blocks.BLAST_FURNACE;
                    if (!isFurnace && !isBlast) {
                        continue;
                    }
                    if (!isStationReachable(pos)) {
                        continue;
                    }
                    Container container = getContainerAt(pos);
                    if (container == null) {
                        continue;
                    }
                    ItemStack out = container.getItem(2);
                    if (!out.isEmpty() && out.getItem() != targetItem) {
                        continue;
                    }

                    double dist = origin.distSqr(pos);
                    if (best == null) {
                        best = pos.immutable();
                        bestDist = dist;
                        continue;
                    }
                    Block bestBlock = steve.level().getBlockState(best).getBlock();
                    boolean bestIsBlast = bestBlock == Blocks.BLAST_FURNACE;
                    if (preferBlastFurnace && isBlast && !bestIsBlast) {
                        best = pos.immutable();
                        bestDist = dist;
                        continue;
                    }
                    if (dist < bestDist) {
                        best = pos.immutable();
                        bestDist = dist;
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

    private boolean isOreLikeSmelt() {
        if (inputItem == null) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(inputItem).getPath();
        return path.endsWith("_ore") || path.startsWith("raw_");
    }

    private void queueStationSetup() {
        Item furnaceItem = parseItem("minecraft:furnace");
        int hasFurnace = furnaceItem == null ? 0 : steve.getItemCount(furnaceItem);
        int stationAttempt = Math.max(0, task.getIntParameter("station_attempt", 0));
        if (stationAttempt >= MAX_STATION_SETUP_ATTEMPTS) {
            return;
        }
        if (steve.getActionExecutor().hasQueuedTask("craft", "item", "furnace")
            || steve.getActionExecutor().hasQueuedTask("place", "block", "furnace")) {
            return;
        }

        boolean queuedAny = false;
        if (hasFurnace <= 0) {
            steve.getActionExecutor().enqueueTask(new Task("craft", Map.of(
                "item", "furnace",
                "quantity", 1,
                "planned", false,
                "station_attempt", attempt + 1
            )));
            queuedAny = true;
        } else {
            BlockPos placePos = findNearbyPlacementPos();
            if (placePos != null) {
                steve.getActionExecutor().enqueueTask(new Task("place", Map.of(
                    "block", "furnace",
                    "x", placePos.getX(),
                    "y", placePos.getY(),
                    "z", placePos.getZ(),
                    "owned_station", true,
                    "place_attempt", 0
                )));
                queuedAny = true;
            }
        }
        if (queuedAny) {
            enqueueRetryWithStationAttempt(attempt + 1, stationAttempt + 1);
        }
    }

    private void enqueueRetryWithStationAttempt(int nextAttempt, int stationAttempt) {
        Map<String, Object> retry = new HashMap<>(task.getParameters());
        retry.put("item", itemName);
        retry.put("quantity", quantity);
        retry.put("smelt_attempt", nextAttempt);
        retry.put("station_attempt", stationAttempt);
        steve.getActionExecutor().enqueueTask(new Task("smelt", retry));
    }

    private void queueAcquireFuel(int missingFuelUnits) {
        if (missingFuelUnits <= 0) {
            return;
        }

        // Strategy switch:
        // attempt 0-1 => prefer coal path, attempt >=2 => prefer wood-log fallback fuel.
        boolean preferLogs = attempt >= 2;
        String primaryFuel = preferLogs ? FuelPolicy.OAK_LOG : FuelPolicy.COAL;
        int primaryUnits = Math.max(1, FuelPolicy.fuelUnitsForItem(primaryFuel));
        int primaryNeeded = Math.max(1, (int) Math.ceil((double) missingFuelUnits / primaryUnits));
        queueAcquireItem(primaryFuel, primaryNeeded);

        // Add one fallback gather in the other fuel family to avoid deadlock on single-source scarcity.
        String secondaryFuel = preferLogs ? FuelPolicy.COAL : FuelPolicy.OAK_LOG;
        int secondaryUnits = Math.max(1, FuelPolicy.fuelUnitsForItem(secondaryFuel));
        int secondaryNeeded = Math.max(1, (int) Math.ceil((double) missingFuelUnits / secondaryUnits));
        queueAcquireItem(secondaryFuel, secondaryNeeded);

        SteveMod.LOGGER.info(
            "[SMELT] Steve '{}' queued fuel acquisition (missingUnits={} primary={} secondary={} attempt={})",
            steve.getSteveName(),
            missingFuelUnits,
            primaryFuel,
            secondaryFuel,
            attempt
        );
    }

    private void queueAcquireItem(String itemId, int quantityNeeded) {
        if (quantityNeeded <= 0) {
            return;
        }
        int chestRadius = KnownResourceManager.configuredChestRadius();
        int inKnownChests = KnownResourceManager.knownChestItemCount(steve, itemId, chestRadius, true);
        int fromChest = Math.min(quantityNeeded, Math.max(0, inKnownChests));
        if (fromChest > 0) {
            steve.getActionExecutor().enqueueTask(new Task("retrieve_chest", Map.of(
                "item", itemNameFromId(itemId),
                "quantity", fromChest,
                "radius", chestRadius,
                "fallback_to_gather", true
            )));
        }

        int toGather = Math.max(0, quantityNeeded - fromChest);
        if (toGather <= 0) {
            return;
        }
        List<ResourceTargetResolver.Candidate> candidates = ResourceTargetResolver.gatherCandidateEntriesForItemId(itemId);
        if (candidates.isEmpty()) {
            return;
        }
        ResourceTargetResolver.Candidate preferred = ResourceTargetResolver.chooseBestVisibleCandidate(steve, candidates);
        String primary = preferred != null ? pathOf(preferred.blockId()) : pathOf(candidates.get(0).blockId());
        if (primary == null || primary.isBlank()) {
            return;
        }
        List<String> candidateIds = new ArrayList<>();
        for (ResourceTargetResolver.Candidate c : candidates) {
            candidateIds.add(c.blockId());
        }
        List<String> alternatives = new ArrayList<>(ResourceTargetResolver.toPathList(candidateIds));
        alternatives.remove(primary);
        steve.getActionExecutor().enqueueTask(new Task("gather", Map.of(
            "resource", primary,
            "quantity", toGather,
            "alternatives", alternatives
        )));
    }

    private void enqueueRetry(int nextAttempt) {
        Map<String, Object> retry = new HashMap<>(task.getParameters());
        retry.put("item", itemName);
        retry.put("quantity", quantity);
        retry.put("smelt_attempt", nextAttempt);
        steve.getActionExecutor().enqueueTask(new Task("smelt", retry));
    }

    private Container getContainerAt(BlockPos pos) {
        if (pos == null || !(steve.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = serverLevel.getBlockEntity(pos);
        if (be instanceof Container container) {
            return container;
        }
        return null;
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

    private int fuelUnitsOfStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.getCount() * fuelUnitForItem(id);
    }

    private int fuelUnitForItem(String itemId) {
        return FuelPolicy.fuelUnitsForItem(itemId);
    }

    private Item chooseFuelItemFromInventory(Item pinnedItem) {
        if (pinnedItem != null && steve.getItemCount(pinnedItem) > 0) {
            return pinnedItem;
        }
        for (String fuelId : FuelPolicy.inventoryFuelPriority()) {
            Item fuel = parseItem(fuelId);
            if (fuel != null && steve.getItemCount(fuel) > 0) {
                return fuel;
            }
        }
        return null;
    }

    private Map<Item, Integer> resolveDirectRequirements(CraftingPlanner.RecipeSpec recipe, int targetQuantity) {
        int batches = (int) Math.ceil((double) targetQuantity / recipe.outputCount());
        Map<Item, Integer> required = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : recipe.inputs().entrySet()) {
            Item input = parseItem(entry.getKey());
            if (input == null) {
                continue;
            }
            required.put(input, entry.getValue() * batches);
        }
        return required;
    }

    private Map.Entry<Item, Integer> selectBestInput(Map<Item, Integer> requiredInputs) {
        Map.Entry<Item, Integer> best = null;
        int bestHave = -1;
        for (Map.Entry<Item, Integer> entry : requiredInputs.entrySet()) {
            int have = steve.getItemCount(entry.getKey());
            if (best == null || have > bestHave) {
                best = entry;
                bestHave = have;
            }
        }
        return best;
    }

    private Item parseItem(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = normalizeId(name);
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private String normalizeId(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
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

    private int positiveOrDefault(int preferred, int fallback) {
        if (preferred > 0) {
            return preferred;
        }
        return Math.max(1, fallback);
    }
}
