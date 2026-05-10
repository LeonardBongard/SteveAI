package com.steve.ai.execution;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DefaultSafetyEvaluatorManager implements SafetyEvaluatorManager {
    private static final int THREAT_SCAN_RADIUS = 12;
    private static final int IMMEDIATE_THREAT_RADIUS = 5;
    private static final int RETREAT_DISTANCE = 8;
    private static final int CRITICAL_SCORE = 30;
    private static final int DANGER_SCORE = 55;
    private static final int CAUTION_SCORE = 80;
    private static final int PANIC_LOW_MIN = 20;
    private static final int PANIC_MEDIUM_MIN = 40;
    private static final int PANIC_HIGH_MIN = 65;
    private static final int PANIC_CRITICAL_MIN = 85;

    @Override
    public SafetySnapshot evaluate(SteveEntity steve, ActionContext context) {
        if (steve == null || steve.level().isClientSide()) {
            return SafetySnapshot.safe();
        }

        float health = steve.getHealth();
        float maxHealth = Math.max(1.0F, steve.getMaxHealth());
        double healthRatio = health / maxHealth;

        List<Mob> threats = findNearbyThreats(steve);
        Mob nearestThreat = threats.stream()
            .min(Comparator.comparingDouble(steve::distanceToSqr))
            .orElse(null);

        int immediateThreats = 0;
        for (Mob threat : threats) {
            if (steve.distanceToSqr(threat) <= IMMEDIATE_THREAT_RADIUS * IMMEDIATE_THREAT_RADIUS) {
                immediateThreats++;
            }
        }

        int score = 100;
        List<String> reasons = new ArrayList<>();

        int foodLevel = steve.getFoodLevel();
        if (foodLevel <= 4) {
            score -= 35;
            reasons.add("critical-hunger");
        } else if (foodLevel <= 8) {
            score -= 20;
            reasons.add("low-hunger");
        } else if (foodLevel <= 12) {
            score -= 8;
            reasons.add("reduced-hunger");
        }

        if (healthRatio < 0.25) {
            score -= 55;
            reasons.add("critical-health");
        } else if (healthRatio < 0.45) {
            score -= 35;
            reasons.add("low-health");
        }

        if (!threats.isEmpty()) {
            score -= Math.min(35, threats.size() * 8);
            reasons.add("nearby-hostiles:" + threats.size());
        }
        if (immediateThreats > 0) {
            score -= Math.min(25, immediateThreats * 12);
            reasons.add("immediate-hostiles:" + immediateThreats);
        }

        boolean standingInHazard = isStandingInHazard(steve);
        if (standingInHazard) {
            score -= 30;
            reasons.add("terrain-hazard");
        }

        score = Math.max(0, Math.min(100, score));
        SafetyState state = classify(score);
        int panicScore = computePanicScore(healthRatio, foodLevel, threats.size(), immediateThreats, standingInHazard, state);
        PanicLevel panicLevel = classifyPanicLevel(panicScore);
        SafetyDecision decision = shouldRetreatForStateOrPanic(state, panicLevel)
            ? SafetyDecision.RETREAT
            : SafetyDecision.CONTINUE;

        BlockPos retreatTarget = decision == SafetyDecision.RETREAT
            ? pickRetreatTarget(steve, nearestThreat)
            : null;

        return new SafetySnapshot(state, score, decision, panicLevel, panicScore, reasons, retreatTarget);
    }

    @Override
    public SafetyDecision recommend(SafetySnapshot snapshot, String currentActionType) {
        if (snapshot == null) {
            return SafetyDecision.CONTINUE;
        }
        PanicLevel panicLevel = snapshot.panicLevel();
        String actionType = currentActionType == null ? "unknown" : currentActionType.toLowerCase();

        if (panicLevel == PanicLevel.CRITICAL || panicLevel == PanicLevel.HIGH) {
            return SafetyDecision.RETREAT;
        }
        if (panicLevel == PanicLevel.MEDIUM && "attack".equals(actionType)) {
            return SafetyDecision.RETREAT;
        }
        return snapshot.recommendedDecision();
    }

    private List<Mob> findNearbyThreats(SteveEntity steve) {
        return steve.level().getEntitiesOfClass(
            Mob.class,
            steve.getBoundingBox().inflate(THREAT_SCAN_RADIUS),
            mob -> mob instanceof Enemy
                && mob.isAlive()
                && !mob.isRemoved()
                && steve.distanceToSqr(mob) <= THREAT_SCAN_RADIUS * THREAT_SCAN_RADIUS
        );
    }

    private SafetyState classify(int score) {
        if (score <= CRITICAL_SCORE) {
            return SafetyState.CRITICAL;
        }
        if (score <= DANGER_SCORE) {
            return SafetyState.DANGER;
        }
        if (score <= CAUTION_SCORE) {
            return SafetyState.CAUTION;
        }
        return SafetyState.SAFE;
    }

    private boolean shouldRetreatForStateOrPanic(SafetyState state, PanicLevel panicLevel) {
        if (state == SafetyState.DANGER || state == SafetyState.CRITICAL) {
            return true;
        }
        return panicLevel == PanicLevel.HIGH || panicLevel == PanicLevel.CRITICAL;
    }

    private int computePanicScore(
        double healthRatio,
        int foodLevel,
        int threatCount,
        int immediateThreats,
        boolean standingInHazard,
        SafetyState state
    ) {
        int panic = 0;

        if (healthRatio < 0.25) {
            panic += 45;
        } else if (healthRatio < 0.45) {
            panic += 28;
        }

        if (foodLevel <= 4) {
            panic += 24;
        } else if (foodLevel <= 8) {
            panic += 14;
        }

        panic += Math.min(24, threatCount * 6);
        panic += Math.min(40, immediateThreats * 18);

        if (standingInHazard) {
            panic += 30;
        }

        if (state == SafetyState.DANGER) {
            panic += 8;
        } else if (state == SafetyState.CRITICAL) {
            panic += 15;
        }

        return Math.max(0, Math.min(100, panic));
    }

    private PanicLevel classifyPanicLevel(int panicScore) {
        if (panicScore >= PANIC_CRITICAL_MIN) {
            return PanicLevel.CRITICAL;
        }
        if (panicScore >= PANIC_HIGH_MIN) {
            return PanicLevel.HIGH;
        }
        if (panicScore >= PANIC_MEDIUM_MIN) {
            return PanicLevel.MEDIUM;
        }
        if (panicScore >= PANIC_LOW_MIN) {
            return PanicLevel.LOW;
        }
        return PanicLevel.NONE;
    }

    private boolean isStandingInHazard(SteveEntity steve) {
        if (steve.isOnFire()) {
            return true;
        }

        BlockPos feet = steve.blockPosition();
        BlockState feetState = steve.level().getBlockState(feet);
        BlockState belowState = steve.level().getBlockState(feet.below());

        // Keep normal river traversal safe by default: don't flag water as hazard.
        if (!feetState.getFluidState().isEmpty() || !belowState.getFluidState().isEmpty()) {
            return false;
        }

        String feetId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(feetState.getBlock()).toString();
        String belowId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(belowState.getBlock()).toString();
        return feetId.contains("lava") || belowId.contains("lava") || feetId.contains("fire") || belowId.contains("fire");
    }

    private BlockPos pickRetreatTarget(SteveEntity steve, LivingEntity nearestThreat) {
        BlockPos origin = steve.blockPosition();
        if (nearestThreat == null) {
            return origin;
        }

        double dx = steve.getX() - nearestThreat.getX();
        double dz = steve.getZ() - nearestThreat.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        dx /= length;
        dz /= length;

        int targetX = (int) Math.round(steve.getX() + dx * RETREAT_DISTANCE);
        int targetZ = (int) Math.round(steve.getZ() + dz * RETREAT_DISTANCE);
        int targetY = origin.getY();

        BlockPos raw = new BlockPos(targetX, targetY, targetZ);
        if (steve.level() instanceof ServerLevel serverLevel) {
            BlockPos heightAdjusted = serverLevel.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, raw);
            return heightAdjusted == null ? raw : heightAdjusted;
        }
        return raw;
    }
}
