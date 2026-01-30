package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Side-mounted GUI panel for Steve agent interaction.
 * Inspired by Cursor's composer - slides in/out from the right side.
 * Now with scrollable message history!
 */
public class SteveGUI {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_PADDING = 6;
    private static final int ANIMATION_SPEED = 20;
    private static final int MESSAGE_HEIGHT = 12;
    private static final int MAX_MESSAGES = 500;
    
    private static boolean isOpen = false;
    private static float slideOffset = PANEL_WIDTH; // Start fully hidden
    private static EditBox inputBox;
    private static List<String> commandHistory = new ArrayList<>();
    private static int historyIndex = -1;
    
    // Message history and scrolling
    private static List<ChatMessage> messages = new ArrayList<>();
    private static int scrollOffset = 0;
    private static int maxScroll = 0;
    private static final int BACKGROUND_COLOR = 0x15202020; // Ultra transparent (15 = ~8% opacity)
    private static final int BORDER_COLOR = 0x40404040; // More transparent border
    private static final int HEADER_COLOR = 0x25252525; // More transparent header (~15% opacity)
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    
    // Message bubble colors
    private static final int USER_BUBBLE_COLOR = 0xC04CAF50; // Green bubble for user
    private static final int STEVE_BUBBLE_COLOR = 0xC02196F3; // Blue bubble for Steve
    private static final int SYSTEM_BUBBLE_COLOR = 0xC0FF9800; // Orange bubble for system
    private static final int DEBUG_BG_COLOR = 0xAA000000;
    private static final int DEBUG_TEXT_COLOR = 0xFFE0E0E0;
    private static final int DEBUG_ACCENT_COLOR = 0xFF7CD2FF;
    private static final long SNAPSHOT_REFRESH_MS = 1500L;

    private static class ChatMessage {
        String sender; // "You", "Steve", "Alex", "System", etc.
        String text;
        int bubbleColor;
        boolean isUser; // true if message from user
        
        ChatMessage(String sender, String text, int bubbleColor, boolean isUser) {
            this.sender = sender;
            this.text = text;
            this.bubbleColor = bubbleColor;
            this.isUser = isUser;
        }
    }

    public static void toggle() {
        isOpen = !isOpen;
        
        Minecraft mc = Minecraft.getInstance();
        SteveMod.LOGGER.info("SteveGUI.toggle -> isOpen={} screen={}", isOpen, mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
        
        if (isOpen) {
            initializeInputBox();
            mc.setScreen(new SteveOverlayScreen());
            if (inputBox != null) {
                inputBox.setFocused(true);
            }
        } else {
            if (inputBox != null) {
                inputBox = null;
            }
            if (mc.screen instanceof SteveOverlayScreen) {
                mc.setScreen(null);
            }
        }
    }

    public static boolean isOpen() {
        return isOpen;
    }

    private static void initializeInputBox() {
        Minecraft mc = Minecraft.getInstance();
        if (inputBox == null) {
            inputBox = new EditBox(mc.font, 0, 0, PANEL_WIDTH - 20, 20, 
                Component.literal("Command"));
            inputBox.setMaxLength(256);
            inputBox.setHint(Component.literal("Tell Steve what to do..."));
            inputBox.setFocused(true);
        }
    }

    /**
     * Add a message to the chat history
     */
    public static void addMessage(String sender, String text, int bubbleColor, boolean isUser) {
        messages.add(new ChatMessage(sender, text, bubbleColor, isUser));
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
        // Auto-scroll to bottom on new message
        scrollOffset = 0;
    }

    /**
     * Add a user command to the history
     */
    public static void addUserMessage(String text) {
        addMessage("You", text, USER_BUBBLE_COLOR, true);
    }

    /**
     * Add a Steve response to the history
     */
    public static void addSteveMessage(String steveName, String text) {
        addMessage(steveName, text, STEVE_BUBBLE_COLOR, false);
    }

    /**
     * Add a system message to the history
     */
    public static void addSystemMessage(String text) {
        addMessage("System", text, SYSTEM_BUBBLE_COLOR, false);
    }

    public static void registerOverlayLayer() {
        AddGuiOverlayLayersEvent.BUS.addListener(SteveGUI::onAddGuiOverlayLayers);
    }

    private static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
        Identifier layerId = Identifier.fromNamespaceAndPath(SteveMod.MODID, "steve_gui");
        event.getLayeredDraw().addAbove(ForgeLayeredDraw.HOTBAR_AND_DECOS, layerId, SteveGUI::renderOverlay);
    }

