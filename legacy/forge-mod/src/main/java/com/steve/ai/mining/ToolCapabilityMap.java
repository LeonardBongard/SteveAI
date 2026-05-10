package com.steve.ai.mining;

import com.steve.ai.SteveMod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ToolCapabilityMap {
    private static final String RESOURCE_PATH = "/steve/block_tool_requirements.csv";
    private static final Map<String, ToolRequirement> REQUIREMENTS = new HashMap<>();
    private static boolean loaded = false;

    private ToolCapabilityMap() {
    }

    public static synchronized void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;

        try (InputStream stream = ToolCapabilityMap.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                SteveMod.LOGGER.warn("Tool requirements file not found at {}", RESOURCE_PATH);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    if (parts.length < 2) {
                        continue;
                    }
                    String blockId = parts[0].trim();
                    String requiredRaw = parts[1].trim();
                    String preferredRaw = parts.length >= 3 ? parts[2].trim() : "";
                    String minTierRaw = parts.length >= 4 ? parts[3].trim() : "";
                    if (blockId.isEmpty() || requiredRaw.isEmpty()) {
                        continue;
                    }
                    ToolType required = ToolType.fromCsv(requiredRaw);
                    ToolType preferred = preferredRaw.isEmpty() ? ToolType.NONE : ToolType.fromCsv(preferredRaw);
                    ToolTier minTier = minTierRaw.isEmpty()
                        ? defaultMinPickaxeTier(blockId)
                        : ToolTier.fromCsv(minTierRaw);
                    if (required != ToolType.PICKAXE) {
                        minTier = ToolTier.NONE;
                    }
                    REQUIREMENTS.put(blockId, new ToolRequirement(required, preferred, minTier));
                }
            }
        } catch (Exception ex) {
            SteveMod.LOGGER.warn("Failed to load tool requirements map", ex);
        }
    }

    public static ToolRequirement getRequirement(String blockId) {
        loadIfNeeded();
        if (blockId == null || blockId.isBlank()) {
            return ToolRequirement.NONE;
        }
        return REQUIREMENTS.getOrDefault(blockId, ToolRequirement.NONE);
    }

    public record ToolRequirement(ToolType required, ToolType preferred, ToolTier minTier) {
        public static final ToolRequirement NONE = new ToolRequirement(ToolType.NONE, ToolType.NONE, ToolTier.NONE);
    }

    public static ToolTier requiredPickaxeTier(String blockId) {
        ToolRequirement req = getRequirement(blockId);
        if (req.required() != ToolType.PICKAXE) {
            return ToolTier.NONE;
        }
        return req.minTier();
    }

    public enum ToolType {
        NONE,
        PICKAXE,
        SHOVEL,
        AXE,
        HOE;

        public static ToolType fromCsv(String raw) {
            return switch (raw.trim().toLowerCase()) {
                case "pickaxe" -> PICKAXE;
                case "shovel" -> SHOVEL;
                case "axe" -> AXE;
                case "hoe" -> HOE;
                default -> NONE;
            };
        }

        public String label() {
            return switch (this) {
                case PICKAXE -> "pickaxe";
                case SHOVEL -> "shovel";
                case AXE -> "axe";
                case HOE -> "hoe";
                default -> "hand";
            };
        }

        public static boolean matches(ToolType toolType, net.minecraft.world.item.ItemStack stack) {
            if (toolType == null || stack == null || stack.isEmpty()) {
                return false;
            }
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            return switch (toolType) {
                case PICKAXE -> itemId.endsWith("_pickaxe");
                case SHOVEL -> itemId.endsWith("_shovel");
                case AXE -> itemId.endsWith("_axe");
                case HOE -> itemId.endsWith("_hoe");
                default -> false;
            };
        }
    }

    public enum ToolTier {
        NONE(0),
        WOOD(1),
        STONE(2),
        IRON(3),
        DIAMOND(4),
        NETHERITE(5);

        private final int rank;

        ToolTier(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        public boolean atLeast(ToolTier other) {
            if (other == null) {
                return true;
            }
            return this.rank >= other.rank;
        }

        public static ToolTier fromCsv(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            return switch (raw.trim().toLowerCase()) {
                case "wood", "wooden", "gold", "golden" -> WOOD;
                case "stone" -> STONE;
                case "iron" -> IRON;
                case "diamond" -> DIAMOND;
                case "netherite" -> NETHERITE;
                default -> NONE;
            };
        }

        public static ToolTier detectPickaxeTier(String itemId) {
            if (itemId == null || itemId.isBlank()) {
                return NONE;
            }
            String normalized = itemId.trim().toLowerCase();
            if (normalized.endsWith("netherite_pickaxe")) {
                return NETHERITE;
            }
            if (normalized.endsWith("diamond_pickaxe")) {
                return DIAMOND;
            }
            if (normalized.endsWith("iron_pickaxe")) {
                return IRON;
            }
            if (normalized.endsWith("stone_pickaxe")) {
                return STONE;
            }
            if (normalized.endsWith("wooden_pickaxe") || normalized.endsWith("golden_pickaxe")) {
                return WOOD;
            }
            return NONE;
        }

        public String label() {
            return switch (this) {
                case WOOD -> "wooden";
                case STONE -> "stone";
                case IRON -> "iron";
                case DIAMOND -> "diamond";
                case NETHERITE -> "netherite";
                default -> "any";
            };
        }
    }

    private static ToolTier defaultMinPickaxeTier(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return ToolTier.NONE;
        }
        String id = blockId.trim().toLowerCase();

        if (id.equals("minecraft:obsidian")
            || id.equals("minecraft:crying_obsidian")
            || id.equals("minecraft:ancient_debris")
            || id.equals("minecraft:respawn_anchor")) {
            return ToolTier.DIAMOND;
        }

        if (id.equals("minecraft:diamond_ore")
            || id.equals("minecraft:deepslate_diamond_ore")
            || id.equals("minecraft:emerald_ore")
            || id.equals("minecraft:deepslate_emerald_ore")
            || id.equals("minecraft:gold_ore")
            || id.equals("minecraft:deepslate_gold_ore")
            || id.equals("minecraft:redstone_ore")
            || id.equals("minecraft:deepslate_redstone_ore")) {
            return ToolTier.IRON;
        }

        if (id.equals("minecraft:iron_ore")
            || id.equals("minecraft:deepslate_iron_ore")
            || id.equals("minecraft:lapis_ore")
            || id.equals("minecraft:deepslate_lapis_ore")
            || id.equals("minecraft:copper_ore")
            || id.equals("minecraft:deepslate_copper_ore")) {
            return ToolTier.STONE;
        }

        if (id.endsWith("_ore") || id.startsWith("minecraft:deepslate_")) {
            return ToolTier.WOOD;
        }
        return ToolTier.NONE;
    }
}
