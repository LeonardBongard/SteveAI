package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.config.StevePersona;
import com.steve.ai.config.StevePersonaProfiles;
import com.steve.ai.config.SteveRuntimeSettings;
import com.steve.ai.food.FoodTargetResolver;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.VisibleBlockEntry;
import com.steve.ai.resource.SourceRiskManager;
import com.steve.ai.mining.ToolCapabilityMap;
import com.steve.ai.mining.ToolCapabilityMap.ToolRequirement;
import com.steve.ai.mining.ToolCapabilityMap.ToolTier;
import com.steve.ai.mining.ToolCapabilityMap.ToolType;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DEBUG_STATUS =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DEBUG_TARGET_BLOCK =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> INVENTORY_SUMMARY =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MEMORY_BLOCKS_SUMMARY =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MEMORY_CHESTS_SUMMARY =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MEMORY_BLOCK_POSITIONS =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MEMORY_CHEST_POSITIONS =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MEMORY_EPISODIC_POSITIONS =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MEMORY_SEMANTIC_SUMMARY =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> PLAYTEST_STATE =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> PLAYTEST_INFO =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TASK_STATUS_SUMMARY =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private int tickCounter = 0;
    private int lastVisibleScanTick = -1;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
    private int pickupCooldown = 0;
    private final SteveFoodData foodData = new SteveFoodData();
    private final Map<String, Set<BlockPos>> ownedStations = new HashMap<>();
    private double lastTickX;
    private double lastTickY;
    private double lastTickZ;
    private boolean navigationConfigured = false;
    private static final int AUTO_EAT_THRESHOLD = 16;
    private static final int EMERGENCY_EAT_THRESHOLD = 4;

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.setCustomNameVisible(true);
        
        this.isInvulnerable = true;
        this.setInvulnerable(true);
        this.setPersistenceRequired();
        this.lastTickX = this.getX();
        this.lastTickY = this.getY();
        this.lastTickZ = this.getZ();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STEVE_NAME, "Steve");
        builder.define(DEBUG_STATUS, "Idle");
        builder.define(DEBUG_TARGET_BLOCK, "");
        builder.define(INVENTORY_SUMMARY, "Inventory empty");
        builder.define(MEMORY_BLOCKS_SUMMARY, "No block memory");
        builder.define(MEMORY_CHESTS_SUMMARY, "No chest memory");
        builder.define(MEMORY_BLOCK_POSITIONS, "");
        builder.define(MEMORY_CHEST_POSITIONS, "");
        builder.define(MEMORY_EPISODIC_POSITIONS, "");
        builder.define(MEMORY_SEMANTIC_SUMMARY, "No semantic memory");
        builder.define(PLAYTEST_STATE, "NONE");
        builder.define(PLAYTEST_INFO, "");
        builder.define(TASK_STATUS_SUMMARY, "No active tasks");
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide()) {
            ensureNavigationConfigured();
            applyMovementExhaustion();
            foodData.tick(this);
            actionExecutor.tick();
            updateTaskStatusSummary();
            pickupNearbyItems();
            tickCounter++;
        }

        lastTickX = this.getX();
        lastTickY = this.getY();
        lastTickZ = this.getZ();
    }

    public void setSteveName(String name) {
        this.steveName = name;
        this.entityData.set(STEVE_NAME, name);
        this.setCustomName(Component.literal(name));
    }

    public String getSteveName() {
        return this.steveName;
    }

    public SteveMemory getMemory() {
        return this.memory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    public StevePersona getPersona() {
        return StevePersonaProfiles.forSteveName(getSteveName());
    }

    public int getVisibleBlocksCount() {
        return memory.getVisibleBlocks().size();
    }

    public int getVisibleScanAge() {
        if (lastVisibleScanTick < 0) {
            return -1;
        }
        return tickCounter - lastVisibleScanTick;
    }

    public void forceVisibleScan() {
        if (this.level() instanceof ServerLevel serverLevel) {
            updateVisibleBlocks(serverLevel, SteveRuntimeSettings.getVisibleScanRadius());
        }
    }

    public void forceVisibleScan(int radius) {
        if (this.level() instanceof ServerLevel serverLevel) {
            int safeRadius = Math.max(1, Math.min(radius, 48));
            updateVisibleBlocks(serverLevel, safeRadius);
        }
    }

    public ItemStack addToInventory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slot = inventory.get(i);
            if (slot.isEmpty()) {
                inventory.set(i, remaining.copy());
                updateInventorySummary();
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(slot, remaining)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space <= 0) {
                    continue;
                }
                int toMove = Math.min(space, remaining.getCount());
                slot.grow(toMove);
                remaining.shrink(toMove);
                if (remaining.isEmpty()) {
                    updateInventorySummary();
                    return ItemStack.EMPTY;
                }
            }
        }
        updateInventorySummary();
        return remaining;
    }

    public boolean hasItem(Item item, int count) {
        if (item == null || count <= 0) {
            return false;
        }
        int total = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
                if (total >= count) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getItemCount(Item item) {
        if (item == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public void rememberOwnedStation(String blockId, BlockPos pos) {
        if (blockId == null || blockId.isBlank() || pos == null) {
            return;
        }
        String normalized = normalizeBlockId(blockId);
        ownedStations.computeIfAbsent(normalized, id -> new HashSet<>()).add(pos.immutable());
    }

    public void forgetOwnedStation(String blockId, BlockPos pos) {
        if (blockId == null || blockId.isBlank() || pos == null) {
            return;
        }
        String normalized = normalizeBlockId(blockId);
        Set<BlockPos> positions = ownedStations.get(normalized);
        if (positions == null) {
            return;
        }
        positions.remove(pos);
        if (positions.isEmpty()) {
            ownedStations.remove(normalized);
        }
    }

    public boolean isOwnedStation(String blockId, BlockPos pos) {
        if (blockId == null || blockId.isBlank() || pos == null) {
            return false;
        }
        String normalized = normalizeBlockId(blockId);
        Set<BlockPos> positions = ownedStations.get(normalized);
        return positions != null && positions.contains(pos);
    }

    @Nullable
    public BlockPos findNearestOwnedStation(String blockId, BlockPos origin, int maxDistance) {
        if (blockId == null || blockId.isBlank() || origin == null) {
            return null;
        }
        String normalized = normalizeBlockId(blockId);
        Set<BlockPos> positions = ownedStations.get(normalized);
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        Block stationBlock = parseBlock(normalized);
        long maxDistSqr = maxDistance <= 0 ? Long.MAX_VALUE : (long) maxDistance * maxDistance;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        List<BlockPos> stale = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }
            if (origin.distSqr(pos) > maxDistSqr) {
                continue;
            }
            if (stationBlock != null && this.level().getBlockState(pos).getBlock() != stationBlock) {
                stale.add(pos);
                continue;
            }
            double dist = origin.distSqr(pos);
            if (best == null || dist < bestDist) {
                best = pos.immutable();
                bestDist = dist;
            }
        }
        if (!stale.isEmpty()) {
            positions.removeAll(stale);
            if (positions.isEmpty()) {
                ownedStations.remove(normalized);
            }
        }
        return best;
    }

    public Map<String, Integer> getInventoryItemCountsById() {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : inventory) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.put(id, counts.getOrDefault(id, 0) + stack.getCount());
        }
        return counts;
    }

    public ItemStack findToolInInventory(com.steve.ai.mining.ToolCapabilityMap.ToolType toolType) {
        if (toolType == null || toolType == com.steve.ai.mining.ToolCapabilityMap.ToolType.NONE) {
            return ItemStack.EMPTY;
        }
        for (ItemStack stack : inventory) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (com.steve.ai.mining.ToolCapabilityMap.ToolType.matches(toolType, stack)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    public ItemStack findToolForRequirement(ToolRequirement requirement) {
        if (requirement == null) {
            return ItemStack.EMPTY;
        }
        ToolType required = requirement.required();
        ToolType preferred = requirement.preferred();

        if (required == ToolType.PICKAXE) {
            ToolTier minTier = requirement.minTier() == null ? ToolTier.NONE : requirement.minTier();
            ItemStack best = ItemStack.EMPTY;
            ToolTier bestTier = ToolTier.NONE;
            for (ItemStack stack : inventory) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (!ToolType.matches(ToolType.PICKAXE, stack)) {
                    continue;
                }
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                ToolTier tier = ToolTier.detectPickaxeTier(itemId);
                if (!tier.atLeast(minTier)) {
                    continue;
                }
                if (best.isEmpty() || tier.rank() > bestTier.rank()) {
                    best = stack.copy();
                    bestTier = tier;
                }
            }
            return best;
        }

        if (preferred != ToolType.NONE) {
            ItemStack preferredTool = findToolInInventory(preferred);
            if (!preferredTool.isEmpty()) {
                return preferredTool;
            }
        }
        if (required != ToolType.NONE) {
            return findToolInInventory(required);
        }
        return ItemStack.EMPTY;
    }

    public boolean hasPickaxeAtLeast(ToolTier minimumTier) {
        ToolTier min = minimumTier == null ? ToolTier.NONE : minimumTier;
        for (ItemStack stack : inventory) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!ToolType.matches(ToolType.PICKAXE, stack)) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            ToolTier tier = ToolTier.detectPickaxeTier(itemId);
            if (tier.atLeast(min)) {
                return true;
            }
        }
        return false;
    }

    public boolean canMineBlockNow(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        ToolRequirement requirement = ToolCapabilityMap.getRequirement(blockId);
        ToolType required = requirement.required();
        ToolType preferred = requirement.preferred();

        if (required == ToolType.NONE && preferred == ToolType.NONE) {
            return true;
        }
        if (required == ToolType.PICKAXE) {
            return hasPickaxeAtLeast(requirement.minTier());
        }
        if (required != ToolType.NONE && !findToolInInventory(required).isEmpty()) {
            return true;
        }
        if (preferred != ToolType.NONE && !findToolInInventory(preferred).isEmpty()) {
            return true;
        }
        return required == ToolType.NONE;
    }

    public boolean tryAutoEatIfNeeded(boolean safeToEat) {
        if (!safeToEat || !foodData.needsFood() || foodData.getFoodLevel() > AUTO_EAT_THRESHOLD) {
            return false;
        }
        return consumeBestFood(foodData.getFoodLevel() <= EMERGENCY_EAT_THRESHOLD);
    }

    public boolean hasEdibleFood(boolean includeProtectedAndUnsafe) {
        for (ItemStack stack : inventory) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!FoodTargetResolver.isEdibleByPolicy(stack, includeProtectedAndUnsafe, includeProtectedAndUnsafe)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean consumeBestFood(boolean emergencyMode) {
        int bestIndex = -1;
        int bestNutrition = Integer.MIN_VALUE;
        float bestSaturation = Float.MIN_VALUE;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!FoodTargetResolver.isEdibleByPolicy(stack, emergencyMode, emergencyMode)) {
                continue;
            }
            if (FoodTargetResolver.nutrition(stack) > bestNutrition
                || (FoodTargetResolver.nutrition(stack) == bestNutrition
                && FoodTargetResolver.saturation(stack) > bestSaturation)) {
                bestIndex = i;
                bestNutrition = FoodTargetResolver.nutrition(stack);
                bestSaturation = FoodTargetResolver.saturation(stack);
            }
        }

        if (bestIndex < 0) {
            return false;
        }

        ItemStack chosen = inventory.get(bestIndex);
        FoodProperties food = chosen.get(DataComponents.FOOD);
        if (food == null) {
            return false;
        }

        foodData.eat(food);
        chosen.shrink(1);
        if (chosen.isEmpty()) {
            inventory.set(bestIndex, ItemStack.EMPTY);
        }
        updateInventorySummary();
        return true;
    }

    public boolean tryAcquireFoodFromKnownChests(int radius, boolean emergencyMode) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        scanNearbyChests(radius);
        List<SteveMemory.ChestMemoryEntry> known = new ArrayList<>(memory.getKnownChests());
        known.sort(Comparator.comparingDouble(entry -> this.blockPosition().distSqr(entry.position())));

        for (SteveMemory.ChestMemoryEntry entry : known) {
            if (entry.position().distSqr(this.blockPosition()) > (long) radius * radius) {
                continue;
            }
            int edibleEstimate = estimateEdibleCountInSnapshot(entry, emergencyMode);
            if (edibleEstimate <= 0) {
                continue;
            }
            long ageTicks = Math.max(0L, this.level().getGameTime() - entry.lastSeenTick());
            double distance = Math.sqrt(this.blockPosition().distSqr(entry.position()));
            SourceRiskManager.Urgency urgency = emergencyMode
                ? SourceRiskManager.Urgency.CRITICAL
                : SourceRiskManager.Urgency.HIGH;
            SourceRiskManager.Decision riskDecision = SourceRiskManager.evaluateChestCandidate(
                edibleEstimate,
                distance,
                ageTicks,
                Math.max(1, 8 - getFoodLevel()),
                radius,
                urgency
            );
            if (!riskDecision.accept()) {
                continue;
            }
            Container container = getContainerAt(serverLevel, entry.position());
            if (container == null) {
                continue;
            }
            if (this.blockPosition().distSqr(entry.position()) > 16.0) {
                this.getNavigation().moveTo(
                    entry.position().getX() + 0.5,
                    entry.position().getY(),
                    entry.position().getZ() + 0.5,
                    1.1
                );
                return false;
            }

            if (withdrawBestFoodFromContainer(container, emergencyMode)) {
                rememberContainerSnapshot(entry.position(), container);
                return true;
            }
            rememberContainerSnapshot(entry.position(), container);
        }
        return false;
    }

    public int getKnownChestItemCount(String itemId, int radius) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }
        String normalized = normalizeItemId(itemId);
        BlockPos origin = this.blockPosition();
        int total = 0;
        for (SteveMemory.ChestMemoryEntry entry : memory.getKnownChests()) {
            if (entry == null || entry.position() == null) {
                continue;
            }
            if (entry.position().distSqr(origin) > (long) radius * radius) {
                continue;
            }
            total += entry.items().getOrDefault(normalized, 0);
        }
        return total;
    }

    public ChestAcquireAttempt tryAcquireItemFromKnownChests(String itemId, int quantity, int radius) {
        return tryAcquireItemFromKnownChests(itemId, quantity, radius, SourceRiskManager.Urgency.NORMAL);
    }

    public ChestAcquireAttempt tryAcquireItemFromKnownChests(
        String itemId,
        int quantity,
        int radius,
        SourceRiskManager.Urgency urgency
    ) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return ChestAcquireAttempt.none();
        }
        if (itemId == null || itemId.isBlank() || quantity <= 0) {
            return ChestAcquireAttempt.none();
        }

        String normalizedItemId = normalizeItemId(itemId);
        int safeRadius = Math.max(1, radius);
        int remaining = quantity;
        int acquired = 0;
        boolean sawPotential = false;

        scanNearbyChests(safeRadius);
        List<SteveMemory.ChestMemoryEntry> known = new ArrayList<>(memory.getKnownChests());
        known.sort(Comparator.comparingDouble(entry -> this.blockPosition().distSqr(entry.position())));

        for (SteveMemory.ChestMemoryEntry entry : known) {
            if (entry.position().distSqr(this.blockPosition()) > (long) safeRadius * safeRadius) {
                continue;
            }
            int estimated = entry.items().getOrDefault(normalizedItemId, 0);
            if (estimated <= 0) {
                continue;
            }
            long ageTicks = Math.max(0L, this.level().getGameTime() - entry.lastSeenTick());
            double distance = Math.sqrt(this.blockPosition().distSqr(entry.position()));
            SourceRiskManager.Decision riskDecision = SourceRiskManager.evaluateChestCandidate(
                estimated,
                distance,
                ageTicks,
                remaining,
                safeRadius,
                urgency == null ? SourceRiskManager.Urgency.NORMAL : urgency
            );
            if (!riskDecision.accept()) {
                continue;
            }
            sawPotential = true;

            Container container = getContainerAt(serverLevel, entry.position());
            if (container == null) {
                continue;
            }
            if (this.blockPosition().distSqr(entry.position()) > 16.0) {
                this.getNavigation().moveTo(
                    entry.position().getX() + 0.5,
                    entry.position().getY(),
                    entry.position().getZ() + 0.5,
                    1.1
                );
                return new ChestAcquireAttempt(acquired, true, true);
            }

            int moved = withdrawItemFromContainer(container, normalizedItemId, remaining);
            if (moved > 0) {
                acquired += moved;
                remaining -= moved;
            }
            rememberContainerSnapshot(entry.position(), container);
            if (remaining <= 0) {
                return new ChestAcquireAttempt(acquired, false, true);
            }
        }

        return new ChestAcquireAttempt(acquired, false, sawPotential);
    }

    public void scanNearbyChests(int radius) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos origin = this.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (origin.distSqr(pos) > (long) radius * radius) {
                        continue;
                    }
                    Container container = getContainerAt(serverLevel, pos);
                    if (container != null) {
                        rememberContainerSnapshot(pos, container);
                    }
                }
            }
        }
    }

    private boolean withdrawBestFoodFromContainer(Container container, boolean emergencyMode) {
        int bestSlot = -1;
        int bestNutrition = Integer.MIN_VALUE;
        float bestSaturation = Float.MIN_VALUE;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!FoodTargetResolver.isEdibleByPolicy(stack, emergencyMode, emergencyMode)) {
                continue;
            }
            int nutrition = FoodTargetResolver.nutrition(stack);
            float saturation = FoodTargetResolver.saturation(stack);
            if (nutrition > bestNutrition || (nutrition == bestNutrition && saturation > bestSaturation)) {
                bestSlot = slot;
                bestNutrition = nutrition;
                bestSaturation = saturation;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack stack = container.getItem(bestSlot);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        int moveCount = Math.min(stack.getCount(), Math.max(1, 8));
        ItemStack moved = stack.copy();
        moved.setCount(moveCount);
        ItemStack remainder = addToInventory(moved);
        int inserted = moveCount - remainder.getCount();
        if (inserted <= 0) {
            return false;
        }
        stack.shrink(inserted);
        if (stack.isEmpty()) {
            container.setItem(bestSlot, ItemStack.EMPTY);
        }
        container.setChanged();
        return true;
    }

    private int withdrawItemFromContainer(Container container, String targetItemId, int desiredCount) {
        if (container == null || targetItemId == null || targetItemId.isBlank() || desiredCount <= 0) {
            return 0;
        }

        int remaining = desiredCount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!targetItemId.equals(itemId)) {
                continue;
            }

            int attempt = Math.min(stack.getCount(), remaining);
            ItemStack moved = stack.copy();
            moved.setCount(attempt);
            ItemStack remainder = addToInventory(moved);
            int inserted = attempt - remainder.getCount();
            if (inserted <= 0) {
                continue;
            }

            stack.shrink(inserted);
            if (stack.isEmpty()) {
                container.setItem(slot, ItemStack.EMPTY);
            }
            remaining -= inserted;
        }
        container.setChanged();
        return desiredCount - remaining;
    }

    private boolean containsEdibleInSnapshot(SteveMemory.ChestMemoryEntry entry, boolean emergencyMode) {
        return estimateEdibleCountInSnapshot(entry, emergencyMode) > 0;
    }

    private int estimateEdibleCountInSnapshot(SteveMemory.ChestMemoryEntry entry, boolean emergencyMode) {
        if (entry == null || entry.items().isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String itemId : entry.items().keySet()) {
            Identifier id = Identifier.tryParse(itemId);
            if (id == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item, 1);
            if (FoodTargetResolver.isEdibleByPolicy(stack, emergencyMode, emergencyMode)) {
                total += Math.max(0, entry.items().getOrDefault(itemId, 0));
            }
        }
        return total;
    }

    private void rememberContainerSnapshot(BlockPos pos, Container container) {
        if (container == null || pos == null) {
            return;
        }
        Map<String, Integer> contents = new HashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            contents.put(id, contents.getOrDefault(id, 0) + stack.getCount());
        }
        memory.rememberChestContents(pos, contents, this.level().getGameTime());
        updateMemoryDebugSummary();
    }

    private Container getContainerAt(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            return container;
        }
        return null;
    }

    private String normalizeItemId(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private String normalizeBlockId(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private Block parseBlock(String blockId) {
        Identifier id = Identifier.tryParse(normalizeBlockId(blockId));
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    public int getFoodLevel() {
        return foodData.getFoodLevel();
    }

    public void setFoodLevel(int foodLevel) {
        foodData.setFoodLevel(Math.max(0, Math.min(foodLevel, 20)));
    }

    public float getSaturationLevel() {
        return foodData.getSaturationLevel();
    }

    public void setSaturationLevel(float saturationLevel) {
        foodData.setSaturation(Math.max(0.0F, Math.min(saturationLevel, 20.0F)));
    }

    public boolean needsFood() {
        return foodData.needsFood();
    }

    public void addExhaustion(float exhaustion) {
        if (exhaustion > 0.0F) {
            foodData.addExhaustion(exhaustion);
        }
    }

    private void applyMovementExhaustion() {
        double dx = this.getX() - lastTickX;
        double dy = this.getY() - lastTickY;
        double dz = this.getZ() - lastTickZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= 0.001 && Math.abs(dy) <= 0.001) {
            return;
        }

        // Vanilla-inspired exhaustion sourcing with conservative defaults.
        if (this.isSprinting()) {
            addExhaustion((float) (horizontal * 0.1F));
        } else {
            addExhaustion((float) (horizontal * 0.01F));
        }
        if (dy > 0.4) {
            addExhaustion(this.isSprinting() ? 0.2F : 0.05F);
        }
    }

    public boolean consumeItem(Item item, int count) {
        if (!hasItem(item, count)) {
            return false;
        }
        int remaining = count;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int toRemove = Math.min(remaining, stack.getCount());
            stack.shrink(toRemove);
            remaining -= toRemove;
            if (stack.isEmpty()) {
                inventory.set(i, ItemStack.EMPTY);
            }
            if (remaining <= 0) {
                updateInventorySummary();
                return true;
            }
        }
        updateInventorySummary();
        return true;
    }

    public ItemStack extractItem(Item item, int count) {
        if (item == null || count <= 0) {
            return ItemStack.EMPTY;
        }
        int remaining = count;
        int removed = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int toRemove = Math.min(remaining, stack.getCount());
            stack.shrink(toRemove);
            remaining -= toRemove;
            removed += toRemove;
            if (stack.isEmpty()) {
                inventory.set(i, ItemStack.EMPTY);
            }
            if (remaining <= 0) {
                break;
            }
        }
        if (removed > 0) {
            updateInventorySummary();
            return new ItemStack(item, removed);
        }
        return ItemStack.EMPTY;
    }

    public String getInventorySummary() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ItemStack stack : inventory) {
            if (stack.isEmpty()) {
                continue;
            }
            String name = stack.getItem().getName(stack).getString();
            counts.put(name, counts.getOrDefault(name, 0) + stack.getCount());
        }
        if (counts.isEmpty()) {
            return "Inventory empty";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (var entry : counts.entrySet()) {
            if (shown >= 12) {
                sb.append(" ...");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
            shown++;
        }
        return sb.toString();
    }

    public String getInventorySummarySynced() {
        return this.entityData.get(INVENTORY_SUMMARY);
    }

    public String getMemoryBlocksSummarySynced() {
        return this.entityData.get(MEMORY_BLOCKS_SUMMARY);
    }

    public String getMemoryChestsSummarySynced() {
        return this.entityData.get(MEMORY_CHESTS_SUMMARY);
    }

    public String getMemoryBlockPositionsSynced() {
        return this.entityData.get(MEMORY_BLOCK_POSITIONS);
    }

    public String getMemoryChestPositionsSynced() {
        return this.entityData.get(MEMORY_CHEST_POSITIONS);
    }

    public String getMemoryEpisodicPositionsSynced() {
        return this.entityData.get(MEMORY_EPISODIC_POSITIONS);
    }

    public String getMemorySemanticSummarySynced() {
        return this.entityData.get(MEMORY_SEMANTIC_SUMMARY);
    }

    public void setPlaytestStatus(String state, String info) {
        if (this.level().isClientSide()) {
            return;
        }
        String safeState = state == null ? "NONE" : state.trim().toUpperCase(Locale.ROOT);
        if (safeState.isBlank()) {
            safeState = "NONE";
        }
        String safeInfo = info == null ? "" : info.trim();
        if (safeInfo.length() > 160) {
            safeInfo = safeInfo.substring(0, 157) + "...";
        }
        this.entityData.set(PLAYTEST_STATE, safeState);
        this.entityData.set(PLAYTEST_INFO, safeInfo);
    }

    public String getPlaytestStateSynced() {
        String state = this.entityData.get(PLAYTEST_STATE);
        return state == null || state.isBlank() ? "NONE" : state;
    }

    public String getPlaytestInfoSynced() {
        String info = this.entityData.get(PLAYTEST_INFO);
        return info == null ? "" : info;
    }

    public String getTaskStatusSummarySynced() {
        String raw = this.entityData.get(TASK_STATUS_SUMMARY);
        return raw == null || raw.isBlank() ? "No active tasks" : raw;
    }

    private void updateInventorySummary() {
        if (this.level().isClientSide()) {
            return;
        }
        this.entityData.set(INVENTORY_SUMMARY, getInventorySummary());
    }

    private void updateTaskStatusSummary() {
        if (this.level().isClientSide()) {
            return;
        }
        String summary = actionExecutor != null ? actionExecutor.getTaskStatusSummaryForUi() : "No active tasks";
        if (summary == null || summary.isBlank()) {
            summary = "No active tasks";
        }
        if (summary.length() > 1200) {
            summary = summary.substring(0, 1197) + "...";
        }
        this.entityData.set(TASK_STATUS_SUMMARY, summary);
    }

    private void updateMemoryDebugSummary() {
        if (this.level().isClientSide()) {
            return;
        }

        Map<String, Integer> blockCounts = new HashMap<>();
        for (VisibleBlockEntry entry : memory.getVisibleBlocks()) {
            blockCounts.merge(entry.blockId(), 1, Integer::sum);
        }

        if (blockCounts.isEmpty()) {
            this.entityData.set(MEMORY_BLOCKS_SUMMARY, "No block memory");
            this.entityData.set(MEMORY_BLOCK_POSITIONS, "");
            this.entityData.set(MEMORY_SEMANTIC_SUMMARY, "No semantic memory");
        } else {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(blockCounts.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder blocksSummary = new StringBuilder();
            int maxItems = Math.min(8, sorted.size());
            for (int i = 0; i < maxItems; i++) {
                if (i > 0) {
                    blocksSummary.append(", ");
                }
                Map.Entry<String, Integer> item = sorted.get(i);
                blocksSummary.append(item.getKey()).append(" x").append(item.getValue());
            }
            if (sorted.size() > maxItems) {
                blocksSummary.append(" ...");
            }
            this.entityData.set(MEMORY_BLOCKS_SUMMARY, blocksSummary.toString());

            StringBuilder positions = new StringBuilder();
            int maxPositions = Math.min(SteveRuntimeSettings.getSyncedWorkingPositions(), memory.getVisibleBlocks().size());
            for (int i = 0; i < maxPositions; i++) {
                BlockPos pos = memory.getVisibleBlocks().get(i).position();
                if (i > 0) {
                    positions.append(';');
                }
                positions.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
            }
            this.entityData.set(MEMORY_BLOCK_POSITIONS, positions.toString());
            this.entityData.set(MEMORY_SEMANTIC_SUMMARY, memory.getSemanticSummary(6));
        }

        List<BlockPos> episodicPositions = memory.getEpisodicPositions(SteveRuntimeSettings.getSyncedEpisodicPositions());
        if (episodicPositions.isEmpty()) {
            this.entityData.set(MEMORY_EPISODIC_POSITIONS, "");
        } else {
            StringBuilder episodic = new StringBuilder();
            for (int i = 0; i < episodicPositions.size(); i++) {
                BlockPos pos = episodicPositions.get(i);
                if (i > 0) {
                    episodic.append(';');
                }
                episodic.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
            }
            this.entityData.set(MEMORY_EPISODIC_POSITIONS, episodic.toString());
        }

        List<SteveMemory.ChestMemoryEntry> knownChests = memory.getKnownChests();
        if (knownChests.isEmpty()) {
            this.entityData.set(MEMORY_CHESTS_SUMMARY, "No chest memory");
            this.entityData.set(MEMORY_CHEST_POSITIONS, "");
            return;
        }

        int totalStacks = 0;
        int totalItems = 0;
        for (SteveMemory.ChestMemoryEntry chest : knownChests) {
            totalStacks += chest.items().size();
            for (Integer count : chest.items().values()) {
                if (count != null && count > 0) {
                    totalItems += count;
                }
            }
        }
        this.entityData.set(
            MEMORY_CHESTS_SUMMARY,
            "chests=" + knownChests.size() + ", stacks=" + totalStacks + ", items=" + totalItems
        );

        StringBuilder chestPositions = new StringBuilder();
        int maxChestPositions = Math.min(64, knownChests.size());
        for (int i = 0; i < maxChestPositions; i++) {
            BlockPos pos = knownChests.get(i).position();
            if (i > 0) {
                chestPositions.append(';');
            }
            chestPositions.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        this.entityData.set(MEMORY_CHEST_POSITIONS, chestPositions.toString());
    }

    private void pickupNearbyItems() {
        if (pickupCooldown > 0) {
            pickupCooldown--;
            return;
        }
        pickupCooldown = 10;

        var items = this.level().getEntitiesOfClass(
            ItemEntity.class,
            this.getBoundingBox().inflate(2.5)
        );

        for (ItemEntity itemEntity : items) {
            if (itemEntity.isRemoved() || !itemEntity.isAlive()) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = addToInventory(stack);
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(remaining);
            }
        }
    }

    public void setDebugStatus(String status) {
        if (this.level().isClientSide()) return;
        if (status == null) {
            status = "";
        }
        if (status.length() > 120) {
            status = status.substring(0, 117) + "...";
        }
        this.entityData.set(DEBUG_STATUS, status);
    }

    public String getDebugStatus() {
        return this.entityData.get(DEBUG_STATUS);
    }

    public void setDebugTargetBlock(@Nullable BlockPos pos) {
        if (this.level().isClientSide()) {
            return;
        }
        if (pos == null) {
            this.entityData.set(DEBUG_TARGET_BLOCK, "");
            return;
        }
        this.entityData.set(DEBUG_TARGET_BLOCK, pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    @Nullable
    public BlockPos getDebugTargetBlock() {
        String raw = this.entityData.get(DEBUG_TARGET_BLOCK);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new BlockPos(x, y, z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        output.putString("SteveName", this.steveName);
        ValueOutput memoryOutput = output.child("Memory");
        this.memory.saveToNBT(memoryOutput);
        ValueOutput foodOutput = output.child("FoodData");
        this.foodData.addAdditionalSaveData(foodOutput);

        ValueOutput.TypedOutputList<ItemStack> invList = output.list("Inventory", ItemStack.CODEC);
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                invList.add(stack);
            }
        }

        ValueOutput.TypedOutputList<String> stationsList = output.list("OwnedStations", Codec.STRING);
        for (Map.Entry<String, Set<BlockPos>> entry : ownedStations.entrySet()) {
            String blockId = entry.getKey();
            for (BlockPos pos : entry.getValue()) {
                if (pos == null) {
                    continue;
                }
                stationsList.add(blockId + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
        }
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        this.setSteveName(input.getStringOr("SteveName", this.steveName));
        this.memory.loadFromNBT(input.childOrEmpty("Memory"));
        this.foodData.readAdditionalSaveData(input.childOrEmpty("FoodData"));

        input.list("Inventory", ItemStack.CODEC).ifPresent(list -> {
            for (int i = 0; i < inventory.size(); i++) {
                inventory.set(i, ItemStack.EMPTY);
            }
            int index = 0;
            for (ItemStack stack : list.stream().toList()) {
                if (index >= inventory.size()) {
                    break;
                }
                inventory.set(index, stack);
                index++;
            }
            updateInventorySummary();
        });

        ownedStations.clear();
        input.list("OwnedStations", Codec.STRING).ifPresent(list -> {
            for (String row : list.stream().toList()) {
                if (row == null || row.isBlank()) {
                    continue;
                }
                int sep = row.indexOf('@');
                if (sep <= 0 || sep >= row.length() - 1) {
                    continue;
                }
                String blockId = normalizeBlockId(row.substring(0, sep));
                String[] xyz = row.substring(sep + 1).split(",");
                if (xyz.length != 3) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(xyz[0].trim());
                    int y = Integer.parseInt(xyz[1].trim());
                    int z = Integer.parseInt(xyz[2].trim());
                    rememberOwnedStation(blockId, new BlockPos(x, y, z));
                } catch (NumberFormatException ignored) {
                }
            }
        });
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       EntitySpawnReason spawnType, @Nullable SpawnGroupData spawnData) {
        spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData);
        return spawnData;
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide()) return;
        
        Component chatComponent = Component.literal("<" + this.steveName + "> " + message);
        this.level().players().forEach(player -> player.displayClientMessage(chatComponent, false));
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.world.damagesource.DamageSource source,
                                       boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
    }

    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    private void ensureNavigationConfigured() {
        if (navigationConfigured) {
            return;
        }
        if (this.getNavigation() != null) {
            this.getNavigation().setCanFloat(true);
            navigationConfigured = true;
        }
    }

    private void updateVisibleBlocks(ServerLevel serverLevel, int radius) {
        BlockPos origin = this.blockPosition();
        List<VisibleBlockEntry> entries = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = serverLevel.getBlockState(pos);
                    Block block = state.getBlock();
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                        continue;
                    }
                    String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
                    float distance = (float) Math.sqrt(origin.distSqr(pos));
                    entries.add(new VisibleBlockEntry(blockId, pos.immutable(), distance, tickCounter));
                }
            }
        }

        entries.sort(Comparator.comparing(VisibleBlockEntry::distance));
        int maxEntries = Math.max(32, SteveRuntimeSettings.getVisibleMaxEntries());
        if (entries.size() > maxEntries) {
            entries = new ArrayList<>(entries.subList(0, maxEntries));
        }

        memory.setVisibleBlocks(entries);
        lastVisibleScanTick = tickCounter;
        updateMemoryDebugSummary();
    }

    /**
     * Set invulnerability for building (immune to ALL damage: fire, lava, suffocation, fall, etc.)
     */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable); // Minecraft's built-in invulnerability
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide()) {
            double motionY = this.getDeltaMovement().y;
            
            if (this.getNavigation().isInProgress()) {
                super.travel(travelVector);
                
                // But add ability to move vertically freely
                if (Math.abs(motionY) < 0.1) {
                    // Small upward force to prevent falling
                    this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
                }
            } else {
                super.travel(travelVector);
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(double distance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource source) {
        // No fall damage when flying
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    public record ChestAcquireAttempt(int acquiredCount, boolean moving, boolean hadKnownSources) {
        public static ChestAcquireAttempt none() {
            return new ChestAcquireAttempt(0, false, false);
        }
    }
}
