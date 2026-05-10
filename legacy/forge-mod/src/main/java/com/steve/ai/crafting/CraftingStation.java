package com.steve.ai.crafting;

public enum CraftingStation {
    NONE("none"),
    CRAFTING_TABLE("minecraft:crafting_table"),
    FURNACE("minecraft:furnace"),
    STONECUTTER("minecraft:stonecutter");

    private final String blockId;

    CraftingStation(String blockId) {
        this.blockId = blockId;
    }

    public String blockId() {
        return blockId;
    }
}
