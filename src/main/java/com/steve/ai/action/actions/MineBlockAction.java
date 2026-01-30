package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final int CONE_RANGE = 12;
    private static final float CONE_YAW = 30.0f;
    private static final float CONE_PITCH = 18.0f;
    private static final float YAW_STEP = 6.0f;
    private static final float PITCH_STEP = 6.0f;
    private static final int MEMORY_SEARCH_RADIUS = 48;
    private static final int LOCAL_SCAN_RADIUS = 6;
    private float scanYawOffset = 0.0f;
    private float scanYawDirection = 1.0f;
    private static final float SCAN_SWEEP_LIMIT = 60.0f;
    private static final float SCAN_SWEEP_STEP = 6.0f;
    private float scanPitchOffset = 0.0f;
    private float scanPitchDirection = 1.0f;
    private static final float SCAN_PITCH_LIMIT = 20.0f;
    private static final float SCAN_PITCH_STEP = 3.0f;
    private float scanYawAnchor = 0.0f;
    private float scanPitchAnchor = 0.0f;
    
    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetQuantity = task.getIntParameter("quantity", 8); // Mine reasonable amount by default
        minedCount = 0;
        ticksRunning = 0;
        ticksSinceLastTorch = 0;
        ticksSinceLastMine = 0;
        
        targetBlock = parseBlock(blockName);
        
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }
        
        steve.setFlying(true);
        steve.setInvulnerableBuilding(true); // Prevent suffocation damage while mining
        
        equipIronPickaxe();
        
        SteveMod.LOGGER.info("Steve '{}' mining {} - visible scan + memory", 
            steve.getSteveName(), targetBlock.getName().getString());

        scanYawAnchor = steve.getYRot();
        scanPitchAnchor = steve.getXRot();
        currentTarget = findVisibleTargetInCone();
        if (currentTarget == null) {
            currentTarget = findNearestFromMemory();
            if (currentTarget == null) {
                currentTarget = findNearbyTargetInRadius();
            }
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastTorch++;
        ticksSinceLastMine++;
        
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            steve.setInvulnerableBuilding(false);
            steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
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
            sweepHead();
            currentTarget = findVisibleTargetInCone();
            if (currentTarget == null) {
                currentTarget = findNearestFromMemory();
                if (currentTarget == null) {
                    currentTarget = findNearbyTargetInRadius();
                }
            }
            if (currentTarget == null) {
                if (minedCount >= targetQuantity) {
                    steve.setFlying(false);
                    steve.setInvulnerableBuilding(false);
                    steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                    return;
                }
                return;
            }
        }
        
        if (steve.level().getBlockState(currentTarget).getBlock() == targetBlock) {
            double distance = steve.distanceToSqr(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
            );
            if (distance > 4.0) {
                steve.getNavigation().moveTo(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY(),
                    currentTarget.getZ() + 0.5,
                    1.2
                );
                return;
            }
            
            steve.swing(InteractionHand.MAIN_HAND, true);
            
            mineAndCollect(currentTarget);
            minedCount++;
            ticksSinceLastMine = 0; // Reset delay timer
            
            SteveMod.LOGGER.info("Steve '{}' moved to ore and mined {} at {} - Total: {}/{}", 
                steve.getSteveName(), targetBlock.getName().getString(), currentTarget, 
                minedCount, targetQuantity);
            
            if (minedCount >= targetQuantity) {
                steve.setFlying(false);
                steve.setInvulnerableBuilding(false);
                steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                return;
            }
            
            currentTarget = null;
        } else {
            currentTarget = null;
        }
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.setInvulnerableBuilding(false);
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Mine " + targetQuantity + " " + targetBlock.getName().getString() + " (" + minedCount + " found)";
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

    private BlockPos findVisibleTargetInCone() {
        Vec3 eyePos = steve.getEyePosition(1.0F);
        float baseYaw = scanYawAnchor + scanYawOffset;
        float basePitch = scanPitchAnchor + scanPitchOffset;

        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (float yawOffset = -CONE_YAW; yawOffset <= CONE_YAW; yawOffset += YAW_STEP) {
            for (float pitchOffset = -CONE_PITCH; pitchOffset <= CONE_PITCH; pitchOffset += PITCH_STEP) {
                Vec3 dir = Vec3.directionFromRotation(basePitch + pitchOffset, baseYaw + yawOffset);
                boolean debugScan = SteveConfig.ENABLE_DEBUG_OVERLAY.get() && (ticksRunning % 20 == 0);
                BlockPos hitPos = scanRayForTargetAndRecord(eyePos, dir, CONE_RANGE, debugScan);
                if (hitPos == null) {
                    continue;
                }

                double dist = eyePos.distanceToSqr(Vec3.atCenterOf(hitPos));
                if (dist < bestDist) {
                    bestDist = dist;
                    bestPos = hitPos;
                }
            }
        }

        return bestPos;
    }

    private void sweepHead() {
        scanYawOffset += SCAN_SWEEP_STEP * scanYawDirection;
        if (scanYawOffset >= SCAN_SWEEP_LIMIT) {
            scanYawOffset = SCAN_SWEEP_LIMIT;
            scanYawDirection = -1.0f;
        } else if (scanYawOffset <= -SCAN_SWEEP_LIMIT) {
            scanYawOffset = -SCAN_SWEEP_LIMIT;
            scanYawDirection = 1.0f;
        }
        scanPitchOffset += SCAN_PITCH_STEP * scanPitchDirection;
        if (scanPitchOffset >= SCAN_PITCH_LIMIT) {
            scanPitchOffset = SCAN_PITCH_LIMIT;
            scanPitchDirection = -1.0f;
        } else if (scanPitchOffset <= -SCAN_PITCH_LIMIT) {
            scanPitchOffset = -SCAN_PITCH_LIMIT;
            scanPitchDirection = 1.0f;
        }
        steve.setYRot(scanYawAnchor + scanYawOffset);
        steve.setXRot(scanPitchAnchor + scanPitchOffset);
        steve.yHeadRot = steve.getYRot();
    }

    private BlockPos findNearestFromMemory() {
        List<BlockPos> matches = steve.getBlockMemory().findNearestMatches(
            targetBlock,
            steve.blockPosition(),
            MEMORY_SEARCH_RADIUS,
            20
        );
        return matches.isEmpty() ? null : matches.get(0);
    }

    private BlockPos findNearbyTargetInRadius() {
        BlockPos origin = steve.blockPosition();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -LOCAL_SCAN_RADIUS; dx <= LOCAL_SCAN_RADIUS; dx++) {
            for (int dy = -LOCAL_SCAN_RADIUS; dy <= LOCAL_SCAN_RADIUS; dy++) {
                for (int dz = -LOCAL_SCAN_RADIUS; dz <= LOCAL_SCAN_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    if (state.getBlock() != targetBlock) {
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

    private BlockPos scanRayForTargetAndRecord(Vec3 start, Vec3 dir, int range, boolean debugScan) {
        ServerLevel serverLevel = steve.level() instanceof ServerLevel level ? level : null;
        for (int step = 1; step <= range; step++) {
            Vec3 sample = start.add(dir.scale(step));
            BlockPos pos = new BlockPos(Mth.floor(sample.x), Mth.floor(sample.y), Mth.floor(sample.z));
            BlockState state = steve.level().getBlockState(pos);
            if (debugScan && serverLevel != null && (step % 2 == 0)) {
                serverLevel.sendParticles(ParticleTypes.END_ROD, sample.x, sample.y, sample.z, 1, 0, 0, 0, 0);
            }
            if (state.isAir() || state.getBlock() == Blocks.BEDROCK) {
                continue;
            }
            steve.getBlockMemory().record(pos, state);
            if (state.getBlock() == targetBlock) {
                if (debugScan && serverLevel != null) {
                    serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, sample.x, sample.y, sample.z, 2, 0, 0, 0, 0);
                }
                return pos;
            }
        }
        return null;
    }

    /**
     * Equip an iron pickaxe for mining
     */
    private void equipIronPickaxe() {
        // Give Steve an iron pickaxe if he doesn't have one
        net.minecraft.world.item.ItemStack pickaxe = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.IRON_PICKAXE
        );
        steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, pickaxe);
        SteveMod.LOGGER.info("Steve '{}' equipped iron pickaxe for mining", steve.getSteveName());
    }

    private Block parseBlock(String blockName) {
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
