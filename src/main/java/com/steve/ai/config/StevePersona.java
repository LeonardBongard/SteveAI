package com.steve.ai.config;

public enum StevePersona {
    TUNNELER,
    CAVE_EXPLORER;

    public static StevePersona fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return TUNNELER;
        }
        String normalized = raw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "cave_explorer", "explorer", "cave" -> CAVE_EXPLORER;
            default -> TUNNELER;
        };
    }

    public boolean prefersTunneling() {
        return this == TUNNELER;
    }

    public boolean prefersCaveExploration() {
        return this == CAVE_EXPLORER;
    }
}

