package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import com.mojang.serialization.Codec;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SteveMemory {
    private final SteveEntity steve;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private List<VisibleBlockEntry> visibleBlocks;
    private static final int MAX_RECENT_ACTIONS = 20;

    public SteveMemory(SteveEntity steve) {
        this.steve = steve;
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();
        this.visibleBlocks = new ArrayList<>();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void addAction(String action) {
        recentActions.addLast(action);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    public List<String> getRecentActions(int count) {
        int size = Math.min(count, recentActions.size());
        List<String> result = new ArrayList<>();
        
        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }
        
        return result;
    }

    public List<VisibleBlockEntry> getVisibleBlocks() {
        return Collections.unmodifiableList(visibleBlocks);
    }

    public void setVisibleBlocks(List<VisibleBlockEntry> visibleBlocks) {
        this.visibleBlocks = new ArrayList<>(visibleBlocks);
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }

    public void saveToNBT(ValueOutput output) {
        output.putString("CurrentGoal", currentGoal);

        ValueOutput.TypedOutputList<String> actionsList = output.list("RecentActions", Codec.STRING);
        for (String action : recentActions) {
            actionsList.add(action);
        }
    }

    public void loadFromNBT(ValueInput input) {
        currentGoal = input.getStringOr("CurrentGoal", currentGoal);

        input.list("RecentActions", Codec.STRING).ifPresent(list -> {
            recentActions.clear();
            list.stream().forEach(recentActions::add);
        });
    }
}
