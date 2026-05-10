package com.steve.ai.memory;

import net.minecraft.core.BlockPos;

public record VisibleBlock(
    String blockId,
    BlockPos position,
    double distance,
    long lastSeenTick
) {}
