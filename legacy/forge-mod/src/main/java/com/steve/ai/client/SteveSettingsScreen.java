package com.steve.ai.client;

import com.steve.ai.config.SteveRuntimeSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public class SteveSettingsScreen extends Screen {
    @Nullable
    private final Screen parent;

    public SteveSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Steve Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = 420;
        int x = (this.width - panelWidth) / 2;
        int y = 44;
        int row = 0;

        addIntRow(x, y + row++ * 28, "Scan Radius", 1,
            SteveRuntimeSettings::getVisibleScanRadius, SteveRuntimeSettings::setVisibleScanRadius);
        addIntRow(x, y + row++ * 28, "Visible Memory Entries", 120,
            SteveRuntimeSettings::getVisibleMaxEntries, SteveRuntimeSettings::setVisibleMaxEntries);
        addIntRow(x, y + row++ * 28, "Synced Working Positions", 16,
            SteveRuntimeSettings::getSyncedWorkingPositions, SteveRuntimeSettings::setSyncedWorkingPositions);
        addIntRow(x, y + row++ * 28, "Synced Episodic Positions", 16,
            SteveRuntimeSettings::getSyncedEpisodicPositions, SteveRuntimeSettings::setSyncedEpisodicPositions);

        this.addRenderableWidget(Button.builder(Component.literal("-"), b ->
                SteveRuntimeSettings.setMemoryMarkerScale(SteveRuntimeSettings.getMemoryMarkerScale() - 0.1F))
            .bounds(x + 244, y + row * 28, 22, 20)
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b ->
                SteveRuntimeSettings.setMemoryMarkerScale(SteveRuntimeSettings.getMemoryMarkerScale() + 0.1F))
            .bounds(x + 386, y + row * 28, 22, 20)
            .build());
        row++;

        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(x + panelWidth - 100, y + row * 28 + 14, 90, 20)
            .build());
    }

    private void addIntRow(int x, int y, String label, int step, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> setter.accept(getter.getAsInt() - step))
            .bounds(x + 244, y, 22, 20)
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> setter.accept(getter.getAsInt() + step))
            .bounds(x + 386, y, 22, 20)
            .build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        int panelWidth = 420;
        int panelHeight = 230;
        int x = (this.width - panelWidth) / 2;
        int y = 30;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xCC0F0F0F);
        graphics.fill(x, y, x + panelWidth, y + 1, 0xFF4A4A4A);
        graphics.fill(x, y, x + 1, y + panelHeight, 0xFF4A4A4A);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, 0xFF4A4A4A);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, 0xFF4A4A4A);

        graphics.drawString(this.font, "Steve Runtime Settings", x + 10, y + 10, 0xFFFFFFFF, false);

        int rowY = y + 40;
        drawRow(graphics, x, rowY, "Scan Radius", Integer.toString(SteveRuntimeSettings.getVisibleScanRadius()));
        rowY += 28;
        drawRow(graphics, x, rowY, "Visible Memory Entries", Integer.toString(SteveRuntimeSettings.getVisibleMaxEntries()));
        rowY += 28;
        drawRow(graphics, x, rowY, "Synced Working Positions", Integer.toString(SteveRuntimeSettings.getSyncedWorkingPositions()));
        rowY += 28;
        drawRow(graphics, x, rowY, "Synced Episodic Positions", Integer.toString(SteveRuntimeSettings.getSyncedEpisodicPositions()));
        rowY += 28;
        drawRow(graphics, x, rowY, "Memory Marker Scale", String.format(java.util.Locale.ROOT, "%.1fx", SteveRuntimeSettings.getMemoryMarkerScale()));

        graphics.drawString(this.font, "Open this from K chat with 'settings' or with O key.", x + 10, y + panelHeight - 16, 0xFFBBBBBB, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics graphics, int x, int y, String label, String value) {
        graphics.drawString(this.font, label, x + 10, y + 6, 0xFFE0E0E0, false);
        graphics.drawString(this.font, value, x + 276, y + 6, 0xFFFFFFFF, false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
