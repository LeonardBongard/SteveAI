package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

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
    private static final int LOCAL_SCAN_RADIUS = 6;
    private static final int EXPLORE_RETRY_INTERVAL = 40;
    private static final int EXPLORE_RADIUS = 12;
    private static final int RESCAN_INTERVAL = 10;
    private static final int TARGET_STALL_TICKS = 60;
    private static final int MINING_DURATION = 40;
    private static final int BLOCKED_TARGET_TTL = 200;
    private BlockPos exploreTarget;
    private int ticksSinceExplore = 0;
    private int ticksSinceRescan = 0;
    private int targetStallTicks = 0;
    private double lastTargetDistance = Double.MAX_VALUE;
    private int miningProgressTicks = 0;
    private BlockPos lastMiningTarget = null;
    private final Map<BlockPos, Integer> blockedTargets = new HashMap<>();
    
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
        ticksSinceExplore = 0;
        ticksSinceRescan = 0;
        targetStallTicks = 0;
        lastTargetDistance = Double.MAX_VALUE;
        miningProgressTicks = 0;
        lastMiningTarget = null;
        exploreTarget = null;
        
        targetBlock = parseBlock(blockName);
        
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }
        
        steve.setInvulnerableBuilding(true); // Prevent suffocation damage while mining
        
        equipIronPickaxe();
        
        SteveMod.LOGGER.info("Steve '{}' mining {} - using perception snapshot", 
            steve.getSteveName(), targetBlock.getName().getString());
        steve.forceVisibleScan();
        currentTarget = findNearestVisibleTarget();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastTorch++;
        ticksSinceLastMine++;
        ticksSinceRescan++;
        if (!blockedTargets.isEmpty()) {
            blockedTargets.entrySet().removeIf(entry -> ticksRunning - entry.getValue() > BLOCKED_TARGET_TTL);
        }
        
        if (ticksRunning > MAX_TICKS) {
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
            if (ticksSinceRescan >= RESCAN_INTERVAL) {
                steve.forceVisibleScan();
                ticksSinceRescan = 0;
            }
            currentTarget = findNearestVisibleTarget();
            if (currentTarget == null) {
                currentTarget = findNearbyTargetInRadius();
            }
            if (currentTarget == null) {
                if (minedCount >= targetQuantity) {
                    steve.setInvulnerableBuilding(false);
                    steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                    return;
                }
                ticksSinceExplore++;
                if (ticksSinceExplore >= EXPLORE_RETRY_INTERVAL) {
                    ticksSinceExplore = 0;
                    exploreTarget = pickExploreTarget();
                    if (exploreTarget != null) {
                        steve.getNavigation().moveTo(
                            exploreTarget.getX() + 0.5,
                            exploreTarget.getY(),
                            exploreTarget.getZ() + 0.5,
                            1.0
                        );
                        SteveMod.LOGGER.info("Steve '{}' exploring for {} at {}", 
                            steve.getSteveName(), targetBlock.getName().getString(), exploreTarget);
                    }
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
            if (distance >= lastTargetDistance - 0.01) {
                targetStallTicks++;
            } else {
                targetStallTicks = 0;
                lastTargetDistance = distance;
            }
            if (targetStallTicks >= TARGET_STALL_TICKS) {
                SteveMod.LOGGER.info("Steve '{}' stuck approaching target {}; forcing rescan", steve.getSteveName(), currentTarget);
                blockedTargets.put(currentTarget.immutable(), ticksRunning);
                currentTarget = null;
                targetStallTicks = 0;
                lastTargetDistance = Double.MAX_VALUE;
                miningProgressTicks = 0;
                lastMiningTarget = null;
                steve.getNavigation().stop();
                steve.forceVisibleScan();
                return;
            }
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
            if (lastMiningTarget == null || !lastMiningTarget.equals(currentTarget)) {
                lastMiningTarget = currentTarget;
                miningProgressTicks = 0;
            }
            miningProgressTicks++;
            if (miningProgressTicks < MINING_DURATION) {
                return;
            }
            miningProgressTicks = 0;
            
            mineAndCollect(currentTarget);
            minedCount++;
            ticksSinceLastMine = 0; // Reset delay timer
            steve.forceVisibleScan();
            ticksSinceRescan = 0;
            targetStallTicks = 0;
            lastTargetDistance = Double.MAX_VALUE;
            miningProgressTicks = 0;
            lastMiningTarget = null;
            
            SteveMod.LOGGER.info("Steve '{}' moved to ore and mined {} at {} - Total: {}/{}", 
                steve.getSteveName(), targetBlock.getName().getString(), currentTarget, 
                minedCount, targetQuantity);
            
            if (minedCount >= targetQuantity) {
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
        steve.setInvulnerableBuilding(false);
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
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

        for (int dx = -LOCAL_SCAN_RADIUS; dx <= LOCAL_SCAN_RADIUS; dx++) {
            for (int dy = -LOCAL_SCAN_RADIUS; dy <= LOCAL_SCAN_RADIUS; dy++) {
                for (int dz = -LOCAL_SCAN_RADIUS; dz <= LOCAL_SCAN_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    if (state.getBlock() != targetBlock) {
                        continue;
                    }
                    if (blockedTargets.containsKey(pos)) {
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
        String targetId = BuiltInRegistries.BLOCK.getKey(targetBlock).toString();
        VisibleBlockEntry best = null;
        for (VisibleBlockEntry entry : entries) {
            if (!targetId.equals(entry.blockId())) {
                continue;
            }
            if (blockedTargets.containsKey(entry.position())) {
                continue;
            }
            if (best == null || entry.distance() < best.distance()) {
                best = entry;
            }
        }
        return best != null ? best.position() : null;
    }

    private BlockPos pickExploreTarget() {
        BlockPos origin = steve.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = steve.getRandom().nextInt(EXPLORE_RADIUS * 2 + 1) - EXPLORE_RADIUS;
            int dz = steve.getRandom().nextInt(EXPLORE_RADIUS * 2 + 1) - EXPLORE_RADIUS;
            BlockPos base = origin.offset(dx, 0, dz);
            BlockPos ground = findGroundAbove(base);
            if (ground != null) {
                return ground;
            }
        }
        return null;
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
