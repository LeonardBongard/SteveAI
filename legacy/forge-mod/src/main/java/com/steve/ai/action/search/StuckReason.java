package com.steve.ai.action.search;

public enum StuckReason {
    NONE,
    NO_VISIBLE_TARGET,
    STALE_TARGET,
    UNREACHABLE_TARGET,
    SEARCH_LOOP,
    NO_PROGRESS_WHILE_MOVING;

    public boolean isStuck() {
        return this != NONE;
    }
}
