package com.steve.ai.llm;

import com.steve.ai.action.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResponseParserTest {

    @Test
    void normalizesCraftRecipeAliasToItem() {
        String json = """
            {"reasoning":"x","plan":"y","tasks":[{"action":"craft","parameters":{"recipe":"wooden_pickaxe","quantity":1}}]}
            """;

        ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(json);
        assertNotNull(parsed);
        Task task = parsed.getTasks().get(0);
        assertEquals("craft", task.getAction());
        assertEquals("wooden_pickaxe", task.getStringParameter("item"));
    }

    @Test
    void rewritesBuildWorkbenchToCraftingTableCraft() {
        String json = """
            {"reasoning":"x","plan":"y","tasks":[{"action":"build","parameters":{"structure":"work bench","quantity":1}}]}
            """;

        ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(json);
        assertNotNull(parsed);
        Task task = parsed.getTasks().get(0);
        assertEquals("craft", task.getAction());
        assertEquals("crafting_table", task.getStringParameter("item"));
        assertEquals(1, task.getIntParameter("quantity", 0));
    }
}
