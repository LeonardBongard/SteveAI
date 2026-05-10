package com.steve.ai.action;

import com.steve.ai.gather.ResourceTargetResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTargetResolverTest {
    private static final Set<String> EXPECTED_WOOD_FAMILY_SOURCES = Set.of(
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:jungle_log",
        "minecraft:acacia_log",
        "minecraft:dark_oak_log",   // 2x2 trunk family support
        "minecraft:mangrove_log",   // complex roots/branches family support
        "minecraft:cherry_log",
        "minecraft:bamboo_block",   // bamboo crafting path support
        "minecraft:crimson_stem",   // nether wood family
        "minecraft:warped_stem"     // nether wood family
    );

    @Test
    void resolvesPlanksToAnyOverworldLog() {
        List<String> candidates = ResourceTargetResolver.gatherCandidatesForItemId("minecraft:oak_planks");
        assertTrue(candidates.contains("minecraft:oak_log"));
        assertTrue(candidates.contains("minecraft:spruce_log"));
        assertTrue(candidates.contains("minecraft:birch_log"));
    }

    @Test
    void resolvesCoalItemToOreVariants() {
        List<String> candidates = ResourceTargetResolver.gatherCandidatesForItemId("minecraft:coal");
        assertTrue(candidates.contains("minecraft:coal_ore"));
        assertTrue(candidates.contains("minecraft:deepslate_coal_ore"));
        assertEquals(2, candidates.size());
    }

    @Test
    void resolvesStickToMultipleWoodFamilies() {
        List<String> candidates = ResourceTargetResolver.gatherCandidatesForItemId("minecraft:stick");
        assertTrue(candidates.contains("minecraft:oak_log"));
        assertTrue(candidates.contains("minecraft:bamboo_block"));
        assertTrue(candidates.contains("minecraft:crimson_stem"));
    }

    @Test
    void woodMatrixPlanksSticksAndTableResolveToAllConfiguredWoodFamilies() {
        assertContainsAllWoodFamilies(ResourceTargetResolver.gatherCandidatesForItemId("minecraft:oak_planks"));
        assertContainsAllWoodFamilies(ResourceTargetResolver.gatherCandidatesForItemId("minecraft:stick"));
        assertContainsAllWoodFamilies(ResourceTargetResolver.gatherCandidatesForItemId("minecraft:crafting_table"));
    }

    @Test
    void woodFamilyCandidateSetSizeIsStable() {
        List<String> tableCandidates = ResourceTargetResolver.gatherCandidatesForItemId("minecraft:crafting_table");
        Set<String> unique = new LinkedHashSet<>(tableCandidates);
        assertEquals(EXPECTED_WOOD_FAMILY_SOURCES.size(), unique.size());
    }

    @Test
    void mergesPrimaryAndAlternativesUniquely() {
        List<String> merged = ResourceTargetResolver.mergeCandidates("oak_log", List.of("spruce_log", "oak_log"));
        assertEquals(List.of("oak_log", "spruce_log"), merged);
    }

    private void assertContainsAllWoodFamilies(List<String> candidates) {
        Set<String> unique = new LinkedHashSet<>(candidates);
        for (String expected : EXPECTED_WOOD_FAMILY_SOURCES) {
            assertTrue(
                unique.contains(expected),
                "Expected wood source missing from resolver candidates: " + expected
            );
        }
    }
}
