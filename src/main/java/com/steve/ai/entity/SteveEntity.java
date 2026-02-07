package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.VisibleBlockEntry;
import com.steve.ai.network.SteveNetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DEBUG_STATUS =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private int tickCounter = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;
    private static final int VISIBLE_BLOCK_SCAN_RADIUS = 16;
    private static final int VISIBLE_BLOCK_MAX_ENTRIES = 200;
    private static final int VISIBLE_BLOCK_SCAN_INTERVAL = 20;

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
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
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;

        if (tickCounter % 5 == 0) {
            long timestamp = this.level().getGameTime();
            memory.recordViewSample(this.getYRot(), this.getXRot(), timestamp);
        }
        
        if (!this.level().isClientSide()) {
            actionExecutor.tick();
            tickCounter++;
            if (tickCounter % VISIBLE_BLOCK_SCAN_INTERVAL == 0 && this.level() instanceof ServerLevel serverLevel) {
                updateVisibleBlocks(serverLevel);
            }
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
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        this.setSteveName(input.getStringOr("SteveName", this.steveName));
        this.memory.loadFromNBT(input.childOrEmpty("Memory"));
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

    private void updateVisibleBlocks(ServerLevel serverLevel) {
        BlockPos origin = this.blockPosition();
        List<VisibleBlockEntry> entries = new ArrayList<>();

        for (int x = -VISIBLE_BLOCK_SCAN_RADIUS; x <= VISIBLE_BLOCK_SCAN_RADIUS; x++) {
            for (int y = -VISIBLE_BLOCK_SCAN_RADIUS; y <= VISIBLE_BLOCK_SCAN_RADIUS; y++) {
                for (int z = -VISIBLE_BLOCK_SCAN_RADIUS; z <= VISIBLE_BLOCK_SCAN_RADIUS; z++) {
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
        if (entries.size() > VISIBLE_BLOCK_MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, VISIBLE_BLOCK_MAX_ENTRIES));
        }

        memory.setVisibleBlocks(entries);
        SteveNetworkHandler.sendVisibleBlocks(serverLevel, this.getId(), entries);
    }
}
