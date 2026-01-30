package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class BlockMemoryScanner {
    private static final int RANGE = 12;
    private static final float YAW_LIMIT = 70.0f;
    private static final float PITCH_LIMIT = 30.0f;
    private static final float YAW_STEP = 8.0f;
    private static final float PITCH_STEP = 6.0f;
    private static final int RAYS_PER_TICK = 3;

    private final SteveEntity steve;
    private float yawOffset = -YAW_LIMIT;
    private float pitchOffset = -PITCH_LIMIT;

    public BlockMemoryScanner(SteveEntity steve) {
        this.steve = steve;
    }

    public void tick() {
        Vec3 eyePos = steve.getEyePosition(1.0F);
        float baseYaw = steve.getYRot();
        float basePitch = steve.getXRot();

        for (int i = 0; i < RAYS_PER_TICK; i++) {
            Vec3 dir = Vec3.directionFromRotation(basePitch + pitchOffset, baseYaw + yawOffset);
            recordFirstSolidAlongRay(eyePos, dir, RANGE);
            advanceSweep();
        }
    }

    private void advanceSweep() {
        yawOffset += YAW_STEP;
        if (yawOffset > YAW_LIMIT) {
            yawOffset = -YAW_LIMIT;
            pitchOffset += PITCH_STEP;
            if (pitchOffset > PITCH_LIMIT) {
                pitchOffset = -PITCH_LIMIT;
            }
        }
    }

    private void recordFirstSolidAlongRay(Vec3 start, Vec3 dir, int range) {
        for (int step = 1; step <= range; step++) {
            Vec3 sample = start.add(dir.scale(step));
            BlockPos pos = new BlockPos(Mth.floor(sample.x), Mth.floor(sample.y), Mth.floor(sample.z));
            BlockState state = steve.level().getBlockState(pos);
            if (state.isAir() || state.getBlock() == Blocks.BEDROCK) {
                continue;
            }
            steve.getBlockMemory().record(pos, state);
            return;
        }
    }
}