    private static void renderOverlay(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (SteveConfig.ENABLE_DEBUG_OVERLAY.get()) {
            renderDebugOverlay(graphics);
        }
        renderPanel(graphics);
    }

    private static void renderDebugOverlay(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        List<SteveEntity> steves = mc.level.getEntitiesOfClass(
            SteveEntity.class,
            mc.player.getBoundingBox().inflate(96)
        );

        steves.sort(Comparator.comparing(SteveEntity::getSteveName, String.CASE_INSENSITIVE_ORDER));
        cachedDebugSteves = new ArrayList<>(steves);

        List<String> lines = new ArrayList<>();
        lines.add("Steve Debug");
        if (steves.isEmpty()) {
            lines.add("No active Steves");
        } else {
            SteveEntity selectedSteve = resolveSelectedSteve(steves);
            String selectedName = selectedSteve.getSteveName();
            int selectedIndex = Math.max(0, selectedSteveIndex);
            String selectorLine = "Select: < " + selectedName + " > (" + (selectedIndex + 1) + "/" + steves.size() + ")";
            lines.add(selectorLine);
            lines.add("Alt+←/→ to cycle");

            long nowMs = System.currentTimeMillis();
            if (selectedSteve == null || shouldRefreshSnapshot(selectedSteve, nowMs)) {
                visibleBlockSnapshot = buildVisibleBlockSnapshot(selectedSteve);
                lastSnapshotUpdateMs = nowMs;
                lastSnapshotSteveId = selectedSteve != null ? selectedSteve.getUUID() : null;
            }

            String updateAge = formatUpdateAge(nowMs, lastSnapshotUpdateMs);
            String shortUuid = selectedSteve != null ? shortUuid(selectedSteve.getUUID()) : "n/a";
            lines.add("Selected: " + selectedName + " [" + shortUuid + "] • Updated: " + updateAge);

            String snapshotLine = "Visible blocks: " + visibleBlockSnapshot;
            lines.addAll(wrapDebugLine(mc.font, snapshotLine, 220));
        }

        int maxLines = 5;
        int count = 0;
        if (!steves.isEmpty()) {
            for (SteveEntity steve : steves) {
                if (count >= maxLines) break;
                String name = steve.getSteveName();
                String status = steve.getDebugStatus();
                if (status == null || status.isBlank()) {
                    status = "Idle";
                }
                lines.add(name + ": " + status);
                count++;
            }
        }

        int padding = 4;
        int lineHeight = mc.font.lineHeight + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        int height = lineHeight * lines.size();

        int x = 6;
        int y = 6;
        graphics.fill(x - padding, y - padding, x + width + padding, y + height + padding, DEBUG_BG_COLOR);

        int yLine = y;
        for (int i = 0; i < lines.size(); i++) {
            int color = (i == 0) ? DEBUG_ACCENT_COLOR : DEBUG_TEXT_COLOR;
            graphics.drawString(mc.font, lines.get(i), x, yLine, color);
            yLine += lineHeight;
        }
    }

