package com.steve.ai.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraftforge.event.TickEvent;

/**
 * Handles client-side events, including disabling the narrator and checking key presses
 */
public class ClientEventHandler {
    
    private static boolean narratorDisabled = false;
    
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        
        if (!narratorDisabled && mc.options != null) {
            mc.options.narrator().set(NarratorStatus.OFF);
            mc.options.save();
            narratorDisabled = true;
        }
        
        if (KeyBindings.TOGGLE_GUI != null && KeyBindings.TOGGLE_GUI.consumeClick()) {
            SteveGUI.toggle();
        }
    }
}
