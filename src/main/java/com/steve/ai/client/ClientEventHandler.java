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
            if (mc.screen instanceof SteveOverlayScreen) {
                return;
            }
            com.steve.ai.SteveMod.LOGGER.info("Keybinding pressed: TOGGLE_GUI");
            SteveGUI.toggle();
        }

        if (KeyBindings.TOGGLE_STEVE_POV != null && KeyBindings.TOGGLE_STEVE_POV.consumeClick()) {
            boolean switched = StevePovScreenshot.toggleSteveCamera();
            if (!switched) {
                com.steve.ai.SteveMod.LOGGER.warn("No Steve found for POV camera toggle");
            }
        }

        if (KeyBindings.TOGGLE_DEBUG_BLOCKS != null && KeyBindings.TOGGLE_DEBUG_BLOCKS.consumeClick()) {
            if (mc.screen instanceof SteveDebugBlocksScreen) {
                mc.setScreen(null);
            } else {
                mc.setScreen(new SteveDebugBlocksScreen());
            }
        }

        StevePovScreenshot.onClientTick();
    }
}
