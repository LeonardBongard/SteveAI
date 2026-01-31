package com.steve.ai.llm;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import com.steve.ai.memory.VisibleBlockEntry;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.Locale;

public class PromptBuilder {
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI agent. Respond ONLY with valid JSON, no extra text.
            
            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}
            
            ACTIONS:
            - attack: {"target": "hostile"} (for any mob/monster)
            - build: {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald, stone, dirt, etc.)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}
            
            RULES:
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. STRUCTURE OPTIONS: house, oldhouse, powerplant, castle, tower, barn, modern
            3. house/oldhouse/powerplant = pre-built NBT templates (auto-size)
            4. castle/tower/barn/modern = procedural (castle=14x10x14, tower=6x6x16, barn=12x8x14)
            5. Use 2-3 block types: oak_planks, cobblestone, glass_pane, stone_bricks
            6. NO extra pathfind tasks unless explicitly requested
            7. Keep reasoning under 15 words
            8. COLLABORATIVE BUILDING: Multiple Steves can work on same structure simultaneously
            9. MINING: Can mine any ore (iron, diamond, coal, etc) or block type
            10. DESTROY/DEMOLISH: To destroy or tear down structures, use "mine" action with appropriate block type (e.g., {"action": "mine", "parameters": {"block": "oak_planks", "quantity": 50}} to tear down a wooden house)
            
            EXAMPLES (copy these formats exactly):
            
            Input: "build a house"
            {"reasoning": "Building standard house near player", "plan": "Construct house", "tasks": [{"action": "build", "parameters": {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}}]}
            
            Input: "get me iron"
            {"reasoning": "Mining iron ore for player", "plan": "Mine iron", "tasks": [{"action": "mine", "parameters": {"block": "iron", "quantity": 16}}]}
            
            Input: "find diamonds"
            {"reasoning": "Searching for diamond ore", "plan": "Mine diamonds", "tasks": [{"action": "mine", "parameters": {"block": "diamond", "quantity": 8}}]}
            
            Input: "kill mobs" 
            {"reasoning": "Hunting hostile creatures", "plan": "Attack hostiles", "tasks": [{"action": "attack", "parameters": {"target": "hostile"}}]}
            
            Input: "murder creeper"
            {"reasoning": "Targeting creeper", "plan": "Attack creeper", "tasks": [{"action": "attack", "parameters": {"target": "creeper"}}]}
            
            Input: "follow me"
            {"reasoning": "Player needs me", "plan": "Follow player", "tasks": [{"action": "follow", "parameters": {"player": "USE_NEARBY_PLAYER_NAME"}}]}
            
            Input: "destroy the house"
            {"reasoning": "Demolishing house structure", "plan": "Mine house blocks", "tasks": [{"action": "mine", "parameters": {"block": "oak_planks", "quantity": 50}}]}
            
            CRITICAL: Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON.
            """;
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();
        
        // Give agents FULL situational awareness
        prompt.append("=== YOUR SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");
        prompt.append("Nearby Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Nearby Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Nearby Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append("\n");

        Block targetBlock = extractTargetBlock(command);
        if (targetBlock != Blocks.AIR) {
            List<VisibleBlockEntry> entries = steve.getMemory().getVisibleBlocks();
            if (!entries.isEmpty()) {
                prompt.append("Visible ").append(targetBlock.getName().getString())
                    .append(" (nearest 20): ")
                    .append(formatVisiblePositions(entries, targetBlock))
                    .append("\n");
            }
        }
        
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");
        
        prompt.append("\n=== YOUR RESPONSE (with reasoning) ===\n");
        
        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        return "[empty]";
    }

    private static Block extractTargetBlock(String command) {
        String cleaned = command.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9:_\\s]", " ")
            .trim();
        if (cleaned.isEmpty()) {
            return Blocks.AIR;
        }

        String[] tokens = cleaned.split("\\s+");
        String[] keywords = {"mine", "get", "gather", "collect", "find", "harvest", "dig"};

        for (int i = 0; i < tokens.length; i++) {
            for (String keyword : keywords) {
                if (!tokens[i].equals(keyword)) {
                    continue;
                }
                Block block = tryParseBlockToken(tokens, i + 1);
                if (block != Blocks.AIR) {
                    return block;
                }
            }
        }

        Block fallback = tryParseBlockToken(tokens, tokens.length - 1);
        return fallback;
    }

    private static Block tryParseBlockToken(String[] tokens, int index) {
        if (index < 0 || index >= tokens.length) {
            return Blocks.AIR;
        }
        String candidate = tokens[index];
        Block block = ActionUtils.parseBlock(candidate);
        if (block != Blocks.AIR) {
            return block;
        }
        if (index + 1 < tokens.length) {
            block = ActionUtils.parseBlock(candidate + "_" + tokens[index + 1]);
            if (block != Blocks.AIR) {
                return block;
            }
        }
        return Blocks.AIR;
    }

    private static String formatVisiblePositions(List<VisibleBlockEntry> entries, Block targetBlock) {
        String targetId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(targetBlock).toString();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (VisibleBlockEntry entry : entries) {
            if (!targetId.equals(entry.blockId())) {
                continue;
            }
            if (count > 0) {
                sb.append("; ");
            }
            BlockPos pos = entry.position();
            sb.append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ());
            count++;
            if (count >= 20) {
                break;
            }
        }
        return count == 0 ? "none" : sb.toString();
    }
}
