package com.steve.ai.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StevePersonaProfiles {
    private static final Map<String, StevePersona> RUNTIME_OVERRIDES = new ConcurrentHashMap<>();

    private StevePersonaProfiles() {
    }

    public static StevePersona forSteveName(String steveName) {
        String key = normalizeName(steveName);
        StevePersona runtimeOverride = RUNTIME_OVERRIDES.get(key);
        if (runtimeOverride != null) {
            return runtimeOverride;
        }
        Map<String, StevePersona> overrides = parseOverrides(SteveConfig.PERSONA_OVERRIDES.get());
        StevePersona override = overrides.get(key);
        if (override != null) {
            return override;
        }
        return StevePersona.fromString(SteveConfig.PERSONA_DEFAULT.get());
    }

    public static void setRuntimeOverride(String steveName, StevePersona persona) {
        String key = normalizeName(steveName);
        if (key.isBlank() || persona == null) {
            return;
        }
        RUNTIME_OVERRIDES.put(key, persona);
    }

    public static void clearRuntimeOverride(String steveName) {
        String key = normalizeName(steveName);
        if (key.isBlank()) {
            return;
        }
        RUNTIME_OVERRIDES.remove(key);
    }

    static Map<String, StevePersona> parseOverrides(String raw) {
        Map<String, StevePersona> result = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String[] entries = raw.split(",");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String name = normalizeName(parts[0]);
            if (name.isBlank()) {
                continue;
            }
            if (parts[1] == null || parts[1].isBlank()) {
                continue;
            }
            result.put(name, StevePersona.fromString(parts[1]));
        }
        return result;
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
