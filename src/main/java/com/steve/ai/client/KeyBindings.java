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
    }
}