    public static void renderPanel(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (isOpen) {
            SteveMod.LOGGER.debug("SteveGUI.renderPanel called (open)");
        }

        if (isOpen && slideOffset > 0) {
            slideOffset = Math.max(0, slideOffset - ANIMATION_SPEED);
        } else if (!isOpen && slideOffset < PANEL_WIDTH) {
            slideOffset = Math.min(PANEL_WIDTH, slideOffset + ANIMATION_SPEED);
        }

        // Don't render if completely hidden
        if (slideOffset >= PANEL_WIDTH) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        int panelX = (int) (screenWidth - PANEL_WIDTH + slideOffset);
        int panelY = 0;
        int panelHeight = screenHeight;

        graphics.fillGradient(panelX, panelY, screenWidth, panelHeight, BACKGROUND_COLOR, BACKGROUND_COLOR);
        
        graphics.fillGradient(panelX - 2, panelY, panelX, panelHeight, BORDER_COLOR, BORDER_COLOR);

        int headerHeight = 35;
        graphics.fillGradient(panelX, panelY, screenWidth, headerHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§lSteve AI", panelX + PANEL_PADDING, panelY + 8, TEXT_COLOR);
        graphics.drawString(mc.font, "§7Press Esc to close", panelX + PANEL_PADDING, panelY + 20, 0xFF888888);

        // Message history area
        int inputAreaY = screenHeight - 80;
        int messageAreaTop = headerHeight + 5;
        int messageAreaHeight = inputAreaY - messageAreaTop - 5;
        int messageAreaBottom = messageAreaTop + messageAreaHeight;

        int totalMessageHeight = 0;
        for (ChatMessage msg : messages) {
            int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3);
            String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
            int bubbleHeight = MESSAGE_HEIGHT + 10; // bubble padding
            totalMessageHeight += bubbleHeight + 5 + 12; // message + spacing + name
        }
        maxScroll = Math.max(0, totalMessageHeight - messageAreaHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Render messages (scrollable)
        int yPos = messageAreaTop + 5;
        
        // Clip rendering to message area
        graphics.enableScissor(panelX, messageAreaTop, screenWidth, messageAreaBottom);
        
        if (messages.isEmpty()) {
            graphics.drawString(mc.font, "§7No messages yet...", 
                panelX + PANEL_PADDING, yPos, 0xFF666666);
            graphics.drawString(mc.font, "§7Type a command below!", 
                panelX + PANEL_PADDING, yPos + 12, 0xFF555555);
        } else {
            int currentY = messageAreaBottom - 5; // Start from bottom
            
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                
                int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3); // Leave space on sides
                String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
                int textWidth = mc.font.width(wrappedText);
                int textHeight = MESSAGE_HEIGHT;
                int bubbleWidth = Math.min(textWidth + 10, maxBubbleWidth);
                int bubbleHeight = textHeight + 10;
                
                int msgY = currentY - bubbleHeight + scrollOffset;
                
                if (msgY + bubbleHeight < messageAreaTop - 20 || msgY > messageAreaBottom + 20) {
                    currentY -= bubbleHeight + 5;
                    continue;
                }
                
                // Render message bubble based on sender
                if (msg.isUser) {
                    int bubbleX = screenWidth - bubbleWidth - PANEL_PADDING - 5;
                    
                    // Draw bubble background with gradient for alpha support
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    
                    // Draw sender name (small, above bubble)
                    graphics.drawString(mc.font, "§7" + msg.sender, bubbleX, msgY - 12, 0xFFCCCCCC);
                    
                    // Draw message text (white on colored bubble)
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                    
                } else {
                    int bubbleX = panelX + PANEL_PADDING;
                    
                    // Draw bubble background with gradient for alpha support
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    
                    // Draw sender name (small, above bubble)
                    graphics.drawString(mc.font, "§l" + msg.sender, bubbleX, msgY - 12, TEXT_COLOR);
                    
                    // Draw message text (white on colored bubble)
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                }
                
                currentY -= bubbleHeight + 5 + 12; // Extra space for sender name
            }
        }
        
        graphics.disableScissor();
        
        if (maxScroll > 0) {
            int scrollBarHeight = Math.max(20, (messageAreaHeight * messageAreaHeight) / (maxScroll + messageAreaHeight));
            int scrollBarY = messageAreaTop + (int)((messageAreaHeight - scrollBarHeight) * (1.0f - (float)scrollOffset / maxScroll));
            graphics.fill(screenWidth - 4, scrollBarY, screenWidth - 2, scrollBarY + scrollBarHeight, 0xFF888888);
        }

