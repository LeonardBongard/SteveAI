package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.action.search.StuckReason;
import com.steve.ai.config.StevePersona;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PathfindAction extends BaseAction {
    private BlockPos targetPos;
    private int ticksRunning;
    private int stuckTicks;
    private int arrivalRange;
    private double lastX;
    private double lastY;
    private double lastZ;
    private static final int MAX_TICKS = 600; // 30 seconds timeout
    private static final int STUCK_REPATH_TICKS = 20;
    private static final int STUCK_SWIM_RECOVERY_TICKS = 45;
    private static final int STUCK_SHORE_RECOVERY_TICKS = 70;
    private static final int STUCK_TERRAIN_RECOVERY_TICKS = 40;
    private static final int TERRAIN_RECOVERY_COOLDOWN_TICKS = 20;
    private static final int MAX_TERRAIN_RECOVERY_ACTIONS = 18;
    private static final int SWIM_REPLAN_INTERVAL = 30;
    private static final int SHORE_RECOVERY_RADIUS = 6;
    private static final int SHORE_PROBE_Y = 2;
    private static final int MAX_SWIM_SEGMENT_LOGS = 12;

    private boolean lastInWater;
    private int swimSegmentTicks;
    private int swimReplanCooldown;
    private int swimSegmentLogs;
    private int terrainRecoveryCooldown;
    private int terrainRecoveryActions;
    private BlockPos lastRecoveryTarget;
    private StevePersona persona = StevePersona.TUNNELER;
    private StuckReason currentStuckReason = StuckReason.NONE;
    private int stuckReasonStartTick = 0;
    private int lastStuckLogTick = Integer.MIN_VALUE / 4;
    private static final int STUCK_LOG_REPEAT_TICKS = 80;

    public PathfindAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        arrivalRange = Math.max(1, task.getIntParameter("range", 2));
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        stuckTicks = 0;
        lastX = steve.getX();
        lastY = steve.getY();
        lastZ = steve.getZ();
        lastInWater = steve.isInWater();
        swimSegmentTicks = 0;
        swimReplanCooldown = 0;
        swimSegmentLogs = 0;
        terrainRecoveryCooldown = 0;
        terrainRecoveryActions = 0;
        lastRecoveryTarget = null;
        persona = steve.getPersona();
        currentStuckReason = StuckReason.NONE;
        stuckReasonStartTick = 0;
        lastStuckLogTick = Integer.MIN_VALUE / 4;

        steve.getNavigation().setCanFloat(true);
        steve.getNavigation().moveTo(x, y, z, 1.0);
        SteveMod.LOGGER.info(
            "[SWIM] Steve '{}' pathfind start target={} inWater={} persona={}",
            steve.getSteveName(),
            targetPos,
            lastInWater,
            persona
        );
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        trackWaterSegmentTransitions();
        if (swimReplanCooldown > 0) {
            swimReplanCooldown--;
        }
        if (terrainRecoveryCooldown > 0) {
            terrainRecoveryCooldown--;
        }
        if (steve.isInWater()) {
            swimSegmentTicks++;
        } else {
            swimSegmentTicks = 0;
        }

        if (steve.blockPosition().closerThan(targetPos, arrivalRange)) {
            if (steve.isInWater()) {
                SteveMod.LOGGER.info("[SWIM] Steve '{}' reached target while in water segment", steve.getSteveName());
            }
            result = ActionResult.success("Reached target position");
            return;
        }
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Pathfinding timeout");
            return;
        }

        trackStuckState();

        if (steve.getNavigation().isDone() && !steve.blockPosition().closerThan(targetPos, arrivalRange)) {
            if (steve.isInWater()) {
                SteveMod.LOGGER.info("[SWIM] Steve '{}' navigation done mid-water; replanning", steve.getSteveName());
            }
            steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), pathSpeed());
        }

        if (stuckTicks >= STUCK_REPATH_TICKS && !steve.blockPosition().closerThan(targetPos, arrivalRange)) {
            noteStuckReason(StuckReason.NO_PROGRESS_WHILE_MOVING, "path movement is not reducing distance to target");
            if (swimReplanCooldown <= 0) {
                SteveMod.LOGGER.info(
                    "[SWIM] Steve '{}' repath trigger stuckTicks={} inWater={}",
                    steve.getSteveName(),
                    stuckTicks,
                    steve.isInWater()
                );
                steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), pathSpeed());
                swimReplanCooldown = SWIM_REPLAN_INTERVAL;
            }
        }

        if (!steve.isInWater()
            && stuckTicks >= STUCK_TERRAIN_RECOVERY_TICKS
            && terrainRecoveryCooldown <= 0
            && terrainRecoveryActions < MAX_TERRAIN_RECOVERY_ACTIONS
            && !steve.blockPosition().closerThan(targetPos, arrivalRange)) {
            if (attemptTerrainRecoveryStep()) {
                terrainRecoveryActions++;
                terrainRecoveryCooldown = TERRAIN_RECOVERY_COOLDOWN_TICKS;
                stuckTicks = 0;
                steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), pathSpeed());
                return;
            }
        }

        // Land-stuck pathing should fail fast so parent goal can choose another tactic.
        if (stuckTicks >= 220 && !steve.isInWater()) {
            noteStuckReason(StuckReason.UNREACHABLE_TARGET, "land path remained blocked after recovery attempts");
            result = ActionResult.failure("Path blocked/unreachable: " + targetPos, false);
            return;
        }

        if (stuckTicks >= STUCK_SWIM_RECOVERY_TICKS && steve.isInWater()) {
            SteveMod.LOGGER.info(
                "[SWIM] Steve '{}' swim push recovery stuckTicks={} segmentTicks={}",
                steve.getSteveName(),
                stuckTicks,
                swimSegmentTicks
            );
            double dx = targetPos.getX() + 0.5 - steve.getX();
            double dy = targetPos.getY() + 0.5 - steve.getY();
            double dz = targetPos.getZ() + 0.5 - steve.getZ();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0.001) {
                double push = 0.12;
                steve.setDeltaMovement(
                    steve.getDeltaMovement().add(dx / len * push, dy / len * push * 0.7, dz / len * push)
                );
            }
        }

        if (stuckTicks >= STUCK_SHORE_RECOVERY_TICKS && steve.isInWater() && steve.level() instanceof ServerLevel serverLevel) {
            BlockPos shore = findShoreRecoveryTarget(serverLevel);
            if (shore != null) {
                if (!shore.equals(lastRecoveryTarget)) {
                    SteveMod.LOGGER.warn(
                        "[SWIM] Steve '{}' shoreline recovery target={} stuckTicks={}",
                        steve.getSteveName(),
                        shore,
                        stuckTicks
                    );
                    lastRecoveryTarget = shore;
                }
                steve.getNavigation().moveTo(shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5, 1.25);
                steerToward(shore);
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        String base = "Pathfind to " + targetPos;
        if (currentStuckReason.isStuck()) {
            base += " stuck=" + currentStuckReason.name().toLowerCase(java.util.Locale.ROOT);
        }
        return base;
    }

    private void trackStuckState() {
        double currentX = steve.getX();
        double currentY = steve.getY();
        double currentZ = steve.getZ();
        double moved = Math.sqrt(
            (currentX - lastX) * (currentX - lastX)
                + (currentY - lastY) * (currentY - lastY)
                + (currentZ - lastZ) * (currentZ - lastZ)
        );
        if (moved < 0.04) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            clearStuckReason("movement-progress");
        }
        lastX = currentX;
        lastY = currentY;
        lastZ = currentZ;
    }

    private void noteStuckReason(StuckReason reason, String detail) {
        StuckReason normalized = reason == null ? StuckReason.NONE : reason;
        if (!normalized.isStuck()) {
            clearStuckReason(detail);
            return;
        }
        boolean changed = normalized != currentStuckReason;
        if (changed) {
            currentStuckReason = normalized;
            stuckReasonStartTick = ticksRunning;
            lastStuckLogTick = Integer.MIN_VALUE / 4;
        }
        if (!changed && ticksRunning - lastStuckLogTick < STUCK_LOG_REPEAT_TICKS) {
            return;
        }
        lastStuckLogTick = ticksRunning;
        String goal = steve.getMemory().getCurrentGoal();
        SteveMod.LOGGER.info(
            "[STUCK] Steve '{}' reason={} durationTicks={} goal='{}' action=pathfind target={} detail={}",
            steve.getSteveName(),
            currentStuckReason,
            Math.max(0, ticksRunning - stuckReasonStartTick),
            goal == null ? "" : goal,
            targetPos,
            detail == null ? "" : detail
        );
    }

    private void clearStuckReason(String resolution) {
        if (!currentStuckReason.isStuck()) {
            return;
        }
        SteveMod.LOGGER.info(
            "[STUCK] Steve '{}' cleared reason={} after {} ticks ({})",
            steve.getSteveName(),
            currentStuckReason,
            Math.max(0, ticksRunning - stuckReasonStartTick),
            resolution == null ? "" : resolution
        );
        currentStuckReason = StuckReason.NONE;
        stuckReasonStartTick = ticksRunning;
        lastStuckLogTick = Integer.MIN_VALUE / 4;
    }

    private double pathSpeed() {
        return steve.isInWater() ? 1.25 : 1.0;
    }

    private void trackWaterSegmentTransitions() {
        boolean inWater = steve.isInWater();
        if (inWater == lastInWater) {
            return;
        }
        if (swimSegmentLogs >= MAX_SWIM_SEGMENT_LOGS) {
            lastInWater = inWater;
            return;
        }
        if (inWater) {
            SteveMod.LOGGER.info(
                "[SWIM] Steve '{}' entered water segment toward {}",
                steve.getSteveName(),
                targetPos
            );
        } else {
            SteveMod.LOGGER.info(
                "[SWIM] Steve '{}' exited water segment after {} ticks",
                steve.getSteveName(),
                swimSegmentTicks
            );
        }
        swimSegmentLogs++;
        lastInWater = inWater;
    }

    private BlockPos findShoreRecoveryTarget(ServerLevel level) {
        BlockPos origin = steve.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -SHORE_RECOVERY_RADIUS; dx <= SHORE_RECOVERY_RADIUS; dx++) {
            for (int dz = -SHORE_RECOVERY_RADIUS; dz <= SHORE_RECOVERY_RADIUS; dz++) {
                for (int dy = -SHORE_PROBE_Y; dy <= SHORE_PROBE_Y; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!isWalkableShore(level, pos)) {
                        continue;
                    }
                    if (!hasAdjacentWater(level, pos)) {
                        continue;
                    }
                    candidates.add(pos.immutable());
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(this::distToTargetSqr));
        return candidates.get(0);
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
            BlockPos check = pos.relative(dir);
            if (level.getFluidState(check).is(Fluids.WATER)) {
                return true;
            }
        }
        return level.getFluidState(pos.below()).is(Fluids.WATER);
    }

    private double distToTargetSqr(BlockPos pos) {
        return targetPos.distSqr(pos);
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

    private boolean attemptTerrainRecoveryStep() {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos origin = steve.blockPosition();
        int stepX = Integer.compare(targetPos.getX(), origin.getX());
        int stepZ = Integer.compare(targetPos.getZ(), origin.getZ());
        if (stepX == 0 && stepZ == 0) {
            stepZ = 1;
        }
        BlockPos forward = origin.offset(stepX, 0, stepZ);
        int deltaY = targetPos.getY() - origin.getY();

        // 1) Vertical intent: mine staircase up/down.
        if (deltaY >= 2 && tryMineStairUp(serverLevel, origin, forward)) {
            return true;
        }
        if (deltaY <= -2 && tryMineStairDown(serverLevel, origin, forward)) {
            return true;
        }

        // 2) Bridge across small gap in front when possible.
        if (tryPlaceBridgeBlock(serverLevel, forward)) {
            return true;
        }

        // 3) Horizontal tunnel default fallback.
        if (tryMineForwardTunnel(serverLevel, forward)) {
            return true;
        }

        return false;
    }

    private boolean tryMineStairUp(ServerLevel level, BlockPos origin, BlockPos forward) {
        BlockPos stepUp = forward.above();
        BlockPos headClear = stepUp.above();
        if (mineIfBreakable(level, stepUp, "stair-up step")) {
            return true;
        }
        if (mineIfBreakable(level, headClear, "stair-up headroom")) {
            return true;
        }
        // Also clear immediate headroom above current position if boxed in.
        return mineIfBreakable(level, origin.above(), "stair-up self headroom");
    }

    private boolean tryMineStairDown(ServerLevel level, BlockPos origin, BlockPos forward) {
        BlockPos downStep = forward.below();
        if (mineIfBreakable(level, forward, "stair-down front")) {
            return true;
        }
        return mineIfBreakable(level, downStep, "stair-down step");
    }

    private boolean tryMineForwardTunnel(ServerLevel level, BlockPos forward) {
        if (persona.prefersCaveExploration()) {
            // Explorer persona avoids strip-mining style recovery and prefers reroute/exploration.
            return false;
        }
        if (mineIfBreakable(level, forward, "forward tunnel")) {
            return true;
        }
        return mineIfBreakable(level, forward.above(), "forward headroom");
    }

    private boolean mineIfBreakable(ServerLevel level, BlockPos pos, String reason) {
        if (pos == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR)) {
            return false;
        }
        float destroySpeed = state.getDestroySpeed(level, pos);
        if (destroySpeed < 0.0F) {
            return false;
        }
        level.destroyBlock(pos, true);
        SteveMod.LOGGER.info(
            "[TERRAIN_RECOVERY] Steve '{}' mined {} at {} ({})",
            steve.getSteveName(),
            state.getBlock().getName().getString(),
            pos,
            reason
        );
        return true;
    }

    private boolean tryPlaceBridgeBlock(ServerLevel level, BlockPos forward) {
        if (forward == null) {
            return false;
        }
        BlockPos bridgePos = forward.below();
        BlockState existing = level.getBlockState(bridgePos);
        if (!existing.isAir() && !existing.getFluidState().is(Fluids.WATER)) {
            return false;
        }

        Item bridgeItem = chooseBridgeItemFromInventory();
        if (bridgeItem == null) {
            return false;
        }
        ItemStack moved = steve.extractItem(bridgeItem, 1);
        if (moved.isEmpty()) {
            return false;
        }
        if (!(bridgeItem instanceof BlockItem blockItem)) {
            steve.addToInventory(moved);
            return false;
        }
        Block block = blockItem.getBlock();
        BlockState placeState = block.defaultBlockState();
        if (!placeState.isSolid()) {
            steve.addToInventory(moved);
            return false;
        }
        level.setBlock(bridgePos, placeState, 3);
        SteveMod.LOGGER.info(
            "[TERRAIN_RECOVERY] Steve '{}' placed bridge block {} at {}",
            steve.getSteveName(),
            BuiltInRegistries.ITEM.getKey(bridgeItem),
            bridgePos
        );
        return true;
    }

    private Item chooseBridgeItemFromInventory() {
        List<String> preferred = List.of(
            "minecraft:cobblestone",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:granite",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks"
        );
        for (String id : preferred) {
            Item item = parseItem(id);
            if (item == null || steve.getItemCount(item) <= 0) {
                continue;
            }
            if (item instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().isSolid()) {
                return item;
            }
        }
        // Fallback: any solid block item in inventory summary.
        var counts = steve.getInventoryItemCountsById();
        for (String id : counts.keySet()) {
            Item item = parseItem(id);
            if (item == null || steve.getItemCount(item) <= 0) {
                continue;
            }
            if (item instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().isSolid()) {
                return item;
            }
        }
        return null;
    }

    private Item parseItem(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase().replace(" ", "_");
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
