package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private int tickCounter = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;

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
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide()) {
            actionExecutor.tick();
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
}
