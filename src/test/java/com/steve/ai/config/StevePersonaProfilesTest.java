package com.steve.ai.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StevePersonaProfilesTest {
    @Test
    void parsesPersonaOverridesByName() {
        Map<String, StevePersona> parsed = StevePersonaProfiles.parseOverrides(
            "Steve:tunneler, Alex:cave_explorer, Bob:explorer"
        );

        assertEquals(StevePersona.TUNNELER, parsed.get("steve"));
        assertEquals(StevePersona.CAVE_EXPLORER, parsed.get("alex"));
        assertEquals(StevePersona.CAVE_EXPLORER, parsed.get("bob"));
    }

    @Test
    void ignoresMalformedEntries() {
        Map<String, StevePersona> parsed = StevePersonaProfiles.parseOverrides(
            "broken, :tunneler, Eve:"
        );

        assertTrue(parsed.isEmpty());
    }
}

