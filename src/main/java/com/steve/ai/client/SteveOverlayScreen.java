package com.steve.ai.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Invisible overlay screen that captures input for the Steve GUI
 * This prevents game controls from activating while typing
 */
public class SteveOverlayScreen extends Screen {
    
    public SteveOverlayScreen() {
        super(Component.literal("Steve AI"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render the panel here so it shows while a Screen is open
        SteveGUI.renderPanel(graphics);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Don't intercept K key here - let it go to the input box
        // The GUI can be closed with ESC (handled in SteveGUI.handleKeyPress)
        return SteveGUI.handleKeyPress(event.key(), event.scancode(), event.modifiers());
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // Pass character input to SteveGUI
        return SteveGUI.handleCharTyped((char) event.codepoint(), event.modifiers());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        SteveGUI.handleMouseClick(event.x(), event.y(), event.button());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        SteveGUI.handleMouseScroll(scrollY);
        return true;
    }

    @Override
    public void removed() {
        // Clean up when screen is closed
        if (SteveGUI.isOpen()) {
            SteveGUI.toggle();
        }
    }
}
