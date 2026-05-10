package com.steve.ai.resource;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Centralized smelt-fuel policy so smelting/crafting can use category-based fuel fallback.
 */
public final class FuelPolicy {
    public static final String COAL = "minecraft:coal";
    public static final String CHARCOAL = "minecraft:charcoal";
    public static final String COAL_BLOCK = "minecraft:coal_block";
    public static final String BLAZE_ROD = "minecraft:blaze_rod";
    public static final String OAK_LOG = "minecraft:oak_log";
    public static final String SPRUCE_LOG = "minecraft:spruce_log";
    public static final String BIRCH_LOG = "minecraft:birch_log";
    public static final String JUNGLE_LOG = "minecraft:jungle_log";
    public static final String ACACIA_LOG = "minecraft:acacia_log";
    public static final String DARK_OAK_LOG = "minecraft:dark_oak_log";
    public static final String MANGROVE_LOG = "minecraft:mangrove_log";
    public static final String CHERRY_LOG = "minecraft:cherry_log";

    private static final List<String> INVENTORY_FUEL_PRIORITY = List.of(
        COAL_BLOCK,
        BLAZE_ROD,
        COAL,
        CHARCOAL,
        OAK_LOG,
        SPRUCE_LOG,
        BIRCH_LOG,
        JUNGLE_LOG,
        ACACIA_LOG,
        DARK_OAK_LOG,
        MANGROVE_LOG,
        CHERRY_LOG
    );

    private static final List<String> GATHERABLE_FUEL_PRIORITY = List.of(
        COAL,
        OAK_LOG
    );

    private FuelPolicy() {
    }

    public static List<String> inventoryFuelPriority() {
        return INVENTORY_FUEL_PRIORITY;
    }

    public static List<String> gatherableFuelPriority() {
        return GATHERABLE_FUEL_PRIORITY;
    }

    public static int fuelUnitsForItem(String itemId) {
        String normalized = normalizeId(itemId);
        return switch (normalized) {
            case COAL_BLOCK -> 80;
            case BLAZE_ROD -> 12;
            case COAL, CHARCOAL -> 8;
            // Conservative unit estimate for logs (actual burn time is higher).
            case OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG, ACACIA_LOG, DARK_OAK_LOG, MANGROVE_LOG, CHERRY_LOG -> 1;
            default -> 0;
        };
    }

    public static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        if (normalized.contains(":")) {
            return normalized;
        }
        Identifier id = Identifier.tryParse("minecraft:" + normalized);
        return id == null ? normalized : id.toString();
    }
}
