package com.steve.ai.memory;

import java.util.List;

public class PerceptionCache {
    private List<VisibleBlock> visibleBlocks = List.of();

    public void updateVisibleBlocks(List<VisibleBlock> snapshot) {
        this.visibleBlocks = List.copyOf(snapshot);
    }

    public List<VisibleBlock> getVisibleBlocks() {
        return visibleBlocks;
    }
}
