package com.steve.ai.llm.resilience;

import com.steve.ai.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Fallback handler that generates pattern-based responses when LLM calls fail.
 *
 * <p>Provides graceful degradation when all LLM providers are unavailable.
 * Uses simple pattern matching to recognize common Minecraft commands and
 * generate appropriate action responses.</p>
 *
 * <p><b>When is this used?</b></p>
 * <ul>
 *   <li>Circuit breaker is OPEN (provider experiencing failures)</li>
 *   <li>All retry attempts exhausted</li>
 *   <li>Rate limiter rejects request</li>
 *   <li>Network is completely unavailable</li>
 * </ul>
 *
 * <p><b>Design Philosophy:</b></p>
 * <ul>
 *   <li>Something is better than nothing - basic functionality continues</li>
 *   <li>Conservative defaults - prefer safe actions (wait) over risky ones</li>
 *   <li>Transparency - responses indicate they're from fallback system</li>
 * </ul>
 *
 * <p><b>Supported Patterns:</b></p>
 * <ul>
 *   <li><b>mine:</b> Matches "mine", "dig", "collect ore"</li>
 *   <li><b>build:</b> Matches "build", "construct", "create house"</li>
 *   <li><b>attack:</b> Matches "attack", "fight", "kill"</li>
 *   <li><b>follow:</b> Matches "follow", "come", "follow me"</li>
 *   <li><b>move:</b> Matches "go to", "move to", "walk"</li>
 *   <li><b>default:</b> Matches nothing - returns "wait" action</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class LLMFallbackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMFallbackHandler.class);
    private static final Pattern COORDINATE_TRIPLET =
        Pattern.compile("(-?\\d+)\\s*(?:,|\\s)\\s*(-?\\d+)\\s*(?:,|\\s)\\s*(-?\\d+)");
    private static final Pattern MOVEMENT_INTENT =
        Pattern.compile("(?i).*(go to|move to|walk to|travel|path|navigate|cross|swim).*");
    private static final Pattern WATER_INTENT =
        Pattern.compile("(?i).*(river|water|swim|ocean|lake|across).*");

    private static final List<PatternRule> PATTERN_RESPONSES = List.of(
        // Feeding/farming first so they are not swallowed by generic gather/mine.
        new PatternRule(
            Pattern.compile("(?i).*(feed|breed|animals?|cows?|pigs?|chickens?|sheep|goats?|rabbits?).*"),
            "{\"reasoning\":\"fallback feed intent\",\"plan\":\"feed animals\",\"tasks\":[{\"action\":\"feed\",\"parameters\":{\"species\":\"cow\",\"quantity\":2}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(farm|plant|harvest|crop|wheat|carrot|potato).*"),
            "{\"reasoning\":\"fallback farm intent\",\"plan\":\"farm crops\",\"tasks\":[{\"action\":\"farm\",\"parameters\":{\"crop\":\"wheat\",\"quantity\":12}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(mine|dig|collect|gather|ore|diamond|iron|coal|stone).*"),
            "{\"reasoning\":\"fallback mining intent\",\"plan\":\"mine resources\",\"tasks\":[{\"action\":\"mine\",\"parameters\":{\"block\":\"iron\",\"quantity\":10}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(build|construct|create|make).*(house|home|shelter|structure|base).*"),
            "{\"reasoning\":\"fallback build intent\",\"plan\":\"build house\",\"tasks\":[{\"action\":\"build\",\"parameters\":{\"structure\":\"house\",\"blocks\":[\"oak_planks\",\"cobblestone\",\"glass_pane\"],\"dimensions\":[9,6,9]}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(attack|fight|kill|destroy|hostile|monster|zombie|skeleton|creeper).*"),
            "{\"reasoning\":\"fallback combat intent\",\"plan\":\"attack hostiles\",\"tasks\":[{\"action\":\"attack\",\"parameters\":{\"target\":\"hostile\"}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(follow|come|here|with me|accompany).*"),
            "{\"reasoning\":\"fallback follow intent\",\"plan\":\"follow player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(go to|move to|walk to|travel|path|navigate).*"),
            "{\"reasoning\":\"fallback movement intent\",\"plan\":\"move near current position\",\"tasks\":[{\"action\":\"pathfind\",\"parameters\":{\"x\":0,\"y\":64,\"z\":0}}]}"
        ),
        new PatternRule(
            Pattern.compile("(?i).*(stop|halt|cancel|wait|pause|stay).*"),
            "{\"reasoning\":\"fallback stop intent\",\"plan\":\"hold position\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}"
        )
    );

    // Default response when no pattern matches
    private static final String DEFAULT_RESPONSE =
        "{\"reasoning\":\"fallback default\",\"plan\":\"follow player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}";

    /**
     * Generates a fallback response based on pattern matching.
     *
     * <p>Analyzes the prompt text to identify the user's intent and returns
     * a pre-configured action response. If no pattern matches, returns a
     * safe "wait" action.</p>
     *
     * @param prompt Original prompt that failed
     * @param error  The error that triggered the fallback (for logging)
     * @return LLMResponse containing pattern-matched action or default wait action
     */
    public LLMResponse generateFallback(String prompt, Throwable error) {
        LOGGER.warn("Generating fallback response for prompt: '{}' (error: {})",
            truncatePrompt(prompt, 50),
            error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown");

        String coordinateMovement = buildCoordinateMovementFallback(prompt);
        if (coordinateMovement != null) {
            LOGGER.info("Fallback response generated (matched: coordinate-movement)");
            return LLMResponse.builder()
                .content(coordinateMovement)
                .model("fallback-pattern-matcher")
                .providerId("fallback")
                .latencyMs(0)
                .tokensUsed(0)
                .fromCache(false)
                .build();
        }

        // Try to match against known patterns
        String responseContent = matchPattern(prompt);
        String matchedPattern = responseContent.equals(DEFAULT_RESPONSE) ? "default" : "pattern-match";

        LOGGER.info("Fallback response generated (matched: {})", matchedPattern);

        return LLMResponse.builder()
            .content(responseContent)
            .model("fallback-pattern-matcher")
            .providerId("fallback")
            .latencyMs(0)
            .tokensUsed(0)
            .fromCache(false)
            .build();
    }

    /**
     * Matches the prompt against known patterns.
     *
     * @param prompt The prompt to analyze
     * @return Matching response JSON or default response
     */
    private String matchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return DEFAULT_RESPONSE;
        }

        String lowerPrompt = prompt.toLowerCase();

        for (PatternRule rule : PATTERN_RESPONSES) {
            if (rule.pattern.matcher(lowerPrompt).matches()) {
                LOGGER.debug("Matched pattern: {}", rule.pattern.pattern());
                return rule.responseJson;
            }
        }

        LOGGER.debug("No pattern matched, using default response");
        return DEFAULT_RESPONSE;
    }

    /**
     * Truncates a prompt for logging purposes.
     *
     * @param prompt Prompt to truncate
     * @param maxLength Maximum length
     * @return Truncated prompt with "..." if needed
     */
    private String truncatePrompt(String prompt, int maxLength) {
        if (prompt == null) {
            return "[null]";
        }
        if (prompt.length() <= maxLength) {
            return prompt;
        }
        return prompt.substring(0, maxLength) + "...";
    }

    /**
     * Checks if a prompt would match any known pattern.
     *
     * <p>Useful for testing and debugging.</p>
     *
     * @param prompt The prompt to check
     * @return true if a pattern matches, false if would use default
     */
    public boolean wouldMatchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }

        String lowerPrompt = prompt.toLowerCase();
        return PATTERN_RESPONSES.stream()
            .anyMatch(rule -> rule.pattern.matcher(lowerPrompt).matches());
    }

    /**
     * Returns the number of registered patterns.
     *
     * @return Pattern count
     */
    public int getPatternCount() {
        return PATTERN_RESPONSES.size();
    }

    private String buildCoordinateMovementFallback(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String lower = prompt.toLowerCase();
        if (!MOVEMENT_INTENT.matcher(lower).matches() && !WATER_INTENT.matcher(lower).matches()) {
            return null;
        }
        Matcher matcher = COORDINATE_TRIPLET.matcher(lower);
        if (!matcher.find()) {
            return null;
        }
        int x = parseInt(matcher.group(1), 0);
        int y = parseInt(matcher.group(2), 64);
        int z = parseInt(matcher.group(3), 0);

        String reasoning = WATER_INTENT.matcher(lower).matches()
            ? "fallback water traversal intent"
            : "fallback movement intent";
        String plan = WATER_INTENT.matcher(lower).matches()
            ? "travel across water to coordinates"
            : "move to coordinates";
        return "{\"reasoning\":\"" + reasoning + "\",\"plan\":\"" + plan
            + "\",\"tasks\":[{\"action\":\"pathfind\",\"parameters\":{\"x\":" + x
            + ",\"y\":" + y + ",\"z\":" + z + "}}]}";
    }

    private int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record PatternRule(Pattern pattern, String responseJson) {
    }
}
