package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.farming.FarmTargetResolver;
import com.steve.ai.mining.ToolCapabilityMap.ToolType;
import com.steve.ai.resource.KnownResourceManager;
import com.steve.ai.validation.MinecraftLegalityChecker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FarmCropAction extends BaseAction {
    private static final int MAX_TICKS = 12000;
    private static final int MAX_PREP_ATTEMPTS = 3;
    private static final int FARM_HYDRATION_RADIUS = 4;
    private static final int WATER_SEARCH_RADIUS = 48;

    private static final Set<Block> TILLABLE_BLOCKS = Set.of(
        Blocks.DIRT,
        Blocks.GRASS_BLOCK,
        Blocks.COARSE_DIRT,
        Blocks.ROOTED_DIRT,
        Blocks.DIRT_PATH
    );

    private String cropName;
    private int targetQuantity;
    private int radius;
    private int ticksRunning;
    private int actionCooldown;
    private Item produceItem;
    private Item seedItem;
    private Block cropBlock;
    private int startProduceCount;
    private int prepAttempt;
    private boolean preflightDone;
    private Item waterBucketItem;
    private Item bucketItem;

    public FarmCropAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        cropName = task.getStringParameter("crop", "wheat");
        targetQuantity = positiveOrDefault(task.getIntParameter("quantity", -1), task.getIntParameter("count", 8));
        radius = Math.max(3, Math.min(task.getIntParameter("radius", 10), 32));
        ticksRunning = 0;
        actionCooldown = 0;
        prepAttempt = Math.max(0, task.getIntParameter("prep_attempt", 0));
        preflightDone = false;

        FarmTargetResolver.CropSpec spec = FarmTargetResolver.resolve(cropName);
        produceItem = parseItem(spec.produceItemId());
        seedItem = parseItem(spec.seedItemId());
        cropBlock = parseBlock(spec.cropBlockId());
        waterBucketItem = parseItem("minecraft:water_bucket");
        bucketItem = parseItem("minecraft:bucket");
        if (produceItem == null || seedItem == null || cropBlock == null) {
            SteveMod.LOGGER.warn("[FARM] Steve '{}' unsupported crop profile '{}'", steve.getSteveName(), cropName);
            result = ActionResult.failure("Unsupported crop profile: " + cropName);
            return;
        }
        SteveMod.LOGGER.info(
            "[FARM] Steve '{}' start crop={} quantity={} radius={} prepAttempt={}",
            steve.getSteveName(),
            cropName,
            targetQuantity,
            radius,
            prepAttempt
        );
        startProduceCount = steve.getItemCount(produceItem);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Farming timeout for " + cropName + " (" + producedAmount() + "/" + targetQuantity + ")");
            return;
        }
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (!preflightDone) {
            preflightDone = true;
            if (prepareIfNeeded(serverLevel)) {
                return;
            }
            SteveMod.LOGGER.info("[FARM] Steve '{}' preflight passed for crop={}", steve.getSteveName(), cropName);
        }

        if (producedAmount() >= targetQuantity) {
            result = ActionResult.success("Farmed " + producedAmount() + " " + cropName);
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        BlockPos matureCrop = findNearestMatureCrop(serverLevel);
        if (matureCrop != null) {
            if (!moveIfFar(matureCrop, 4.0)) {
                serverLevel.destroyBlock(matureCrop, true, steve);
                actionCooldown = 8;
            }
            return;
        }

        BlockPos plantSpot = findNearestPlantSpot(serverLevel);
        if (plantSpot != null && steve.hasItem(seedItem, 1)) {
            if (!moveIfFar(plantSpot, 4.0)) {
                BlockPos cropPos = plantSpot.above();
                if (serverLevel.getBlockState(cropPos).isAir()) {
                    serverLevel.setBlockAndUpdate(cropPos, cropBlock.defaultBlockState());
                    steve.consumeItem(seedItem, 1);
                    actionCooldown = 8;
                }
            }
            return;
        }

        BlockPos tillSpot = findNearestTillableSpot(serverLevel);
        if (tillSpot == null) {
            if (tryPlaceWaterSource(serverLevel, steve.blockPosition())) {
                actionCooldown = 8;
                return;
            }
            BlockPos nearestWater = findNearestWater(serverLevel, steve.blockPosition(), Math.max(WATER_SEARCH_RADIUS, radius * 3));
            if (nearestWater != null && moveIfFar(nearestWater, 16.0)) {
                SteveMod.LOGGER.info("[FARM] Steve '{}' moving toward water at {}", steve.getSteveName(), nearestWater);
                actionCooldown = 8;
                return;
            }
            SteveMod.LOGGER.warn("[FARM] Steve '{}' no hydrated farmland spots for crop={}", steve.getSteveName(), cropName);
            result = ActionResult.failure("No hydrated farmland spots found for " + cropName);
            return;
        }

        if (!moveIfFar(tillSpot, 4.0)) {
            if (!MinecraftLegalityChecker.canTillFarmlandNow(steve)) {
                SteveMod.LOGGER.warn("[FARM] Steve '{}' missing hoe during till; queuing prerequisites", steve.getSteveName());
                enqueuePrerequisiteTasks(true, !steve.hasItem(seedItem, 1), false, null);
                result = ActionResult.success("Missing hoe. Queued crafting before farming.");
                return;
            }
            ItemStack hoe = steve.findToolInInventory(ToolType.HOE);
            if (!hoe.isEmpty()) {
                steve.setItemInHand(InteractionHand.MAIN_HAND, hoe);
            }
            BlockPos cropPos = tillSpot.above();
            if (serverLevel.getBlockState(cropPos).isAir()) {
                serverLevel.setBlockAndUpdate(tillSpot, Blocks.FARMLAND.defaultBlockState());
                actionCooldown = 6;
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Farm " + targetQuantity + " " + cropName;
    }

    private int producedAmount() {
        return Math.max(0, steve.getItemCount(produceItem) - startProduceCount);
    }

    private boolean moveIfFar(BlockPos pos, double maxDistanceSqr) {
        double distance = steve.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (distance <= maxDistanceSqr) {
            return false;
        }
        steve.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
        return true;
    }

    private BlockPos findNearestMatureCrop(ServerLevel level) {
        BlockPos origin = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() != cropBlock) {
                        continue;
                    }
                    if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < bestDist) {
                        best = pos.immutable();
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearestPlantSpot(ServerLevel level) {
        BlockPos origin = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (level.getBlockState(pos).getBlock() != Blocks.FARMLAND) {
                        continue;
                    }
                    if (!hasNearbyWater(level, pos, FARM_HYDRATION_RADIUS)) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < bestDist) {
                        best = pos.immutable();
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearestTillableSpot(ServerLevel level) {
        return findNearestTillableSpot(level, true);
    }

    private BlockPos findNearestTillableSpot(ServerLevel level, boolean requireWaterNearby) {
        BlockPos origin = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!TILLABLE_BLOCKS.contains(level.getBlockState(pos).getBlock())) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    if (requireWaterNearby && !hasNearbyWater(level, pos, FARM_HYDRATION_RADIUS)) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < bestDist) {
                        best = pos.immutable();
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    private Item parseItem(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private Block parseBlock(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private int positiveOrDefault(int preferred, int fallback) {
        if (preferred > 0) {
            return preferred;
        }
        return Math.max(1, fallback);
    }

    private boolean prepareIfNeeded(ServerLevel level) {
        boolean missingHoe = !MinecraftLegalityChecker.canTillFarmlandNow(steve);
        boolean missingSeeds = !steve.hasItem(seedItem, 1) && findNearestMatureCrop(level) == null;
        boolean missingWater = !hasNearbyWater(level, steve.blockPosition(), Math.max(FARM_HYDRATION_RADIUS + 1, radius));

        SteveMod.LOGGER.info(
            "[FARM] Steve '{}' preflight crop={} missingHoe={} missingSeeds={} missingWater={}",
            steve.getSteveName(),
            cropName,
            missingHoe,
            missingSeeds,
            missingWater
        );

        if (!missingHoe && !missingSeeds && !missingWater) {
            return false;
        }

        if (prepAttempt >= MAX_PREP_ATTEMPTS) {
            SteveMod.LOGGER.warn(
                "[FARM] Steve '{}' preflight exhausted retries crop={} attempts={}",
                steve.getSteveName(),
                cropName,
                prepAttempt
            );
            result = ActionResult.failure(
                "Farming prerequisites unresolved after retries (hoe="
                    + missingHoe + ", seeds=" + missingSeeds + ", water=" + missingWater + ")"
            );
            return true;
        }

        BlockPos nearestWater = missingWater
            ? findNearestWater(level, steve.blockPosition(), Math.max(WATER_SEARCH_RADIUS, radius * 3))
            : null;

        if (missingWater && nearestWater == null && !hasWaterBucket()) {
            SteveMod.LOGGER.warn(
                "[FARM] Steve '{}' no irrigation path crop={} (no nearby water, no water bucket)",
                steve.getSteveName(),
                cropName
            );
            result = ActionResult.failure("No nearby water and no water bucket for irrigation.");
            return true;
        }

        if (missingWater && nearestWater == null) {
            if (!tryPlaceWaterSource(level, steve.blockPosition())) {
                SteveMod.LOGGER.warn("[FARM] Steve '{}' failed to place water source for crop={}", steve.getSteveName(), cropName);
                result = ActionResult.failure("Failed to place water source for farming.");
                return true;
            }
            SteveMod.LOGGER.info("[FARM] Steve '{}' placed water source for crop={}", steve.getSteveName(), cropName);
            return false;
        }

        SteveMod.LOGGER.info(
            "[FARM] Steve '{}' queue prerequisites crop={} hoe={} seeds={} waterPath={}",
            steve.getSteveName(),
            cropName,
            missingHoe,
            missingSeeds,
            missingWater && nearestWater != null
        );
        enqueuePrerequisiteTasks(missingHoe, missingSeeds, missingWater, nearestWater);
        result = ActionResult.success("Preparing farm prerequisites (hoe/seeds/water).");
        return true;
    }

    private void enqueuePrerequisiteTasks(
        boolean missingHoe,
        boolean missingSeeds,
        boolean missingWater,
        BlockPos nearestWater
    ) {
        var queue = steve.getActionExecutor();
        if (missingHoe) {
            queue.enqueueTask(new Task("craft", Map.of(
                "item", pathOf(FarmTargetResolver.defaultHoeItemId()),
                "quantity", 1
            )));
        }
        if (missingSeeds) {
            String seedResourceId = FarmTargetResolver.seedGatherResourceId(FarmTargetResolver.resolve(cropName));
            int seedTarget = Math.max(4, Math.min(16, targetQuantity));
            int chestRadius = KnownResourceManager.configuredChestRadius();
            queue.enqueueTask(new Task("retrieve_chest", Map.of(
                "item", pathOf(seedResourceId),
                "quantity", seedTarget,
                "radius", chestRadius,
                "fallback_to_gather", true
            )));
            queue.enqueueTask(new Task("gather", Map.of(
                "resource", pathOf(seedResourceId),
                "quantity", seedTarget
            )));
        }
        if (missingWater && nearestWater != null) {
            queue.enqueueTask(new Task("pathfind", Map.of(
                "x", nearestWater.getX(),
                "y", nearestWater.getY(),
                "z", nearestWater.getZ()
            )));
        }
        queue.enqueueTask(farmRetryTask());
    }

    private Task farmRetryTask() {
        Map<String, Object> retry = new HashMap<>(task.getParameters());
        retry.put("crop", cropName);
        retry.put("quantity", targetQuantity);
        retry.put("radius", radius);
        retry.put("prep_attempt", prepAttempt + 1);
        return new Task("farm", retry);
    }

    private boolean hasNearbyWater(ServerLevel level, BlockPos pos, int searchRadius) {
        int r = Math.max(1, searchRadius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (level.getFluidState(check).is(Fluids.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findNearestWater(ServerLevel level, BlockPos origin, int searchRadius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int r = Math.max(1, searchRadius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos check = origin.offset(dx, dy, dz);
                    if (!level.getFluidState(check).is(Fluids.WATER)) {
                        continue;
                    }
                    double dist = origin.distSqr(check);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = check.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean hasWaterBucket() {
        return waterBucketItem != null && steve.hasItem(waterBucketItem, 1);
    }

    private boolean tryPlaceWaterSource(ServerLevel level, BlockPos anchor) {
        if (!hasWaterBucket()) {
            return false;
        }

        BlockPos focus = findNearestTillableSpot(level, false);
        if (focus == null) {
            focus = anchor;
        }
        BlockPos place = findWaterPlacementSpot(level, focus);
        if (place == null) {
            SteveMod.LOGGER.warn("[FARM] Steve '{}' could not find valid water placement near {}", steve.getSteveName(), focus);
            return false;
        }

        level.setBlockAndUpdate(place, Blocks.WATER.defaultBlockState());
        steve.consumeItem(waterBucketItem, 1);
        if (bucketItem != null) {
            steve.addToInventory(new ItemStack(bucketItem, 1));
        }
        SteveMod.LOGGER.info("[FARM] Steve '{}' placed water source at {}", steve.getSteveName(), place);
        return true;
    }

    private BlockPos findWaterPlacementSpot(ServerLevel level, BlockPos center) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos pos = center.offset(dx, 0, dz);
                BlockState state = level.getBlockState(pos);
                BlockState below = level.getBlockState(pos.below());
                if (!state.isAir()) {
                    continue;
                }
                if (below.isAir() || below.getFluidState().is(Fluids.WATER)) {
                    continue;
                }
                return pos.immutable();
            }
        }
        return null;
    }

    private String pathOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "unknown";
        }
        int idx = itemId.indexOf(':');
        if (idx < 0 || idx >= itemId.length() - 1) {
            return itemId;
        }
        return itemId.substring(idx + 1);
    }
}
