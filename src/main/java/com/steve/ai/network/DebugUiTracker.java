package com.steve.ai.network;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DebugUiTracker {
    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    private DebugUiTracker() {
    }

    public static void setSubscribed(UUID playerId, boolean subscribed) {
        if (subscribed) {
            SUBSCRIBERS.add(playerId);
        } else {
            SUBSCRIBERS.remove(playerId);
        }
    }

    public static boolean isSubscribed(UUID playerId) {
        return SUBSCRIBERS.contains(playerId);
    }

    public static void remove(UUID playerId) {
        SUBSCRIBERS.remove(playerId);
    }

    public static boolean isEmpty() {
        return SUBSCRIBERS.isEmpty();
    }
}
