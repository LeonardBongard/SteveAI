package com.steve.ai.execution.behavior;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.entity.SteveEntity;

public class BehaviorContext {
    private final SteveEntity steve;
    private final ActionExecutor executor;
    private final long gameTime;

    public BehaviorContext(SteveEntity steve, ActionExecutor executor, long gameTime) {
        this.steve = steve;
        this.executor = executor;
        this.gameTime = gameTime;
    }

    public SteveEntity steve() {
        return steve;
    }

    public ActionExecutor executor() {
        return executor;
    }

    public long gameTime() {
        return gameTime;
    }

    public boolean isServerSide() {
        return !steve.level().isClientSide();
    }
}

