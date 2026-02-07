package com.steve.ai.client;

import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

public class SteveDebugBlocksScreen extends Screen {
    private static final int PANEL_MARGIN = 20;
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 18;

    private int scrollOffset;
    private int maxScroll;

    public SteveDebugBlocksScreen() {
        super(Component.literal("Steve Visible Blocks"));
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(360, this.width - PANEL_MARGIN * 2);
        int panelX = (this.width - panelWidth) / 2;
        int panelY = PANEL_MARGIN;
        this.addRenderableWidget(Button.builder(Component.literal("Next Steve"), button ->
                SteveDebugBlocksData.cycleSelectedSteve(Minecraft.getInstance()))
            .bounds(panelX + panelWidth - 92, panelY + 4, 84, 18)
            .build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(360, this.width - PANEL_MARGIN * 2);
        int panelHeight = this.height - PANEL_MARGIN * 2;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = PANEL_MARGIN;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xAA000000);
        String title = "Visible Blocks - " + SteveDebugBlocksData.getSelectedSteveLabel(this.minecraft);
        graphics.drawString(this.font, title, panelX + 8, panelY + 8, 0xFFFFFF, false);

        List<VisibleBlockEntry> entries = SteveDebugBlocksData.getSelectedVisibleBlocks();
        int listTop = panelY + HEADER_HEIGHT;
        int listBottom = panelY + panelHeight - FOOTER_HEIGHT;
        int listHeight = listBottom - listTop;
        int lineHeight = this.font.lineHeight + 2;
        int contentHeight = entries.size() * lineHeight;
        maxScroll = Math.max(0, contentHeight - listHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int startIndex = scrollOffset / lineHeight;
        int yOffset = listTop - (scrollOffset % lineHeight);

        for (int i = startIndex; i < entries.size(); i++) {
            int lineY = yOffset + (i - startIndex) * lineHeight;
            if (lineY > listBottom - lineHeight) {
                break;
            }
            VisibleBlockEntry entry = entries.get(i);
            graphics.drawString(this.font, formatEntry(entry), panelX + 8, lineY, 0xE0E0E0, false);
        }

        graphics.drawString(this.font, "Scroll: list | ESC: close", panelX + 8, listBottom + 2, 0xAAAAAA, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (this.font.lineHeight + 2) * 3;
        scrollOffset -= (int) (scrollY * step);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private String formatEntry(VisibleBlockEntry entry) {
        return String.format(
            Locale.ROOT,
            "%s @ %d,%d,%d (%.1fm, t=%d)",
            entry.blockId(),
            entry.position().getX(),
            entry.position().getY(),
            entry.position().getZ(),
            entry.distance(),
            entry.lastSeenTick()
        );
    }
}
