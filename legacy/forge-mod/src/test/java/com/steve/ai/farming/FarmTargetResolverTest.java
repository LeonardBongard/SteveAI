package com.steve.ai.farming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmTargetResolverTest {
    @Test
    void defaultsToWheatWhenMissing() {
        FarmTargetResolver.CropSpec spec = FarmTargetResolver.resolve(null);
        assertEquals("minecraft:wheat", spec.cropBlockId());
        assertEquals("minecraft:wheat", spec.produceItemId());
        assertEquals("minecraft:wheat_seeds", spec.seedItemId());
    }

    @Test
    void resolvesCarrotAliases() {
        FarmTargetResolver.CropSpec spec = FarmTargetResolver.resolve("carrots");
        assertEquals("minecraft:carrots", spec.cropBlockId());
        assertEquals("minecraft:carrot", spec.produceItemId());
        assertEquals("minecraft:carrot", spec.seedItemId());
    }

    @Test
    void resolvesPotatoAliases() {
        FarmTargetResolver.CropSpec spec = FarmTargetResolver.resolve("minecraft:potato");
        assertEquals("minecraft:potatoes", spec.cropBlockId());
        assertEquals("minecraft:potato", spec.produceItemId());
        assertEquals("minecraft:potato", spec.seedItemId());
    }

    @Test
    void seedGatherResourceUsesSeedItemId() {
        FarmTargetResolver.CropSpec wheat = FarmTargetResolver.resolve("wheat");
        FarmTargetResolver.CropSpec carrot = FarmTargetResolver.resolve("carrot");
        assertEquals("minecraft:wheat_seeds", FarmTargetResolver.seedGatherResourceId(wheat));
        assertEquals("minecraft:carrot", FarmTargetResolver.seedGatherResourceId(carrot));
    }

    @Test
    void defaultHoeIsWoodenHoe() {
        assertEquals("minecraft:wooden_hoe", FarmTargetResolver.defaultHoeItemId());
    }
}
