package com.steve.ai.farming;

import java.util.Locale;
import java.util.Map;

public final class FarmTargetResolver {
    public record CropSpec(String cropBlockId, String produceItemId, String seedItemId) {
    }

    private static final CropSpec WHEAT = new CropSpec("minecraft:wheat", "minecraft:wheat", "minecraft:wheat_seeds");
    private static final CropSpec CARROT = new CropSpec("minecraft:carrots", "minecraft:carrot", "minecraft:carrot");
    private static final CropSpec POTATO = new CropSpec("minecraft:potatoes", "minecraft:potato", "minecraft:potato");

    private static final Map<String, CropSpec> CROP_ALIASES = Map.ofEntries(
        Map.entry("wheat", WHEAT),
        Map.entry("minecraft:wheat", WHEAT),
        Map.entry("carrot", CARROT),
        Map.entry("carrots", CARROT),
        Map.entry("minecraft:carrot", CARROT),
        Map.entry("minecraft:carrots", CARROT),
        Map.entry("potato", POTATO),
        Map.entry("potatoes", POTATO),
        Map.entry("minecraft:potato", POTATO),
        Map.entry("minecraft:potatoes", POTATO)
    );

    private FarmTargetResolver() {
    }

    public static CropSpec resolve(String requestedCrop) {
        if (requestedCrop == null || requestedCrop.isBlank()) {
            return WHEAT;
        }
        String key = requestedCrop.trim().toLowerCase(Locale.ROOT);
        return CROP_ALIASES.getOrDefault(key, WHEAT);
    }

    public static String seedGatherResourceId(CropSpec spec) {
        if (spec == null || spec.seedItemId() == null || spec.seedItemId().isBlank()) {
            return "minecraft:wheat_seeds";
        }
        return spec.seedItemId();
    }

    public static String defaultHoeItemId() {
        return "minecraft:wooden_hoe";
    }
}