        // Command input area (bottom) with gradient for alpha support
        graphics.fillGradient(panelX, inputAreaY, screenWidth, screenHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§7Command:", panelX + PANEL_PADDING, inputAreaY + 10, 0xFF888888);

        if (inputBox != null && isOpen) {
            inputBox.setX(panelX + PANEL_PADDING);
            inputBox.setY(inputAreaY + 25);
            inputBox.setWidth(PANEL_WIDTH - (PANEL_PADDING * 2));
            inputBox.render(graphics, (int)mc.mouseHandler.xpos(), (int)mc.mouseHandler.ypos(),
                mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        }

        graphics.drawString(mc.font, "§8Enter: Send | ↑↓: History | Scroll: Messages", 
            panelX + PANEL_PADDING, screenHeight - 15, 0xFF555555);
        
    }

    /**
     * Simple word wrap for text
     */
    private static String wrapText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        // Simple truncation for now
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(i));
            if (font.width(result.toString() + "...") >= maxWidth) {
                return result.substring(0, result.length() - 3) + "...";
            }
        }
        return result.toString();
    }

    public static boolean handleKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!isOpen || inputBox == null) return false;

        if (handleDebugSelectorKeys(keyCode)) {
            return true;
        }

        Minecraft mc = Minecraft.getInstance();
        
        // Escape key - close panel
        if (keyCode == 256) { // ESC
            toggle();
            return true;
        }
        
        // Enter key - send command
        if (keyCode == 257) {
            String command = inputBox.getValue().trim();
            if (!command.isEmpty()) {
                sendCommand(command);
                inputBox.setValue("");
                historyIndex = -1;
            }
            return true;
        }

        // Arrow up - previous command
        if (keyCode == 265 && !commandHistory.isEmpty()) { // UP
            if (historyIndex < commandHistory.size() - 1) {
                historyIndex++;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            }
            return true;
        }

        // Arrow down - next command
        if (keyCode == 264) { // DOWN
            if (historyIndex > 0) {
                historyIndex--;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            } else if (historyIndex == 0) {
                historyIndex = -1;
                inputBox.setValue("");
            }
            return true;
        }

        // Backspace, Delete, Home, End, Left, Right - pass to input box
        if (keyCode == 259 || keyCode == 261 || keyCode == 268 || keyCode == 269 || 
            keyCode == 263 || keyCode == 262) {
            inputBox.keyPressed(new net.minecraft.client.input.KeyEvent(keyCode, scanCode, modifiers));
            return true;
        }

        return true; // Consume all keys to prevent game controls
    }

    public static boolean handleCharTyped(char codePoint, int modifiers) {
        if (isOpen && inputBox != null) {
            inputBox.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, modifiers));
            return true; // Consumed
        }
        return false;
    }

    public static void handleMouseClick(double mouseX, double mouseY, int button) {
        if (!isOpen) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        if (inputBox != null) {
            int inputAreaY = screenHeight - 80;
            if (mouseY >= inputAreaY + 25 && mouseY <= inputAreaY + 45) {
                inputBox.setFocused(true);
            } else {
                inputBox.setFocused(false);
            }
            inputBox.onClick(
                new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY,
                    new net.minecraft.client.input.MouseButtonInfo(button, 0)),
                false
            );
        }
    }

    public static void handleMouseScroll(double scrollDelta) {
        if (!isOpen) return;
        
        int scrollAmount = (int)(scrollDelta * 3 * MESSAGE_HEIGHT);
        scrollOffset -= scrollAmount;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static void sendCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        
        commandHistory.add(command);
        if (commandHistory.size() > 50) {
            commandHistory.remove(0);
        }
        
        addUserMessage(command);

        String commandLower = command.trim().toLowerCase();
        if (commandLower.equals("screenshot") || commandLower.equals("pov screenshot") || commandLower.equals("pov") ||
            commandLower.equals("cheat screenshot") || commandLower.equals("cheat pov") ||
            commandLower.equals("cheat pov screenshot")) {
            if (StevePovScreenshot.requestNearestSteve()) {
                addSystemMessage("Capturing Steve POV screenshot...");
            } else {
                addSystemMessage("No Steve agents found for POV screenshot.");
            }
            return;
        }

        if (command.toLowerCase().startsWith("spawn ")) {
            String name = command.substring(6).trim();
            if (name.isEmpty()) name = "Steve";
            if (mc.player != null) {
                mc.player.connection.sendCommand("steve spawn " + name);
                addSystemMessage("Spawning Steve agent: " + name);
            }
            return;
        }

        List<String> targetSteves = parseTargetSteves(command);
        
        if (targetSteves.isEmpty()) {
            var steves = SteveMod.getSteveManager().getAllSteves();
            if (!steves.isEmpty()) {
                targetSteves.add(steves.iterator().next().getSteveName());
            } else {
                // No Steves available
                addSystemMessage("No Steve agents found! Use 'spawn <name>' to create one.");
                return;
            }
        }

        // Send command to all targeted Steves
        if (mc.player != null) {
            for (String steveName : targetSteves) {
                mc.player.connection.sendCommand("steve tell " + steveName + " " + command);
            }
            
            if (targetSteves.size() > 1) {
                addSystemMessage("→ " + String.join(", ", targetSteves) + ": " + command);
            } else {
                addSystemMessage("→ " + targetSteves.get(0) + ": " + command);
            }
        }
    }
    
    private static List<String> parseTargetSteves(String command) {
        List<String> targets = new ArrayList<>();
        String commandLower = command.toLowerCase();
        
        if (commandLower.startsWith("all steves ") || commandLower.startsWith("all ") || 
            commandLower.startsWith("everyone ") || commandLower.startsWith("everybody ")) {
            var allSteves = SteveMod.getSteveManager().getAllSteves();
            for (SteveEntity steve : allSteves) {
                targets.add(steve.getSteveName());
            }
            return targets;
        }
        
        var allSteves = SteveMod.getSteveManager().getAllSteves();
        List<String> availableNames = new ArrayList<>();
        for (SteveEntity steve : allSteves) {
            availableNames.add(steve.getSteveName().toLowerCase());
        }
        
        String[] parts = command.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            String firstWord = trimmed.split(" ")[0].toLowerCase();
            
            if (availableNames.contains(firstWord)) {
                for (SteveEntity steve : allSteves) {
                    if (steve.getSteveName().equalsIgnoreCase(firstWord)) {
                        targets.add(steve.getSteveName());
                        break;
                    }
                }
            }
        }
        
        return targets;
    }

    public static void tick() {
        if (isOpen && inputBox != null) {
            // Auto-focus input box when panel is open
            if (!inputBox.isFocused()) {
                inputBox.setFocused(true);
            }
        }
    }

    private static List<SteveEntity> cachedDebugSteves = new ArrayList<>();
    private static UUID selectedSteveId;
    private static int selectedSteveIndex = 0;
    private static UUID lastSnapshotSteveId;
    private static String visibleBlockSnapshot = "none";
    private static long lastSnapshotUpdateMs = 0L;

    private static boolean handleDebugSelectorKeys(int keyCode) {
        if (!SteveConfig.ENABLE_DEBUG_OVERLAY.get()) {
            return false;
        }
        if (!Screen.hasAltDown()) {
            return false;
        }
        if (keyCode == 263) { // Left
            cycleSelectedSteve(-1);
            return true;
        }
        if (keyCode == 262) { // Right
            cycleSelectedSteve(1);
            return true;
        }
        return false;
    }

    private static void cycleSelectedSteve(int direction) {
        if (cachedDebugSteves.isEmpty()) {
            selectedSteveId = null;
            selectedSteveIndex = 0;
            return;
        }

        int currentIndex = 0;
        if (selectedSteveId != null) {
            for (int i = 0; i < cachedDebugSteves.size(); i++) {
                if (cachedDebugSteves.get(i).getUUID().equals(selectedSteveId)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        int nextIndex = (currentIndex + direction + cachedDebugSteves.size()) % cachedDebugSteves.size();
        SteveEntity nextSteve = cachedDebugSteves.get(nextIndex);
        selectedSteveId = nextSteve.getUUID();
        selectedSteveIndex = nextIndex;
        lastSnapshotUpdateMs = 0L;
    }

    private static SteveEntity resolveSelectedSteve(List<SteveEntity> steves) {
        if (steves.isEmpty()) {
            selectedSteveId = null;
            selectedSteveIndex = 0;
            return null;
        }

        if (selectedSteveId != null) {
            for (int i = 0; i < steves.size(); i++) {
                SteveEntity steve = steves.get(i);
                if (steve.getUUID().equals(selectedSteveId)) {
                    selectedSteveIndex = i;
                    return steve;
                }
            }
        }

        SteveEntity fallback = steves.get(0);
        selectedSteveId = fallback.getUUID();
        selectedSteveIndex = 0;
        return fallback;
    }

    private static boolean shouldRefreshSnapshot(SteveEntity steve, long nowMs) {
        if (steve == null) {
            return false;
        }
        if (lastSnapshotUpdateMs == 0L) {
            return true;
        }
        if (lastSnapshotSteveId == null || !lastSnapshotSteveId.equals(steve.getUUID())) {
            return true;
        }
        return nowMs - lastSnapshotUpdateMs >= SNAPSHOT_REFRESH_MS;
    }

    private static String buildVisibleBlockSnapshot(SteveEntity steve) {
        if (steve == null) {
            return "none";
        }
        Level level = steve.level();
        Vec3 eye = steve.getEyePosition();
        Vec3 look = steve.getLookAngle().normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = look.cross(up);
        if (right.lengthSqr() < 1.0E-4) {
            right = new Vec3(1, 0, 0);
        }
        right = right.normalize();
        Vec3 trueUp = right.cross(look).normalize();

        double[] offsets = {-0.2, 0.0, 0.2};
        int range = 16;
        Set<String> visibleBlocks = new LinkedHashSet<>();
        for (double xOffset : offsets) {
            for (double yOffset : offsets) {
                Vec3 direction = look.add(right.scale(xOffset)).add(trueUp.scale(yOffset)).normalize();
                for (int distance = 1; distance <= range; distance++) {
                    Vec3 sample = eye.add(direction.scale(distance));
                    BlockPos pos = BlockPos.containing(sample);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        visibleBlocks.add(state.getBlock().getName().getString());
                        break;
                    }
                }
            }
        }

        if (visibleBlocks.isEmpty()) {
            return "none";
        }

        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (String name : visibleBlocks) {
            if (count > 0) {
                summary.append(", ");
            }
            summary.append(name);
            count++;
            if (count >= 6) {
                break;
            }
        }
        return summary.toString();
    }

    private static String formatUpdateAge(long nowMs, long updateMs) {
        if (updateMs <= 0L) {
            return "never";
        }
        long deltaMs = Math.max(0L, nowMs - updateMs);
        if (deltaMs < 1000L) {
            return deltaMs + "ms ago";
        }
        double seconds = deltaMs / 1000.0;
        if (seconds < 60.0) {
            return String.format("%.1fs ago", seconds);
        }
        double minutes = seconds / 60.0;
        return String.format("%.1fm ago", minutes);
    }

    private static String shortUuid(UUID uuid) {
        if (uuid == null) {
            return "n/a";
        }
        String raw = uuid.toString();
        int dash = raw.indexOf('-');
        return dash > 0 ? raw.substring(0, dash) : raw;
    }

    private static List<String> wrapDebugLine(net.minecraft.client.gui.Font font, String line, int maxWidth) {
        List<String> wrapped = new ArrayList<>();
        if (font.width(line) <= maxWidth) {
            wrapped.add(line);
            return wrapped;
        }

        String[] words = line.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
                continue;
            }
            String candidate = current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                current.append(" ").append(word);
            } else {
                wrapped.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (!current.isEmpty()) {
            wrapped.add(current.toString());
        }
        return wrapped;
    }
}
