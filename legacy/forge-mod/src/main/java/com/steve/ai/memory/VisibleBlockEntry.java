package com.steve.ai.memory;

import net.minecraft.core.BlockPos;

public record VisibleBlockEntry(String blockId, BlockPos position, float distance, int lastSeenTick) {
}
