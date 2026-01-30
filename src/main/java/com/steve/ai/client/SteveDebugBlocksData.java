package com.steve.ai.client;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SteveDebugBlocksData {
    private static final Map<Integer, List<VisibleBlockEntry>> VISIBLE_BLOCKS = new HashMap<>();
    private static Integer selectedSteveId = null;

    private SteveDebugBlocksData() {
    }

    public static void updateVisibleBlocks(int steveEntityId, List<VisibleBlockEntry> entries) {
        VISIBLE_BLOCKS.put(steveEntityId, new ArrayList<>(entries));
        if (selectedSteveId == null) {
            selectedSteveId = steveEntityId;
        }
    }

    public static List<VisibleBlockEntry> getSelectedVisibleBlocks() {
        if (selectedSteveId == null) {
            return List.of();
        }
        return VISIBLE_BLOCKS.getOrDefault(selectedSteveId, List.of());
    }

    public static String getSelectedSteveLabel(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || selectedSteveId == null) {
            return "None";
        }
        Entity entity = minecraft.level.getEntity(selectedSteveId);
        if (entity instanceof SteveEntity steveEntity) {
            return steveEntity.getSteveName();
        }
        return "Unknown";
    }

    public static void cycleSelectedSteve(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        List<SteveEntity> steves = minecraft.level.entitiesForRendering().stream()
            .filter(SteveEntity.class::isInstance)
            .map(SteveEntity.class::cast)
            .sorted(Comparator.comparingInt(Entity::getId))
            .toList();

        if (steves.isEmpty()) {
            selectedSteveId = null;
            return;
        }

        if (selectedSteveId == null) {
            selectedSteveId = steves.getFirst().getId();
            return;
        }

        int index = 0;
        for (int i = 0; i < steves.size(); i++) {
            if (steves.get(i).getId() == selectedSteveId) {
                index = (i + 1) % steves.size();
                break;
            }
        }
        selectedSteveId = steves.get(index).getId();
    }
}
