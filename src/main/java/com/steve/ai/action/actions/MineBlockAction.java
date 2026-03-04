package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.mining.BlockTransitionMap;
import com.steve.ai.mining.ToolCapabilityMap;
import com.steve.ai.mining.ToolCapabilityMap.ToolRequirement;
import com.steve.ai.mining.ToolCapabilityMap.ToolType;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MineBlockAction extends BaseAction {
    private Block targetBlock;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTarget;
    private int ticksRunning;
    private int ticksSinceLastTorch = 0;
    private int ticksSinceLastMine = 0; // Delay between mining blocks
    private static final int MAX_TICKS = 24000; // 20 minutes for deep mining
    private static final int TORCH_INTERVAL = 100; // Place torch every 5 seconds (100 ticks)
    private static final int MIN_LIGHT_LEVEL = 8;
    private static final int MINING_DELAY = 10;
    private static final int BASE_SCAN_RADIUS = 6;
    private static final int MAX_SCAN_RADIUS = 14;
    private static final int SEARCH_EXPAND_INTERVAL = 40;
    private static final int EXPLORE_RETRY_INTERVAL = 40;
    private static final int EXPLORE_BASE_RADIUS = 12;
    private static final int EXPLORE_MAX_RADIUS = 72;
    private static final int EXPLORE_RADIUS_STEP = 8;
    private static final int DIRECTED_EXPLORE_SCAN_RADIUS = 24;
    private static final int SEARCH_IDLE_FORCE_MOVE_TICKS = 60;
    private static final double SEARCH_IDLE_MOVE_EPSILON_SQR = 0.04; // ~0.2 blocks
    private static final int RESCAN_INTERVAL = 2;
    private static final int TARGET_STALL_TICKS = 60;
    private static final int MINING_DURATION = 40;
    private static final double MINING_INTERACTION_RANGE_SQR = 25.0; // ~5 blocks like normal player reach
    private static final int BLOCKED_TARGET_TTL = 80;
    private static final int BLOCKED_TARGET_TTL_ORE = 20 * 90;
    private static final int BLOCKED_CLUSTER_RADIUS = 1;
    private static final int MIN_EXPLORE_MOVE_DISTANCE_SQR = 9; // >= 3 blocks
    private static final int MOVEMENT_STUCK_REPATH_TICKS = 20;
    private static final int MOVEMENT_STUCK_SHORE_TICKS = 50;
    private static final int WATER_REPLAN_INTERVAL = 20;
    private static final int WATER_SHORE_RECOVERY_RADIUS = 6;
    private static final int WATER_SHORE_PROBE_Y = 2;
    private BlockPos exploreTarget;
    private int searchIdleTicks = 0;
    private double lastSearchX;
    private double lastSearchY;
    private double lastSearchZ;
    private int ticksSinceExplore = 0;
    private int ticksSinceRescan = 0;
    private int targetStallTicks = 0;
    private double lastTargetDistance = Double.MAX_VALUE;
    private int miningProgressTicks = 0;
    private BlockPos lastMiningTarget = null;
    private int targetRecoveryAttempts = 0;
    private static final int MAX_TARGET_RECOVERY_ATTEMPTS = 3;
    private static final int NO_TARGET_ABORT_TICKS = 20 * 20; // 20s without any valid target
    private static final int COAL_SWITCH_TO_DEEP_SEARCH_TICKS = 20 * 30;
    private static final int COAL_BASE_SCAN_RADIUS = 10;
    private static final int COAL_BASE_EXPLORE_RADIUS = 24;
    private static final int COAL_MAX_EXPLORE_RADIUS = 112;
    private int movementStuckTicks = 0;
    private int noTargetTicks = 0;
    private int waterReplanCooldown = 0;
    private boolean coalDeepSearchMode = false;
    private boolean lastInWater = false;
    private int waterSegmentTicks = 0;
    private BlockPos lastWaterRecoveryTarget;
    private double lastMoveX;
    private double lastMoveY;
    private double lastMoveZ;
    private String targetBlockId;
    private boolean genericWoodRequest;
    private Set<String> acceptableBlockIds = new HashSet<>();
    private Set<String> progressBlockIds = new HashSet<>();
    private int searchTicks = 0;
    private int scanRadius = BASE_SCAN_RADIUS;
    private int exploreRadius = EXPLORE_BASE_RADIUS;
    private final Map<BlockPos, Integer> blockedTargets = new HashMap<>();
    private final Deque<BlockPos> obstructionQueue = new ArrayDeque<>();
    private final Set<BlockPos> obstructionSet = new HashSet<>();
    private BlockPos lastMinedReference;
    private BlockPos actualTarget;
    private ToolRequirement toolRequirement = ToolRequirement.NONE;
    private static final List<String> WOOD_PRIMARY_BLOCK_IDS = List.of(
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
    
    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = firstNonBlank(
            task.getStringParameter("block"),
            task.getStringParameter("blockType"),
            task.getStringParameter("resource")
        );
        targetQuantity = positiveOrDefault(task.getIntParameter("quantity", -1), task.getIntParameter("count", 8));
        minedCount = 0;
        ticksRunning = 0;
        ticksSinceLastTorch = 0;
        ticksSinceLastMine = 0;
        ticksSinceExplore = 0;
        ticksSinceRescan = 0;
        targetStallTicks = 0;
        lastTargetDistance = Double.MAX_VALUE;
        miningProgressTicks = 0;
        lastMiningTarget = null;
        targetRecoveryAttempts = 0;
        movementStuckTicks = 0;
        noTargetTicks = 0;
        waterReplanCooldown = 0;
        coalDeepSearchMode = false;
        lastInWater = steve.isInWater();
        waterSegmentTicks = 0;
        lastWaterRecoveryTarget = null;
        lastMoveX = steve.getX();
        lastMoveY = steve.getY();
        lastMoveZ = steve.getZ();
        searchTicks = 0;
        scanRadius = BASE_SCAN_RADIUS;
        exploreRadius = EXPLORE_BASE_RADIUS;
        exploreTarget = null;
        searchIdleTicks = 0;
        blockedTargets.clear();
        obstructionQueue.clear();
        obstructionSet.clear();
        lastMinedReference = steve.blockPosition().immutable();
        actualTarget = null;
        lastSearchX = steve.getX();
        lastSearchY = steve.getY();
        lastSearchZ = steve.getZ();
        
        targetBlock = parseBlock(blockName);
        genericWoodRequest = isGenericWoodRequest(blockName);

        if (targetBlock == null || targetBlock == Blocks.AIR) {
            steve.setDebugTargetBlock(null);
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }

        targetBlockId = BuiltInRegistries.BLOCK.getKey(targetBlock).toString();
        if (isCoalTask()) {
            scanRadius = COAL_BASE_SCAN_RADIUS;
            exploreRadius = COAL_BASE_EXPLORE_RADIUS;
        }
        acceptableBlockIds.clear();
        acceptableBlockIds.add(targetBlockId);
        acceptableBlockIds.addAll(BlockTransitionMap.getAcceptableSourcesRecursive(targetBlockId));
        addAlternativeAcceptableBlocks();
        addOreVariantAcceptableBlocks();
        if (isWoodTask()) {
            acceptableBlockIds.addAll(WOOD_PRIMARY_BLOCK_IDS);
            SteveMod.LOGGER.info(
                "Steve '{}' treating wood-like target '{}' as multi-wood target set ({} primary ids)",
                steve.getSteveName(),
                blockName,
                WOOD_PRIMARY_BLOCK_IDS.size()
            );
        }
        progressBlockIds.clear();
        progressBlockIds.addAll(acceptableBlockIds);
        if (isWoodTask()) {
            progressBlockIds.addAll(WOOD_PRIMARY_BLOCK_IDS);
        }
        if (isWoodTask()) {
            acceptableBlockIds.removeIf(id -> !isWoodLikeBlockId(id));
            progressBlockIds.removeIf(id -> !isWoodLikeBlockId(id));
        }

        steve.setInvulnerableBuilding(true); // Prevent suffocation damage while mining

        toolRequirement = ToolCapabilityMap.getRequirement(targetBlockId);
        if (!ensureToolEquipped()) {
            steve.setDebugTargetBlock(null);
            if (toolRequirement.required() == ToolType.PICKAXE
                && toolRequirement.minTier() != null
                && toolRequirement.minTier() != ToolCapabilityMap.ToolTier.NONE) {
                result = ActionResult.failure("Missing tool: " + toolRequirement.minTier().label() + "_pickaxe");
            } else {
                result = ActionResult.failure("Missing tool: " + toolRequirement.required().label());
            }
            return;
        }
        
        SteveMod.LOGGER.info("Steve '{}' mining {} - using perception snapshot", 
            steve.getSteveName(), targetBlock.getName().getString());
        steve.forceVisibleScan(scanRadius);
        currentTarget = findNearestVisibleTarget();
        syncDebugTarget();
    }

    @Override
    protected void onTick() {
        syncDebugTarget();
        ticksRunning++;
        ticksSinceLastTorch++;
        ticksSinceLastMine++;
        ticksSinceRescan++;
        if (waterReplanCooldown > 0) {
            waterReplanCooldown--;
        }
        trackMovementStuckState();
        trackWaterTransitions();
        if (handleWaterStuckRecovery()) {
            return;
        }
        if (!blockedTargets.isEmpty()) {
            // blockedTargets stores "valid until tick"; drop entries that expired.
            blockedTargets.entrySet().removeIf(entry -> entry.getValue() <= ticksRunning);
        }
        
        if (ticksRunning > MAX_TICKS) {
            steve.setInvulnerableBuilding(false);
            steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            steve.setDebugTargetBlock(null);
            result = ActionResult.failure("Mining timeout - only found " + minedCount + " blocks");
            return;
        }
        
        if (ticksSinceLastTorch >= TORCH_INTERVAL) {
            placeTorchIfDark();
            ticksSinceLastTorch = 0;
        }
        
        if (ticksSinceLastMine < MINING_DELAY) {
            return; // Still waiting
        }
        
        if (currentTarget == null) {
            updateSearchIdleCounter();
            if (ticksSinceRescan >= RESCAN_INTERVAL) {
                steve.forceVisibleScan(scanRadius);
                logSearchDiagnostics();
                ticksSinceRescan = 0;
            }
            BlockPos nextTarget = findNextTarget();
            if (nextTarget != null) {
                assignActualTarget(nextTarget);
                noTargetTicks = 0;
            }

            if (currentTarget == null) {
                // For ore/stone style goals, avoid random digging and expand a deterministic
                // local frontier around the last mined block so tunnels remain coherent.
                if (shouldAllowSubsurfaceFallback() && queueStructuredFrontierDig()) {
                    currentTarget = resolveCurrentTarget();
                    noTargetTicks = 0;
                    syncDebugTarget();
                    return;
                }
                noTargetTicks++;
                if (isCoalTask() && !coalDeepSearchMode && noTargetTicks >= COAL_SWITCH_TO_DEEP_SEARCH_TICKS) {
                    coalDeepSearchMode = true;
                    scanRadius = Math.max(scanRadius, MAX_SCAN_RADIUS);
                    exploreRadius = Math.max(exploreRadius, COAL_MAX_EXPLORE_RADIUS);
                    steve.forceVisibleScan(scanRadius);
                    SteveMod.LOGGER.info(
                        "[COAL_SEARCH] Steve '{}' switched to deep-search mode after {} no-target ticks (scanRadius={} exploreRadius={})",
                        steve.getSteveName(),
                        noTargetTicks,
                        scanRadius,
                        exploreRadius
                    );
                }
                if (noTargetTicks >= NO_TARGET_ABORT_TICKS) {
                    steve.setInvulnerableBuilding(false);
                    steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    steve.setDebugTargetBlock(null);
                    result = ActionResult.failure(
                        "No valid mining target for " + targetBlock.getName().getString()
                            + " after prolonged search (" + NO_TARGET_ABORT_TICKS + " ticks)"
                    );
                    return;
                }
                searchTicks++;
                if (searchTicks >= SEARCH_EXPAND_INTERVAL) {
                    scanRadius = Math.max(scanRadius, isCoalTask() ? MAX_SCAN_RADIUS : MAX_SCAN_RADIUS);
                    steve.forceVisibleScan(scanRadius);
                    if (searchTicks % SEARCH_EXPAND_INTERVAL == 0) {
                        int prev = exploreRadius;
                        int maxExploreRadius = isCoalTask() ? COAL_MAX_EXPLORE_RADIUS : EXPLORE_MAX_RADIUS;
                        exploreRadius = Math.min(maxExploreRadius, exploreRadius + EXPLORE_RADIUS_STEP);
                        if (exploreRadius != prev) {
                            SteveMod.LOGGER.info(
                                "Steve '{}' expanding explore radius for {} from {} to {}",
                                steve.getSteveName(),
                                targetBlock.getName().getString(),
                                prev,
                                exploreRadius
                            );
                        }
                    }
                }
                if (minedCount >= targetQuantity) {
                    steve.setInvulnerableBuilding(false);
                    steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    steve.setDebugTargetBlock(null);
                    result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                    return;
                }
                ticksSinceExplore++;
                if (ticksSinceExplore >= EXPLORE_RETRY_INTERVAL) {
                    ticksSinceExplore = 0;
                    exploreTarget = pickMemoryDrivenExploreTarget();
                    if (exploreTarget == null && isCoalTask()) {
                        exploreTarget = pickCoalExploreTarget();
                    }
                    if (exploreTarget == null) {
                        exploreTarget = pickDirectedExploreTarget();
                    }
                    if (exploreTarget == null) {
                        exploreTarget = pickExploreTarget();
                    }
                    if (exploreTarget != null) {
                        boolean moved = steve.getNavigation().moveTo(
                            exploreTarget.getX() + 0.5,
                            exploreTarget.getY(),
                            exploreTarget.getZ() + 0.5,
                            1.0
                        );
                        if (moved) {
                            SteveMod.LOGGER.info("Steve '{}' exploring for {} at {}",
                                steve.getSteveName(), targetBlock.getName().getString(), exploreTarget);
                        } else {
                            if (isViableBlockCenter(exploreTarget)) {
                                markBlockedCluster(exploreTarget, "explore path failed");
                            }
                        }
                    }
                }
                if (searchIdleTicks >= SEARCH_IDLE_FORCE_MOVE_TICKS) {
                    BlockPos forced = pickMemoryDrivenExploreTarget();
                    if (forced == null && isCoalTask()) {
                        forced = pickCoalExploreTarget();
                    }
                    if (forced == null) {
                        forced = pickDirectedExploreTarget();
                    }
                    if (forced == null) {
                        forced = pickExploreTarget();
                    }
                    if (forced != null) {
                        exploreTarget = forced;
                        boolean moved = steve.getNavigation().moveTo(
                            forced.getX() + 0.5,
                            forced.getY(),
                            forced.getZ() + 0.5,
                            1.1
                        );
                        if (moved) {
                            searchIdleTicks = 0;
                            SteveMod.LOGGER.info(
                                "Steve '{}' force-moving during search stall for {} -> {}",
                                steve.getSteveName(),
                                targetBlock.getName().getString(),
                                forced
                            );
                        } else {
                            if (isViableBlockCenter(forced)) {
                                markBlockedCluster(forced, "forced move path failed");
                            }
                        }
                    }
                }
                return;
            }
            searchTicks = 0;
            noTargetTicks = 0;
            scanRadius = isCoalTask() ? COAL_BASE_SCAN_RADIUS : BASE_SCAN_RADIUS;
            steve.forceVisibleScan(scanRadius);
        }

        BlockState targetState = steve.level().getBlockState(currentTarget);
        String targetId = BuiltInRegistries.BLOCK.getKey(targetState.getBlock()).toString();
        boolean obstructionTarget = isCurrentObstructionTarget();
        if (acceptableBlockIds.contains(targetId) || obstructionTarget) {
            double distance = steve.distanceToSqr(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
            );
            if (distance >= lastTargetDistance - 0.01) {
                targetStallTicks++;
            } else {
                targetStallTicks = 0;
                lastTargetDistance = distance;
            }
            if (targetStallTicks >= TARGET_STALL_TICKS) {
                BlockPos anchor = findApproachAnchor(currentTarget);
                if (anchor != null && targetRecoveryAttempts < MAX_TARGET_RECOVERY_ATTEMPTS) {
                    targetRecoveryAttempts++;
                    targetStallTicks = 0;
                    lastTargetDistance = Double.MAX_VALUE;
                    steve.getNavigation().moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, 1.1);
                    SteveMod.LOGGER.info(
                        "Steve '{}' stuck approaching {}; recovery {}/{} via anchor {}",
                        steve.getSteveName(),
                        currentTarget,
                        targetRecoveryAttempts,
                        MAX_TARGET_RECOVERY_ATTEMPTS,
                        anchor
                    );
                    return;
                }
                if (shouldAllowSubsurfaceFallback()) {
                    buildObstructionQueue(currentTarget);
                    if (obstructionQueue.isEmpty()) {
                        queueDigStepTowardTarget(currentTarget);
                    }
                    if (!obstructionQueue.isEmpty()) {
                        SteveMod.LOGGER.info(
                            "Steve '{}' switching to dig-through fallback for stalled target {} (queueSize={})",
                            steve.getSteveName(),
                            currentTarget,
                            obstructionQueue.size()
                        );
                        targetStallTicks = 0;
                        lastTargetDistance = Double.MAX_VALUE;
                        targetRecoveryAttempts = 0;
                        currentTarget = resolveCurrentTarget();
                        syncDebugTarget();
                        return;
                    }
                }
                SteveMod.LOGGER.info("Steve '{}' stuck approaching target {}; forcing rescan", steve.getSteveName(), currentTarget);
                markBlockedCluster(currentTarget, "stuck approaching target");
                currentTarget = null;
                targetStallTicks = 0;
                lastTargetDistance = Double.MAX_VALUE;
                miningProgressTicks = 0;
                lastMiningTarget = null;
                targetRecoveryAttempts = 0;
                steve.getNavigation().stop();
                steve.forceVisibleScan(scanRadius);
                return;
            }
            if (distance > MINING_INTERACTION_RANGE_SQR) {
                boolean moved = steve.getNavigation().moveTo(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY(),
                    currentTarget.getZ() + 0.5,
                    1.2
                );
                if (!moved) {
                    BlockPos anchor = findApproachAnchor(currentTarget);
                    if (anchor != null) {
                        moved = steve.getNavigation().moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, 1.1);
                    }
                }
                if (!moved) {
                    if (isWoodTask() || shouldAllowSubsurfaceFallback()) {
                        buildObstructionQueue(currentTarget);
                        if (obstructionQueue.isEmpty() && shouldAllowSubsurfaceFallback()) {
                            queueDigStepTowardTarget(currentTarget);
                        }
                        if (!obstructionQueue.isEmpty()) {
                            currentTarget = resolveCurrentTarget();
                            syncDebugTarget();
                            return;
                        }
                    }
                    markBlockedCluster(currentTarget, "navigation move failed");
                    currentTarget = null;
                    targetRecoveryAttempts = 0;
                    steve.forceVisibleScan(scanRadius);
                    syncDebugTarget();
                }
                return;
            }

            steve.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
            );
            
            steve.swing(InteractionHand.MAIN_HAND, true);
            if (lastMiningTarget == null || !lastMiningTarget.equals(currentTarget)) {
                lastMiningTarget = currentTarget;
                miningProgressTicks = 0;
            }
            miningProgressTicks++;
            if (miningProgressTicks < MINING_DURATION) {
                return;
            }
            miningProgressTicks = 0;
            
            BlockPos minedPos = currentTarget;
            BlockState minedState = steve.level().getBlockState(minedPos);
            String minedId = BuiltInRegistries.BLOCK.getKey(minedState.getBlock()).toString();
            boolean countsTowardTarget = progressBlockIds.contains(minedId);
            String minedName = minedState.getBlock().getName().getString();

            mineAndCollect(minedPos);
            if (countsTowardTarget) {
                minedCount++;
            }
            ticksSinceLastMine = 0; // Reset delay timer
            steve.forceVisibleScan(scanRadius);
            ticksSinceRescan = 0;
            targetStallTicks = 0;
            lastTargetDistance = Double.MAX_VALUE;
            miningProgressTicks = 0;
            lastMiningTarget = null;
            handleBlockMined(minedPos);
            
            SteveMod.LOGGER.info(
                "Steve '{}' mined {} ({}) at {} countsTowardTarget={} - Total: {}/{}",
                steve.getSteveName(),
                minedName,
                minedId,
                minedPos,
                countsTowardTarget,
                minedCount,
                targetQuantity
            );
            
            if (minedCount >= targetQuantity) {
                steve.setInvulnerableBuilding(false);
                steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                steve.setDebugTargetBlock(null);
                return;
            }
            
            currentTarget = resolveCurrentTarget();
            syncDebugTarget();
        } else {
            if (currentTarget != null && isViableBlockCenter(currentTarget)) {
                markBlocked(currentTarget, "target no longer acceptable (" + targetId + ")");
            }
            currentTarget = null;
            targetRecoveryAttempts = 0;
            syncDebugTarget();
        }
    }

    @Override
    protected void onCancel() {
        steve.setInvulnerableBuilding(false);
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        steve.setDebugTargetBlock(null);
        obstructionQueue.clear();
        obstructionSet.clear();
        actualTarget = null;
    }

    @Override
    public String getDescription() {
        String base = "Mine " + targetQuantity + " " + targetBlock.getName().getString() + " (" + minedCount + " found)";
        if (currentTarget != null) {
            return base + " target=" + currentTarget.getX() + "," + currentTarget.getY() + "," + currentTarget.getZ();
        }
        return base + " searching";
    }

    /**
     * Check light level and place torch if too dark
     */
    private void placeTorchIfDark() {
        BlockPos stevePos = steve.blockPosition();
        int lightLevel = steve.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, stevePos);
        
        if (lightLevel < MIN_LIGHT_LEVEL) {
            BlockPos torchPos = findTorchPosition(stevePos);
            
            if (torchPos != null && steve.level().getBlockState(torchPos).isAir()) {
                steve.level().setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
                SteveMod.LOGGER.info("Steve '{}' placed torch at {} (light level was {})", 
                    steve.getSteveName(), torchPos, lightLevel);
                
                steve.swing(InteractionHand.MAIN_HAND, true);
            }
        }
    }
    
    /**
     * Find a good position to place a torch (on floor or wall)
     */
    private BlockPos findTorchPosition(BlockPos center) {
        BlockPos floorPos = center.below();
        if (steve.level().getBlockState(floorPos).isSolid() && 
            steve.level().getBlockState(center).isAir()) {
            return center;
        }
        
        BlockPos[] wallPositions = {
            center.north(), center.south(), center.east(), center.west()
        };
        
        for (BlockPos wallPos : wallPositions) {
            if (steve.level().getBlockState(wallPos).isSolid() && 
                steve.level().getBlockState(center).isAir()) {
                return center;
            }
        }
        
        return null;
    }

    private BlockPos findNearbyTargetInRadius() {
        BlockPos origin = steve.blockPosition();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        int radius = scanRadius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!acceptableBlockIds.contains(blockId)) {
                        continue;
                    }
                    if (isUnsafeVerticalTarget(pos)) {
                        continue;
                    }
                    if (isNearBlockedTarget(pos, BLOCKED_CLUSTER_RADIUS)) {
                        continue;
                    }
                    if (!isReachableTarget(pos)) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestPos = pos;
                    }
                }
            }
        }

        return bestPos;
    }

    private BlockPos findNearestVisibleTarget() {
        List<VisibleBlockEntry> entries = steve.getMemory().getVisibleBlocks();
        if (entries.isEmpty()) {
            return null;
        }
        boolean woodTask = isWoodLikeBlockId(targetBlockId);
        VisibleBlockEntry best = null;
        double bestWoodScore = Double.MAX_VALUE;
        for (VisibleBlockEntry entry : entries) {
            if (!acceptableBlockIds.contains(entry.blockId())) {
                continue;
            }
            if (!isWoodTask() && !isOreTask() && isNearBlockedTarget(entry.position(), BLOCKED_CLUSTER_RADIUS)) {
                continue;
            }
            if (!isReachableTarget(entry.position())) {
                continue;
            }
            if (!woodTask) {
                if (best == null || entry.distance() < best.distance()) {
                    best = entry;
                }
                continue;
            }

            double score = scoreWoodCandidate(entry);
            if (best == null || score < bestWoodScore) {
                best = entry;
                bestWoodScore = score;
            }
        }
        return best != null ? best.position() : null;
    }

    private double scoreWoodCandidate(VisibleBlockEntry entry) {
        BlockPos pos = entry.position();
        double score = entry.distance();
        // Prefer blocks with open faces and easier vertical movement.
        if (!hasExposedFace(pos)) {
            score += 200.0;
        }
        score += Math.abs(pos.getY() - steve.blockPosition().getY()) * 4.0;
        return score;
    }

    private BlockPos pickExploreTarget() {
        BlockPos origin = steve.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = steve.getRandom().nextInt(exploreRadius * 2 + 1) - exploreRadius;
            int dz = steve.getRandom().nextInt(exploreRadius * 2 + 1) - exploreRadius;
            BlockPos base = origin.offset(dx, 0, dz);
            BlockPos ground = findGroundAbove(base);
            if (isViableExploreDestination(ground)) {
                return ground;
            }
        }
        return null;
    }

    private BlockPos pickCoalExploreTarget() {
        BlockPos origin = steve.blockPosition();
        int verticalBiasDown = coalDeepSearchMode ? 8 : 4;
        for (int attempt = 0; attempt < 18; attempt++) {
            int dx = steve.getRandom().nextInt(exploreRadius * 2 + 1) - exploreRadius;
            int dz = steve.getRandom().nextInt(exploreRadius * 2 + 1) - exploreRadius;
            int dy = -steve.getRandom().nextInt(verticalBiasDown + 1);
            BlockPos probe = origin.offset(dx, dy, dz);
            BlockPos ground = findGroundAbove(probe);
            if (ground == null) {
                continue;
            }
            if (ground.getY() > origin.getY() + 1) {
                continue;
            }
            if (isViableExploreDestination(ground)) {
                return ground.immutable();
            }
        }
        return null;
    }

    private BlockPos pickDirectedExploreTarget() {
        BlockPos nearest = findNearestAcceptableIgnoringReachability(DIRECTED_EXPLORE_SCAN_RADIUS);
        if (nearest == null) {
            return null;
        }

        // Step near the candidate so a fresh scan can pick it as active target.
        BlockPos above = nearest.above();
        BlockState aboveState = steve.level().getBlockState(above);
        if (isOpenAdjacentBlock(aboveState) && isViableExploreDestination(above)) {
            return above.immutable();
        }
        BlockPos ground = findGroundAbove(nearest);
        if (isViableExploreDestination(ground)) {
            return ground.immutable();
        }
        return isViableExploreDestination(nearest) ? nearest.immutable() : null;
    }

    private BlockPos pickMemoryDrivenExploreTarget() {
        List<String> leastSeen = steve.getMemory().getLeastSeenDirections(6);
        if (leastSeen.isEmpty()) {
            return null;
        }
        BlockPos origin = steve.blockPosition();
        int distance = Math.max(exploreRadius, 16);
        for (String label : leastSeen) {
            String yaw = label;
            int dash = label.indexOf('-');
            if (dash > 0) {
                yaw = label.substring(0, dash);
            }
            int[] dir = yawToVector(yaw);
            if (dir == null) {
                continue;
            }
            BlockPos probe = origin.offset(dir[0] * distance, 0, dir[1] * distance);
            BlockPos ground = findGroundAbove(probe);
            if (isViableExploreDestination(ground)) {
                return ground.immutable();
            }
        }
        return null;
    }

    private int[] yawToVector(String yaw) {
        return switch (yaw) {
            case "N" -> new int[]{0, -1};
            case "NE" -> new int[]{1, -1};
            case "E" -> new int[]{1, 0};
            case "SE" -> new int[]{1, 1};
            case "S" -> new int[]{0, 1};
            case "SW" -> new int[]{-1, 1};
            case "W" -> new int[]{-1, 0};
            case "NW" -> new int[]{-1, -1};
            default -> null;
        };
    }

    private void updateSearchIdleCounter() {
        double dx = steve.getX() - lastSearchX;
        double dy = steve.getY() - lastSearchY;
        double dz = steve.getZ() - lastSearchZ;
        double movedSqr = dx * dx + dy * dy + dz * dz;
        if (movedSqr <= SEARCH_IDLE_MOVE_EPSILON_SQR) {
            searchIdleTicks++;
        } else {
            searchIdleTicks = 0;
        }
        lastSearchX = steve.getX();
        lastSearchY = steve.getY();
        lastSearchZ = steve.getZ();
    }

    private boolean isReachableTarget(BlockPos pos) {
        if (pos == null || isBlockedTarget(pos)) {
            return false;
        }
        if (shouldAllowSubsurfaceFallback()) {
            // Stone/deepslate tasks should tunnel toward targets even without exposed faces.
            return true;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(steve.level().getBlockState(pos).getBlock()).toString();
        boolean woodLike = isWoodLikeBlockId(blockId);
        if (woodLike) {
            if (steve.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 16.0) {
                return true;
            }
            if (canNavigateToAdjacentAirStrict(pos)) {
                return true;
            }
            BlockPos anchor = findApproachAnchor(pos);
            if (anchor != null) {
                return true;
            }
            markBlocked(pos, "wood target unreachable (no path/anchor)");
            return false;
        }
        if (!woodLike && !hasExposedFace(pos)) {
            markBlocked(pos, "no exposed face");
            return false;
        }
        if (!woodLike && !canNavigateToAdjacentAir(pos)) {
            markBlocked(pos, "no path to adjacent air");
            return false;
        }
        return true;
    }

    private boolean hasExposedFace(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = steve.level().getBlockState(pos.relative(direction));
            if (isOpenAdjacentBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private boolean canNavigateToAdjacentAir(BlockPos pos) {
        // If already in interaction range, treat as reachable.
        if (steve.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 16.0) {
            return true;
        }
        boolean hasExposedNeighbor = false;
        for (Direction direction : Direction.values()) {
            BlockPos adj = pos.relative(direction);
            BlockState neighbor = steve.level().getBlockState(adj);
            if (!isOpenAdjacentBlock(neighbor)) {
                continue;
            }
            hasExposedNeighbor = true;
            if (steve.getNavigation().createPath(adj, 0) != null) {
                return true;
            }
        }
        // Transparent adjacency is enough to consider the target mineable;
        // pathing can still recover via stall handling.
        return hasExposedNeighbor;
    }

    private boolean canNavigateToAdjacentAirStrict(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adj = pos.relative(direction);
            BlockState neighbor = steve.level().getBlockState(adj);
            if (!isOpenAdjacentBlock(neighbor)) {
                continue;
            }
            if (steve.getNavigation().createPath(adj, 0) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isOpenAdjacentBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.isAir() || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR)) {
            return true;
        }
        // See-through (non-occluding) blocks count as exposed adjacency
        // (e.g., leaves, grass, many transparent blocks).
        return !state.canOcclude();
    }

    private boolean isWoodLikeBlockId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        return blockId.endsWith("_log")
            || blockId.endsWith("_wood")
            || blockId.endsWith("_stem")
            || blockId.endsWith("_hyphae")
            || blockId.endsWith("bamboo_block");
    }

    private boolean isOreTask() {
        return targetBlockId != null && targetBlockId.endsWith("_ore");
    }

    private boolean isCoalTask() {
        if (targetBlockId == null || targetBlockId.isBlank()) {
            return false;
        }
        return targetBlockId.equals("minecraft:coal_ore")
            || targetBlockId.equals("minecraft:deepslate_coal_ore");
    }

    private void addOreVariantAcceptableBlocks() {
        if (targetBlockId == null || targetBlockId.isBlank() || !targetBlockId.endsWith("_ore")) {
            return;
        }
        String path = targetBlockId.startsWith("minecraft:") ? targetBlockId.substring("minecraft:".length()) : targetBlockId;
        if (path.startsWith("deepslate_")) {
            String base = "minecraft:" + path.substring("deepslate_".length());
            acceptableBlockIds.add(base);
            progressBlockIds.add(base);
            return;
        }
        String deep = "minecraft:deepslate_" + path;
        acceptableBlockIds.add(deep);
        progressBlockIds.add(deep);
    }

    private boolean isUnsafeVerticalTarget(BlockPos pos) {
        if (!shouldAllowSubsurfaceFallback() || pos == null) {
            return false;
        }
        BlockPos origin = steve.blockPosition();
        if (pos.getY() >= origin.getY()) {
            return false;
        }
        // Avoid digging straight down under our feet.
        return pos.getX() == origin.getX() && pos.getZ() == origin.getZ();
    }

    private boolean isViableExploreDestination(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return isViableBlockCenter(pos) && steve.getNavigation().createPath(pos, 0) != null;
    }

    private boolean isViableBlockCenter(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return steve.blockPosition().distSqr(pos) >= MIN_EXPLORE_MOVE_DISTANCE_SQR;
    }

    private void markBlocked(BlockPos pos, String reason) {
        if (isBlockedTarget(pos)) {
            return;
        }
        // Keep problematic ore targets blocked longer to prevent immediate reselection loops.
        int ttl = blockedTtlFor(reason);
        blockedTargets.put(pos.immutable(), ticksRunning + ttl);
        SteveMod.LOGGER.info(
            "Steve '{}' rejected target {} ({}); blocking for {} ticks",
            steve.getSteveName(),
            pos,
            reason,
            ttl
        );
    }

    private void markBlockedCluster(BlockPos center, String reason) {
        if (center == null) {
            return;
        }
        for (int dx = -BLOCKED_CLUSTER_RADIUS; dx <= BLOCKED_CLUSTER_RADIUS; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -BLOCKED_CLUSTER_RADIUS; dz <= BLOCKED_CLUSTER_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (isBlockedTarget(pos)) {
                        continue;
                    }
                    blockedTargets.put(pos.immutable(), ticksRunning + blockedTtlFor(reason));
                }
            }
        }
        SteveMod.LOGGER.info(
            "Steve '{}' blocked target cluster around {} ({}), ttl={} ticks",
            steve.getSteveName(),
            center,
            reason,
            BLOCKED_TARGET_TTL
        );
    }

    private boolean isNearBlockedTarget(BlockPos pos, int radius) {
        if (pos == null) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isBlockedTarget(pos.offset(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isBlockedTarget(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Integer untilTick = blockedTargets.get(pos);
        return untilTick != null && untilTick > ticksRunning;
    }

    private int blockedTtlFor(String reason) {
        if (isOreTask()) {
            return BLOCKED_TARGET_TTL_ORE;
        }
        if (reason != null && reason.toLowerCase().contains("stuck")) {
            return Math.max(BLOCKED_TARGET_TTL, 20 * 20);
        }
        return BLOCKED_TARGET_TTL;
    }

    private void logSearchDiagnostics() {
        int visibleCount = steve.getMemory().getVisibleBlocks().size();
        int visibleMatches = 0;
        for (VisibleBlockEntry entry : steve.getMemory().getVisibleBlocks()) {
            if (acceptableBlockIds.contains(entry.blockId())) {
                visibleMatches++;
            }
        }
        BlockPos nearestAny = findNearestAcceptableIgnoringReachability();
        int cubeMatches = countAcceptableInRadius();
        SteveMod.LOGGER.info(
            "Steve '{}' search diag: scanRadius={} visible={} visibleMatches={} cubeMatches={} nearestAny={}",
            steve.getSteveName(),
            scanRadius,
            visibleCount,
            visibleMatches,
            cubeMatches,
            nearestAny
        );
    }

    private int countAcceptableInRadius() {
        int count = 0;
        BlockPos origin = steve.blockPosition();
        int radius = scanRadius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (acceptableBlockIds.contains(blockId)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private BlockPos findNearestAcceptableIgnoringReachability() {
        return findNearestAcceptableIgnoringReachability(scanRadius);
    }

    private BlockPos findNearestAcceptableIgnoringReachability(int radius) {
        BlockPos origin = steve.blockPosition();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!acceptableBlockIds.contains(blockId)) {
                        continue;
                    }
                    if (isUnsafeVerticalTarget(pos)) {
                        continue;
                    }
                    if (!isWoodTask() && !isOreTask() && isNearBlockedTarget(pos, BLOCKED_CLUSTER_RADIUS)) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestPos = pos;
                    }
                }
            }
        }
        return bestPos;
    }

    private BlockPos findGroundAbove(BlockPos pos) {
        int startY = pos.getY() + 3;
        for (int y = startY; y >= pos.getY() - 6 && y > -64; y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState state = steve.level().getBlockState(check);
            if (state.isSolid()) {
                return check.above();
            }
        }
        // Fallback: use a same-height probe so navigation still attempts movement
        // when terrain sampling misses a solid block in this small vertical window.
        BlockPos fallback = new BlockPos(pos.getX(), steve.blockPosition().getY(), pos.getZ());
        return steve.getNavigation().createPath(fallback, 0) != null ? fallback : null;
    }

    private BlockPos findNextTarget() {
        BlockPos candidate = findNearestVisibleTarget();
        if (candidate != null) {
            logSelectionTier("working", candidate, "nearest visible candidate");
        }
        if (candidate == null) {
            candidate = findNearbyTargetInRadius();
            if (candidate != null) {
                logSelectionTier("working", candidate, "local radius scan candidate");
            }
        }
        if (candidate == null) {
            SteveMemory.EpisodicTarget episodic = steve.getMemory().findBestEpisodicTarget(
                acceptableBlockIds,
                steve.blockPosition(),
                steve.level().getGameTime(),
                Math.max(96, exploreRadius * 2)
            );
            if (episodic != null) {
                BlockPos episodicPos = episodic.position();
                if (isPresentAcceptableBlock(episodicPos) && isReachableTarget(episodicPos)) {
                    candidate = episodicPos;
                    SteveMod.LOGGER.info(
                        "Steve '{}' [memory-tier=episodic] picked target {} for {} (score={} age={}t seen={})",
                        steve.getSteveName(),
                        episodicPos,
                        episodic.blockId(),
                        episodic.score(),
                        episodic.ageTicks(),
                        episodic.seenCount()
                    );
                }
            }
        }
        if (candidate == null && isWoodTask()) {
            candidate = findNearestAcceptableIgnoringReachability();
            if (candidate != null) {
                SteveMod.LOGGER.info(
                    "Steve '{}' [memory-tier=fallback] using wood fallback target {} for {}",
                    steve.getSteveName(),
                    candidate,
                    targetBlock.getName().getString()
                );
            }
        }
        if (candidate == null && shouldAllowSubsurfaceFallback()) {
            candidate = findNearestAcceptableIgnoringReachability();
            if (candidate != null) {
                SteveMod.LOGGER.info(
                    "Steve '{}' [memory-tier=fallback] using subsurface fallback target {} for {}",
                    steve.getSteveName(),
                    candidate,
                    targetBlock.getName().getString()
                );
            }
        }
        if (candidate == null) {
            SteveMod.LOGGER.info(
                "Steve '{}' [memory-tier=none] no reachable target for {} (scanRadius={}, exploreRadius={})",
                steve.getSteveName(),
                targetBlock.getName().getString(),
                scanRadius,
                exploreRadius
            );
        }
        return candidate;
    }

    private boolean isPresentAcceptableBlock(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockState state = steve.level().getBlockState(pos);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return acceptableBlockIds.contains(blockId);
    }

    private void logSelectionTier(String tier, BlockPos candidate, String reason) {
        if (candidate == null) {
            return;
        }
        SteveMod.LOGGER.info(
            "Steve '{}' [memory-tier={}] selected target {} for {} ({})",
            steve.getSteveName(),
            tier,
            candidate,
            targetBlock.getName().getString(),
            reason
        );
    }

    private boolean shouldAllowSubsurfaceFallback() {
        if (targetBlockId == null || targetBlockId.isBlank()) {
            return false;
        }
        if (targetBlockId.endsWith("_ore")) {
            return true;
        }
        return targetBlockId.equals("minecraft:stone")
            || targetBlockId.equals("minecraft:cobblestone")
            || targetBlockId.equals("minecraft:deepslate")
            || targetBlockId.equals("minecraft:cobbled_deepslate")
            || targetBlockId.equals("minecraft:iron_ore")
            || targetBlockId.equals("minecraft:deepslate_iron_ore");
    }

    private void assignActualTarget(BlockPos target) {
        actualTarget = target;
        obstructionQueue.clear();
        obstructionSet.clear();
        currentTarget = actualTarget;
        targetRecoveryAttempts = 0;
        syncDebugTarget();
    }

    private BlockPos resolveCurrentTarget() {
        if (!obstructionQueue.isEmpty()) {
            return obstructionQueue.peekFirst();
        }
        return actualTarget;
    }

    private boolean isCurrentObstructionTarget() {
        return currentTarget != null
            && !obstructionQueue.isEmpty()
            && currentTarget.equals(obstructionQueue.peekFirst());
    }

    private void buildObstructionQueue(BlockPos target) {
        obstructionQueue.clear();
        obstructionSet.clear();
        if (target == null || !(steve.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Vec3 start = steve.getEyePosition(1.0F);
        Vec3 end = Vec3.atCenterOf(target);
        Vec3 direction = end.subtract(start);
        double distance = direction.length();
        if (distance <= 0.01) {
            return;
        }

        Vec3 step = direction.normalize().scale(0.25);
        Vec3 sample = start.add(step);
        Set<BlockPos> seen = new HashSet<>();
        int maxSteps = Math.max(1, (int) Math.ceil(distance / 0.25));
        for (int i = 0; i < maxSteps; i++) {
            BlockPos samplePos = BlockPos.containing(sample);
            if (samplePos.equals(target) || samplePos.equals(steve.blockPosition())) {
                sample = sample.add(step);
                continue;
            }
            if (seen.contains(samplePos) || isBlockedTarget(samplePos)) {
                sample = sample.add(step);
                continue;
            }
            seen.add(samplePos);
            BlockState sampleState = serverLevel.getBlockState(samplePos);
            if (sampleState.isAir() || sampleState.is(Blocks.CAVE_AIR) || sampleState.is(Blocks.VOID_AIR)) {
                sample = sample.add(step);
                continue;
            }
            float destroySpeed = sampleState.getDestroySpeed(serverLevel, samplePos);
            if (destroySpeed < 0.0F) {
                sample = sample.add(step);
                continue;
            }
            obstructionSet.add(samplePos);
            obstructionQueue.addLast(samplePos.immutable());
            sample = sample.add(step);
        }
    }

    private void queueDigStepTowardTarget(BlockPos target) {
        if (target == null || !(steve.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos origin = steve.blockPosition();
        int stepX = Integer.compare(target.getX(), origin.getX());
        int stepY = Integer.compare(target.getY(), origin.getY());
        int stepZ = Integer.compare(target.getZ(), origin.getZ());

        BlockPos[] candidates = new BlockPos[] {
            origin.offset(stepX, 0, stepZ),
            origin.offset(stepX, stepY, stepZ),
            origin.offset(stepX, -1, stepZ),
            origin.offset(stepX, 1, stepZ),
            origin.offset(stepX, 0, 0),
            origin.offset(0, 0, stepZ),
            origin.offset(0, stepY, 0)
        };

        for (BlockPos candidate : candidates) {
            if (candidate == null || candidate.equals(origin) || isBlockedTarget(candidate)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(candidate);
            if (state.isAir() || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR)) {
                continue;
            }
            float destroySpeed = state.getDestroySpeed(serverLevel, candidate);
            if (destroySpeed < 0.0F) {
                continue;
            }
            obstructionSet.add(candidate.immutable());
            obstructionQueue.addLast(candidate.immutable());
            SteveMod.LOGGER.info(
                "Steve '{}' queued dig-forward fallback block {} toward target {}",
                steve.getSteveName(),
                candidate,
                target
            );
            return;
        }
    }

    private boolean queueStructuredFrontierDig() {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!obstructionQueue.isEmpty()) {
            return true;
        }

        BlockPos center = lastMinedReference != null ? lastMinedReference : steve.blockPosition();
        Direction facing = steve.getDirection();
        int forwardX = facing.getStepX();
        int forwardZ = facing.getStepZ();
        int rightX = -forwardZ;
        int rightZ = forwardX;

        // Deterministic 3x3 frontier around the last mined center.
        int[][] offsets = new int[][]{
            {0, 1},   // next
            {1, 1},   // front-right corner
            {-1, 1},  // front-left corner
            {1, 0},   // right
            {-1, 0},  // left
            {1, -1},  // back-right corner
            {-1, -1}, // back-left corner
            {0, -1}   // back
        };

        for (int[] offset : offsets) {
            BlockPos candidate = center.offset(
                rightX * offset[0] + forwardX * offset[1],
                0,
                rightZ * offset[0] + forwardZ * offset[1]
            );
            if (enqueueStructuredDigCandidate(serverLevel, candidate)) {
                SteveMod.LOGGER.info(
                    "Steve '{}' queued structured 3x3 frontier dig from {} -> {}",
                    steve.getSteveName(),
                    center,
                    candidate
                );
                return true;
            }
        }

        // If 3x3 ring is exhausted, continue one level lower from center.
        BlockPos below = center.below();
        if (enqueueStructuredDigCandidate(serverLevel, below)) {
            SteveMod.LOGGER.info(
                "Steve '{}' queued structured depth step from {} -> {}",
                steve.getSteveName(),
                center,
                below
            );
            return true;
        }
        return false;
    }

    private boolean enqueueStructuredDigCandidate(ServerLevel serverLevel, BlockPos candidate) {
        if (candidate == null || isBlockedTarget(candidate) || obstructionSet.contains(candidate)) {
            return false;
        }
        if (candidate.equals(steve.blockPosition())) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(candidate);
        if (state.isAir() || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        float destroySpeed = state.getDestroySpeed(serverLevel, candidate);
        if (destroySpeed < 0.0F) {
            return false;
        }
        obstructionSet.add(candidate.immutable());
        obstructionQueue.addLast(candidate.immutable());
        return true;
    }

    private void handleBlockMined(BlockPos pos) {
        if (pos == null) {
            return;
        }
        lastMinedReference = pos.immutable();
        if (!obstructionQueue.isEmpty() && obstructionQueue.peekFirst().equals(pos)) {
            obstructionQueue.removeFirst();
            obstructionSet.remove(pos);
        }
        if (actualTarget != null && actualTarget.equals(pos)) {
            actualTarget = null;
        }
        currentTarget = resolveCurrentTarget();
        targetRecoveryAttempts = 0;
        syncDebugTarget();
    }

    private void syncDebugTarget() {
        BlockPos debugTarget = currentTarget;
        if (debugTarget == null) {
            debugTarget = findNearestAcceptableIgnoringReachability();
        }
        steve.setDebugTargetBlock(debugTarget);
    }

    private void trackMovementStuckState() {
        double currentX = steve.getX();
        double currentY = steve.getY();
        double currentZ = steve.getZ();
        double moved = Math.sqrt(
            (currentX - lastMoveX) * (currentX - lastMoveX)
                + (currentY - lastMoveY) * (currentY - lastMoveY)
                + (currentZ - lastMoveZ) * (currentZ - lastMoveZ)
        );
        if (moved < 0.04) {
            movementStuckTicks++;
        } else {
            movementStuckTicks = 0;
        }
        lastMoveX = currentX;
        lastMoveY = currentY;
        lastMoveZ = currentZ;
    }

    private void trackWaterTransitions() {
        boolean inWater = steve.isInWater();
        if (inWater) {
            waterSegmentTicks++;
        } else {
            waterSegmentTicks = 0;
            lastWaterRecoveryTarget = null;
        }
        if (inWater != lastInWater) {
            if (inWater) {
                SteveMod.LOGGER.info(
                    "[WATER_RECOVERY] Steve '{}' entered water while mining {}",
                    steve.getSteveName(),
                    targetBlock.getName().getString()
                );
            } else {
                SteveMod.LOGGER.info(
                    "[WATER_RECOVERY] Steve '{}' exited water after {} ticks",
                    steve.getSteveName(),
                    waterSegmentTicks
                );
            }
            lastInWater = inWater;
        }
    }

    private boolean handleWaterStuckRecovery() {
        if (!steve.isInWater()) {
            return false;
        }
        BlockPos steerTarget = currentTarget != null ? currentTarget : actualTarget;
        if (movementStuckTicks >= MOVEMENT_STUCK_REPATH_TICKS && waterReplanCooldown <= 0 && steerTarget != null) {
            waterReplanCooldown = WATER_REPLAN_INTERVAL;
            steve.getNavigation().moveTo(steerTarget.getX() + 0.5, steerTarget.getY(), steerTarget.getZ() + 0.5, 1.25);
            steerToward(steerTarget);
            SteveMod.LOGGER.info(
                "[WATER_RECOVERY] Steve '{}' repathing in water toward {} (stuckTicks={})",
                steve.getSteveName(),
                steerTarget,
                movementStuckTicks
            );
            return true;
        }

        if (movementStuckTicks < MOVEMENT_STUCK_SHORE_TICKS || !(steve.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos shore = findShoreRecoveryTarget(serverLevel);
        if (shore == null) {
            return false;
        }
        if (!shore.equals(lastWaterRecoveryTarget)) {
            SteveMod.LOGGER.warn(
                "[WATER_RECOVERY] Steve '{}' shoreline recovery toward {} (stuckTicks={} segmentTicks={})",
                steve.getSteveName(),
                shore,
                movementStuckTicks,
                waterSegmentTicks
            );
            lastWaterRecoveryTarget = shore;
        }
        steve.getNavigation().moveTo(shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5, 1.25);
        steerToward(shore);
        return true;
    }

    private BlockPos findShoreRecoveryTarget(ServerLevel level) {
        BlockPos origin = steve.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -WATER_SHORE_RECOVERY_RADIUS; dx <= WATER_SHORE_RECOVERY_RADIUS; dx++) {
            for (int dz = -WATER_SHORE_RECOVERY_RADIUS; dz <= WATER_SHORE_RECOVERY_RADIUS; dz++) {
                for (int dy = -WATER_SHORE_PROBE_Y; dy <= WATER_SHORE_PROBE_Y; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!isWalkableShore(level, pos) || !hasAdjacentWater(level, pos)) {
                        continue;
                    }
                    double score = pos.distSqr(origin);
                    if (best == null || score < bestScore) {
                        best = pos.immutable();
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private boolean isWalkableShore(ServerLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState below = level.getBlockState(pos.below());
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        if (!feet.isAir() || !head.isAir()) {
            return false;
        }
        return below.isSolid();
    }

    private boolean hasAdjacentWater(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(pos.relative(dir)).is(Fluids.WATER)) {
                return true;
            }
        }
        return level.getFluidState(pos.below()).is(Fluids.WATER);
    }

    private void steerToward(BlockPos pos) {
        double dx = pos.getX() + 0.5 - steve.getX();
        double dy = pos.getY() + 0.5 - steve.getY();
        double dz = pos.getZ() + 0.5 - steve.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) {
            return;
        }
        double push = 0.09;
        steve.setDeltaMovement(
            steve.getDeltaMovement().add(dx / len * push, dy / len * push * 0.7, dz / len * push)
        );
    }

    private BlockPos findApproachAnchor(BlockPos target) {
        if (target == null) {
            return null;
        }
        BlockPos origin = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockPos pos = target.offset(dx, dy, dz);
                        BlockState state = steve.level().getBlockState(pos);
                        if (!isOpenAdjacentBlock(state)) {
                            continue;
                        }
                        if (steve.getNavigation().createPath(pos, 0) == null) {
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
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private boolean ensureToolEquipped() {
        ToolType required = toolRequirement.required();
        ToolType preferred = toolRequirement.preferred();
        if (required == ToolType.NONE && preferred == ToolType.NONE) {
            steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return true;
        }

        ItemStack tool = steve.findToolForRequirement(toolRequirement);

        if (tool.isEmpty() && required != ToolType.NONE) {
            if (required == ToolType.PICKAXE && toolRequirement.minTier() != null && toolRequirement.minTier() != ToolCapabilityMap.ToolTier.NONE) {
                steve.sendChatMessage(
                    "I need at least a " + toolRequirement.minTier().label() + " pickaxe to mine "
                        + targetBlock.getName().getString() + "."
                );
            } else {
                steve.sendChatMessage("I need a " + required.label() + " to mine " + targetBlock.getName().getString() + ".");
            }
            return false;
        }

        if (!tool.isEmpty()) {
            steve.setItemInHand(InteractionHand.MAIN_HAND, tool);
            SteveMod.LOGGER.info(
                "Steve '{}' equipped {} for mining {}",
                steve.getSteveName(),
                preferred != ToolType.NONE && ToolCapabilityMap.ToolType.matches(preferred, tool) ? preferred.label() : required.label(),
                targetBlock.getName().getString()
            );
        } else {
            steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        return true;
    }

    private Block parseBlock(String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return Blocks.AIR;
        }
        blockName = blockName.toLowerCase().replace(" ", "_");
        
        Map<String, String> resourceToOre = new HashMap<>() {{
            put("iron", "iron_ore");
            put("diamond", "diamond_ore");
            put("coal", "coal_ore");
            put("gold", "gold_ore");
            put("copper", "copper_ore");
            put("redstone", "redstone_ore");
            put("lapis", "lapis_ore");
            put("emerald", "emerald_ore");
            // Wood/log aliases
            put("wood", "oak_log");
            put("woods", "oak_log");
            put("log", "oak_log");
            put("logs", "oak_log");
            put("tree", "oak_log");
            put("trees", "oak_log");
        }};
        
        if (resourceToOre.containsKey(blockName)) {
            blockName = resourceToOre.get(blockName);
        }
        
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        
        Identifier identifier = Identifier.tryParse(blockName);
        if (identifier == null) {
            return Blocks.AIR;
        }
        return BuiltInRegistries.BLOCK.getOptional(identifier).orElse(Blocks.AIR);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private int positiveOrDefault(int preferred, int fallback) {
        if (preferred > 0) {
            return preferred;
        }
        return Math.max(1, fallback);
    }

    private boolean isGenericWoodRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.trim().toLowerCase().replace(" ", "_");
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized.equals("wood")
            || normalized.equals("woods")
            || normalized.equals("log")
            || normalized.equals("logs")
            || normalized.equals("tree")
            || normalized.equals("trees");
    }

    private boolean isWoodTask() {
        return genericWoodRequest || isWoodLikeBlockId(targetBlockId);
    }

    private void addAlternativeAcceptableBlocks() {
        Object raw = task.getParameter("alternatives");
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (entry == null) {
                continue;
            }
            Block alt = parseBlock(entry.toString());
            if (alt == null || alt == Blocks.AIR) {
                continue;
            }
            String altId = BuiltInRegistries.BLOCK.getKey(alt).toString();
            acceptableBlockIds.add(altId);
            acceptableBlockIds.addAll(BlockTransitionMap.getAcceptableSourcesRecursive(altId));
        }
    }

    private void mineAndCollect(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) {
            return;
        }
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            steve.level().destroyBlock(pos, true);
            return;
        }

        ItemStack tool = steve.getItemInHand(InteractionHand.MAIN_HAND);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, serverLevel.getBlockEntity(pos), steve, tool);
        steve.level().destroyBlock(pos, false);

        for (ItemStack drop : drops) {
            ItemStack remaining = steve.addToInventory(drop);
            if (!remaining.isEmpty()) {
                serverLevel.addFreshEntity(new ItemEntity(
                    serverLevel,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    remaining
                ));
            }
        }
    }
}
