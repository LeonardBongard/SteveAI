package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
    private static final int EXPLORE_RETRY_INTERVAL = 40;
    private static final int EXPLORE_RADIUS = 12;
    private BlockPos exploreTarget;
    private int ticksSinceExplore = 0;

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
        exploreTarget = null;
        
        targetBlock = parseBlock(blockName);
        
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }
        
        //steve.setFlying(true);
        //steve.setInvulnerableBuilding(true); // Prevent suffocation damage while mining
        
        equipIronPickaxe();
        
        SteveMod.LOGGER.info("Steve '{}' mining {} - using perception snapshot", 
            steve.getSteveName(), targetBlock.getName().getString());

        currentTarget = findNearestVisibleTarget();
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
            currentTarget = findNearestVisibleTarget();
            if (currentTarget == null) {
                if (minedCount >= targetQuantity) {
                    steve.setFlying(false);
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
            steve.teleportTo(currentTarget.getX() + 0.5, currentTarget.getY(), currentTarget.getZ() + 0.5);
            
            steve.swing(InteractionHand.MAIN_HAND, true);
            
            steve.level().destroyBlock(currentTarget, true);
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
        int startY = Math.min(pos.getY() + 3, steve.level().getMaxBuildHeight() - 1);
        for (int y = startY; y >= pos.getY() - 6 && y > steve.level().getMinBuildHeight(); y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState state = steve.level().getBlockState(check);
            if (state.isSolid()) {
                return check.above();
            }
        }
        return null;
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
}
