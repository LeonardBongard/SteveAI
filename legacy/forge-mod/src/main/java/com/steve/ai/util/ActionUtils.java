package com.steve.ai.util;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Common utility methods used across multiple action classes
 */
public class ActionUtils {

    /**
     * Find the nearest player to a Steve entity
     *
     * @param steve The Steve entity
     * @return The nearest player, or null if no players found
     */
    public static Player findNearestPlayer(SteveEntity steve) {
        List<? extends Player> players = steve.level().players();

        if (players.isEmpty()) {
            return null;
        }

        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }

            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    /**
     * Parse a block name string into a Block instance
     * Handles common resource names and aliases
     *
     * @param blockName The block name (e.g., "iron_ore", "diamond", "minecraft:stone")
     * @return The Block instance, or Blocks.AIR if not found
     */
    public static Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");

        Map<String, String> aliases = new HashMap<>() {{
            put("iron", "iron_ore");
            put("diamond", "diamond_ore");
            put("coal", "coal_ore");
            put("gold", "gold_ore");
            put("copper", "copper_ore");
            put("redstone", "redstone_ore");
            put("lapis", "lapis_ore");
            put("emerald", "emerald_ore");
            put("wood", "oak_log");
            put("woods", "oak_log");
            put("log", "oak_log");
            put("logs", "oak_log");
            put("tree", "oak_log");
            put("trees", "oak_log");
        }};

        if (aliases.containsKey(blockName)) {
            blockName = aliases.get(blockName);
        }

        // Add minecraft namespace if not present
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }

        Identifier identifier = Identifier.tryParse(blockName);
        if (identifier == null) {
            return Blocks.AIR;
        }
        return BuiltInRegistries.BLOCK.getOptional(identifier).orElse(Blocks.AIR);
    }
}
