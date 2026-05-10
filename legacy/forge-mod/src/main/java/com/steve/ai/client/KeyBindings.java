package com.steve.ai.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    
    public static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("steve", "steve"));
    
    public static KeyMapping TOGGLE_GUI;
    public static KeyMapping TOGGLE_STEVE_POV;
    public static KeyMapping TOGGLE_STEVE_INVENTORY;
    public static KeyMapping TOGGLE_STEVE_MEMORY;
    public static KeyMapping OPEN_STEVE_SETTINGS;
    public static KeyMapping CYCLE_DEBUG_STEVE;

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        TOGGLE_GUI = new KeyMapping(
            "key.steve.toggle_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, // K key
            KEY_CATEGORY
        );
        
        event.register(TOGGLE_GUI);

        TOGGLE_STEVE_POV = new KeyMapping(
            "key.steve.toggle_pov",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, // P key
            KEY_CATEGORY
        );

        event.register(TOGGLE_STEVE_POV);

        TOGGLE_STEVE_INVENTORY = new KeyMapping(
            "key.steve.toggle_inventory",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I, // I key
            KEY_CATEGORY
        );

        event.register(TOGGLE_STEVE_INVENTORY);

        TOGGLE_STEVE_MEMORY = new KeyMapping(
            "key.steve.toggle_memory",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M, // M key
            KEY_CATEGORY
        );

        event.register(TOGGLE_STEVE_MEMORY);

        OPEN_STEVE_SETTINGS = new KeyMapping(
            "key.steve.open_settings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O, // O key
            KEY_CATEGORY
        );

        event.register(OPEN_STEVE_SETTINGS);

        CYCLE_DEBUG_STEVE = new KeyMapping(
            "key.steve.cycle_debug_steve",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, // U key
            KEY_CATEGORY
        );

        event.register(CYCLE_DEBUG_STEVE);
    }
}
