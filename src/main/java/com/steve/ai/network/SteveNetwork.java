package com.steve.ai.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Lightweight compatibility shim for debug packet transport.
 *
 * <p>The current branch keeps debug snapshot production/consumption modular,
 * while transport wiring can be implemented per target Forge/NeoForge API.</p>
 */
public final class SteveNetwork {
    private SteveNetwork() {
    }

    public static void sendDebugUiState(boolean enabled) {
        // Transport wiring intentionally deferred for API-compatibility cleanup.
    }

    public static void sendToPlayer(ServerPlayer player, VisibleBlocksPacket packet) {
        // Transport wiring intentionally deferred for API-compatibility cleanup.
    }
}

