package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.BlockMemory;
import com.steve.ai.memory.BlockMemoryScanner;
import com.steve.ai.memory.SteveMemory;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
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
import org.jetbrains.annotations.Nullable;

public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DEBUG_STATUS =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> INVENTORY_SUMMARY =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private boolean isFlying = false;
    private final BlockMemoryScanner blockMemoryScanner;
    private boolean isInvulnerable = false;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
    private int pickupCooldown = 0;
    private final BlockMemory blockMemory;

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.blockMemory = new BlockMemory(this, 2400);
        this.blockMemoryScanner = new BlockMemoryScanner(this);
        this.setCustomNameVisible(true);
        
        this.isInvulnerable = true;
        this.setInvulnerable(true);
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
        builder.define(INVENTORY_SUMMARY, "Inventory empty");
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide()) {
            actionExecutor.tick();
            pickupNearbyItems();
            blockMemoryScanner.tick();
        }
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

    public BlockMemory getBlockMemory() {
        return this.blockMemory;
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

    private void updateInventorySummary() {
        if (this.level().isClientSide()) {
            return;
        }
        this.entityData.set(INVENTORY_SUMMARY, getInventorySummary());
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

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        output.putString("SteveName", this.steveName);
        ValueOutput memoryOutput = output.child("Memory");
        this.memory.saveToNBT(memoryOutput);

        ValueOutput.TypedOutputList<ItemStack> invList = output.list("Inventory", ItemStack.CODEC);
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                invList.add(stack);
            }
        }
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        this.setSteveName(input.getStringOr("SteveName", this.steveName));
        this.memory.loadFromNBT(input.childOrEmpty("Memory"));

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
}
