package com.steve.ai.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Vanilla-mirrored food system adapted from FoodData for non-player entities.
 */
public class SteveFoodData {
    private int foodLevel = 20;
    private float saturationLevel = 5.0F;
    private float exhaustionLevel;
    private int tickTimer;

    private void add(int nutrition, float saturation) {
        this.foodLevel = net.minecraft.util.Mth.clamp(nutrition + this.foodLevel, 0, 20);
        this.saturationLevel = net.minecraft.util.Mth.clamp(saturation + this.saturationLevel, 0.0F, this.foodLevel);
    }

    public void eat(net.minecraft.world.food.FoodProperties foodProperties) {
        this.add(foodProperties.nutrition(), foodProperties.saturation());
    }

    public void tick(SteveEntity steve) {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Difficulty difficulty = serverLevel.getDifficulty();
        if (this.exhaustionLevel > 4.0F) {
            this.exhaustionLevel -= 4.0F;
            if (this.saturationLevel > 0.0F) {
                this.saturationLevel = Math.max(this.saturationLevel - 1.0F, 0.0F);
            } else if (difficulty != Difficulty.PEACEFUL) {
                this.foodLevel = Math.max(this.foodLevel - 1, 0);
            }
        }

        boolean naturalRegen = serverLevel.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        boolean hurt = steve.getHealth() < steve.getMaxHealth();
        if (naturalRegen && this.saturationLevel > 0.0F && hurt && this.foodLevel >= 20) {
            this.tickTimer++;
            if (this.tickTimer >= 10) {
                float healAmount = Math.min(this.saturationLevel, 6.0F) / 6.0F;
                steve.heal(healAmount);
                this.addExhaustion(Math.min(this.saturationLevel, 6.0F));
                this.tickTimer = 0;
            }
        } else if (naturalRegen && this.foodLevel >= 18 && hurt) {
            this.tickTimer++;
            if (this.tickTimer >= 80) {
                steve.heal(1.0F);
                this.addExhaustion(6.0F);
                this.tickTimer = 0;
            }
        } else if (this.foodLevel <= 0) {
            this.tickTimer++;
            if (this.tickTimer >= 80) {
                if (steve.getHealth() > 10.0F
                    || difficulty == Difficulty.HARD
                    || steve.getHealth() > 1.0F && difficulty == Difficulty.NORMAL) {
                    steve.hurtServer(serverLevel, steve.damageSources().starve(), 1.0F);
                }
                this.tickTimer = 0;
            }
        } else {
            this.tickTimer = 0;
        }
    }

    public void readAdditionalSaveData(ValueInput input) {
        this.foodLevel = input.getIntOr("foodLevel", 20);
        this.tickTimer = input.getIntOr("foodTickTimer", 0);
        this.saturationLevel = input.getFloatOr("foodSaturationLevel", 5.0F);
        this.exhaustionLevel = input.getFloatOr("foodExhaustionLevel", 0.0F);
    }

    public void addAdditionalSaveData(ValueOutput output) {
        output.putInt("foodLevel", this.foodLevel);
        output.putInt("foodTickTimer", this.tickTimer);
        output.putFloat("foodSaturationLevel", this.saturationLevel);
        output.putFloat("foodExhaustionLevel", this.exhaustionLevel);
    }

    public int getFoodLevel() {
        return foodLevel;
    }

    public float getSaturationLevel() {
        return saturationLevel;
    }

    public boolean needsFood() {
        return foodLevel < 20;
    }

    public void setFoodLevel(int foodLevel) {
        this.foodLevel = foodLevel;
    }

    public void setSaturation(float saturationLevel) {
        this.saturationLevel = saturationLevel;
    }

    public void addExhaustion(float exhaustion) {
        this.exhaustionLevel = Math.min(this.exhaustionLevel + exhaustion, 40.0F);
    }
}
