package com.steve.ai.llm.resilience;

import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.async.LLMResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMFallbackHandlerTest {
    @Test
    void fallbackFeedPromptParsesIntoFeedTask() {
        LLMFallbackHandler handler = new LLMFallbackHandler();
        LLMResponse response = handler.generateFallback("please feed the cows", null);
        ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(response.getContent());

        assertNotNull(parsed);
        assertNotNull(parsed.getTasks());
        assertTrue(parsed.getTasks().stream().anyMatch(t -> "feed".equals(t.getAction())));
    }

    @Test
    void fallbackFarmPromptParsesIntoFarmTask() {
        LLMFallbackHandler handler = new LLMFallbackHandler();
        LLMResponse response = handler.generateFallback("start farming wheat", null);
        ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(response.getContent());

        assertNotNull(parsed);
        assertNotNull(parsed.getTasks());
        assertTrue(parsed.getTasks().stream().anyMatch(t -> "farm".equals(t.getAction())));
    }

    @Test
    void fallbackPatternCountIsStable() {
        LLMFallbackHandler handler = new LLMFallbackHandler();
        assertEquals(8, handler.getPatternCount());
    }

    @Test
    void fallbackMovementWithCoordinatesUsesPathfindTarget() {
        LLMFallbackHandler handler = new LLMFallbackHandler();
        LLMResponse response = handler.generateFallback("go to 120 64 -40 across the river", null);
        ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(response.getContent());

        assertNotNull(parsed);
        assertNotNull(parsed.getTasks());
        assertTrue(parsed.getTasks().stream().anyMatch(t ->
            "pathfind".equals(t.getAction())
                && t.getIntParameter("x", Integer.MIN_VALUE) == 120
                && t.getIntParameter("y", Integer.MIN_VALUE) == 64
                && t.getIntParameter("z", Integer.MIN_VALUE) == -40
        ));
    }
}
