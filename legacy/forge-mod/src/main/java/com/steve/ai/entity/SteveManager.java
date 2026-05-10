package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SteveManager {
    private final Map<String, SteveEntity> activeSteves;
    private final Map<UUID, SteveEntity> stevesByUUID;
    private final Map<UUID, ChunkPos> forcedChunksBySteve;

    public SteveManager() {
        this.activeSteves = new ConcurrentHashMap<>();
        this.stevesByUUID = new ConcurrentHashMap<>();
        this.forcedChunksBySteve = new ConcurrentHashMap<>();
    }

    public SteveEntity spawnSteve(ServerLevel level, Vec3 position, String name) {
        SteveMod.LOGGER.info("Current active Steves: {}", activeSteves.size());

        if (activeSteves.containsKey(name)) {
            SteveMod.LOGGER.warn("Steve name '{}' already exists", name);
            return null;
        }

        int maxSteves = SteveConfig.MAX_ACTIVE_STEVES.get();
        if (activeSteves.size() >= maxSteves) {
            SteveMod.LOGGER.warn("Max Steve limit reached: {}", maxSteves);
            return null;
        }

        SteveEntity steve;
        try {
            SteveMod.LOGGER.info("EntityType: {}", SteveMod.STEVE_ENTITY.get());
            steve = new SteveEntity(SteveMod.STEVE_ENTITY.get(), level);
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Failed to create Steve entity", e);
            SteveMod.LOGGER.error("Exception class: {}", e.getClass().getName());
            SteveMod.LOGGER.error("Exception message: {}", e.getMessage());
            return null;
        }

        try {
            steve.setSteveName(name);
            steve.setPos(position.x, position.y, position.z);
            boolean added = level.addFreshEntity(steve);
            if (added) {
                activeSteves.put(name, steve);
                stevesByUUID.put(steve.getUUID(), steve);
                forceChunkForSteve(level, steve.getUUID(), steve.chunkPosition());
                SteveMod.LOGGER.info(
                    "Successfully spawned Steve: {} with UUID {} at {}",
                    name,
                    steve.getUUID(),
                    position
                );
                return steve;
            }

            SteveMod.LOGGER.error("Failed to add Steve entity to world (addFreshEntity returned false)");
            SteveMod.LOGGER.error("=== SPAWN ATTEMPT FAILED ===");
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Exception during spawn setup", e);
            SteveMod.LOGGER.error("=== SPAWN ATTEMPT FAILED WITH EXCEPTION ===");
        }

        return null;
    }

    public SteveEntity getSteve(String name) {
        return activeSteves.get(name);
    }

    public SteveEntity getSteve(UUID uuid) {
        return stevesByUUID.get(uuid);
    }

    public boolean removeSteve(String name) {
        SteveEntity steve = activeSteves.remove(name);
        if (steve != null) {
            releaseForcedChunk(steve.getUUID(), steve);
            stevesByUUID.remove(steve.getUUID());
            steve.discard();
            return true;
        }
        return false;
    }

    public void clearAllSteves() {
        SteveMod.LOGGER.info("Clearing {} Steve entities", activeSteves.size());
        for (SteveEntity steve : activeSteves.values()) {
            releaseForcedChunk(steve.getUUID(), steve);
            steve.discard();
        }
        activeSteves.clear();
        stevesByUUID.clear();
        forcedChunksBySteve.clear();
    }

    public Collection<SteveEntity> getAllSteves() {
        return Collections.unmodifiableCollection(activeSteves.values());
    }

    public List<String> getSteveNames() {
        return new ArrayList<>(activeSteves.keySet());
    }

    public int getActiveCount() {
        return activeSteves.size();
    }

    public void tick(ServerLevel level) {
        Iterator<Map.Entry<String, SteveEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SteveEntity> entry = iterator.next();
            SteveEntity steve = entry.getValue();

            if (!steve.isAlive() || steve.isRemoved()) {
                iterator.remove();
                stevesByUUID.remove(steve.getUUID());
                releaseForcedChunk(steve.getUUID(), steve);
                SteveMod.LOGGER.info("Cleaned up Steve: {}", entry.getKey());
                continue;
            }

            forceChunkForSteve(level, steve.getUUID(), steve.chunkPosition());
        }
    }

    private void forceChunkForSteve(ServerLevel level, UUID steveUuid, ChunkPos desiredChunk) {
        if (level == null || steveUuid == null || desiredChunk == null) {
            return;
        }

        ChunkPos previous = forcedChunksBySteve.get(steveUuid);
        if (previous != null && previous.equals(desiredChunk)) {
            return;
        }

        if (previous != null) {
            level.setChunkForced(previous.x, previous.z, false);
        }

        level.setChunkForced(desiredChunk.x, desiredChunk.z, true);
        forcedChunksBySteve.put(steveUuid, desiredChunk);
    }

    private void releaseForcedChunk(UUID steveUuid, SteveEntity steve) {
        if (steveUuid == null || steve == null || !(steve.level() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos previous = forcedChunksBySteve.remove(steveUuid);
        if (previous != null) {
            level.setChunkForced(previous.x, previous.z, false);
        }
    }
}
