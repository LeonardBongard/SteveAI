package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.async.*;
import com.steve.ai.llm.resilience.LLMFallbackHandler;
import com.steve.ai.llm.resilience.ResilientLLMClient;
import com.steve.ai.memory.WorldKnowledge;
import com.steve.ai.validation.MinecraftLegalityChecker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

public class TaskPlanner {
    // Legacy synchronous clients (for backward compatibility)
    private final OpenAIClient openAIClient;
    private final GeminiClient geminiClient;
    private final GroqClient groqClient;
    private final OllamaClient ollamaClient;

    // NEW: Async resilient clients
    private final AsyncLLMClient asyncOpenAIClient;
    private final AsyncLLMClient asyncGroqClient;
    private final AsyncLLMClient asyncGeminiClient;
    private final AsyncLLMClient asyncOllamaClient;
    private final LLMCache llmCache;
    private final LLMFallbackHandler fallbackHandler;

    private static final String GROQ_DEFAULT_MODEL = "llama-3.1-8b-instant";
    private static final String GEMINI_DEFAULT_MODEL = "gemini-1.5-flash";
    private static final Pattern PICKAXE_INTENT =
        Pattern.compile(".*\\b(craft|make|create|get|obtain|build)\\b.*\\bpickaxe\\b.*", Pattern.CASE_INSENSITIVE);

    public TaskPlanner() {
        // Legacy clients
        this.openAIClient = new OpenAIClient();
        this.geminiClient = new GeminiClient();
        this.groqClient = new GroqClient();
        this.ollamaClient = new OllamaClient();

        // Initialize async infrastructure
        this.llmCache = new LLMCache();
        this.fallbackHandler = new LLMFallbackHandler();

        // Initialize async clients with resilience wrappers
        String apiKey = SteveConfig.OPENAI_API_KEY.get();
        String model = SteveConfig.OPENAI_MODEL.get();
        int maxTokens = SteveConfig.MAX_TOKENS.get();
        double temperature = SteveConfig.TEMPERATURE.get();

        // Create base async clients
        AsyncLLMClient baseOpenAI = new AsyncOpenAIClient(apiKey, model, maxTokens, temperature);
        AsyncLLMClient baseGroq = new AsyncGroqClient(apiKey, GROQ_DEFAULT_MODEL, 500, temperature);
        AsyncLLMClient baseGemini = new AsyncGeminiClient(apiKey, GEMINI_DEFAULT_MODEL, maxTokens, temperature);
        AsyncLLMClient baseOllama = new AsyncOllamaClient(
            SteveConfig.OLLAMA_BASE_URL.get(),
            SteveConfig.OLLAMA_API_KEY.get(),
            SteveConfig.OLLAMA_MODEL.get(),
            maxTokens,
            temperature
        );

        // Wrap with resilience patterns
        this.asyncOpenAIClient = new ResilientLLMClient(baseOpenAI, llmCache, fallbackHandler);
        this.asyncGroqClient = new ResilientLLMClient(baseGroq, llmCache, fallbackHandler);
        this.asyncGeminiClient = new ResilientLLMClient(baseGemini, llmCache, fallbackHandler);
        this.asyncOllamaClient = new ResilientLLMClient(baseOllama, llmCache, fallbackHandler);

        SteveMod.LOGGER.info("TaskPlanner initialized with async resilient clients");
    }

    public ResponseParser.ParsedResponse planTasks(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldKnowledge);
            
            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("Requesting AI plan for Steve '{}' using {}: {}", steve.getSteveName(), provider, command);
            
            String response = getAIResponse(provider, systemPrompt, userPrompt);
            
            if (response == null) {
                SteveMod.LOGGER.error("Failed to get AI response for command: {}", command);
                return null;
            }            ResponseParser.ParsedResponse parsedResponse = ResponseParser.parseAIResponse(response);
            
            if (parsedResponse == null) {
                SteveMod.LOGGER.error("Failed to parse AI response");
                return null;
            }
            
            ResponseParser.ParsedResponse guarded = applyCommandTaskGuards(command, parsedResponse);
            SteveMod.LOGGER.info("Plan: {} ({} tasks)", guarded.getPlan(), guarded.getTasks().size());
            return guarded;
            
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error planning tasks", e);
            return null;
        }
    }

    private String getAIResponse(String provider, String systemPrompt, String userPrompt) {
        String response = switch (provider) {
            case "groq" -> groqClient.sendRequest(systemPrompt, userPrompt);
            case "gemini" -> geminiClient.sendRequest(systemPrompt, userPrompt);
            case "openai" -> openAIClient.sendRequest(systemPrompt, userPrompt);
            case "ollama" -> ollamaClient.sendRequest(systemPrompt, userPrompt);
            default -> {
                SteveMod.LOGGER.warn("Unknown AI provider '{}', using Groq", provider);
                yield groqClient.sendRequest(systemPrompt, userPrompt);
            }
        };

        if (response == null && !provider.equals("groq")) {
            SteveMod.LOGGER.warn("{} failed, trying Groq as fallback", provider);
            response = groqClient.sendRequest(systemPrompt, userPrompt);
        }

        return response;
    }

    /**
     * Asynchronously plans tasks for Steve using the configured LLM provider.
     *
     * <p>This method returns immediately with a CompletableFuture, allowing the game thread
     * to continue without blocking. The actual LLM call is executed on a separate thread pool
     * with full resilience patterns (circuit breaker, retry, rate limiting, caching).</p>
     *
     * <p><b>Non-blocking:</b> Game thread is never blocked</p>
     * <p><b>Resilient:</b> Automatic retry, circuit breaker, fallback on failure</p>
     * <p><b>Cached:</b> Repeated prompts may hit cache (40-60% hit rate)</p>
     *
     * @param steve   The Steve entity making the request
     * @param command The user command to plan
     * @return CompletableFuture that completes with the parsed response, or null on failure
     */
    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldKnowledge);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("[Async] Requesting AI plan for Steve '{}' using {}: {}",
                steve.getSteveName(), provider, command);

            // Build params map
            String model = getModelForProvider(provider);

            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", model,
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            // Select async client based on provider
            AsyncLLMClient client = getAsyncClient(provider);

            // Execute async request
            return client.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        SteveMod.LOGGER.error("[Async] Empty response from LLM");
                        return null;
                    }

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        SteveMod.LOGGER.error("[Async] Failed to parse AI response");
                        return null;
                    }

                    ResponseParser.ParsedResponse guarded = applyCommandTaskGuards(command, parsed);
                    SteveMod.LOGGER.info("[Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        guarded.getPlan(),
                        guarded.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());

                    return guarded;
                })
                .exceptionally(throwable -> {
                    SteveMod.LOGGER.error("[Async] Error planning tasks: {}", throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            SteveMod.LOGGER.error("[Async] Error setting up task planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Returns the appropriate async client based on provider config.
     *
     * @param provider Provider name ("openai", "groq", "gemini", "ollama")
     * @return Resilient async client
     */
    private AsyncLLMClient getAsyncClient(String provider) {
        return switch (provider) {
            case "openai" -> asyncOpenAIClient;
            case "gemini" -> asyncGeminiClient;
            case "groq" -> asyncGroqClient;
            case "ollama" -> asyncOllamaClient;
            default -> {
                SteveMod.LOGGER.warn("[Async] Unknown provider '{}', using Groq", provider);
                yield asyncGroqClient;
            }
        };
    }

    private String getModelForProvider(String provider) {
        return switch (provider) {
            case "ollama" -> SteveConfig.OLLAMA_MODEL.get();
            case "groq" -> GROQ_DEFAULT_MODEL;
            case "gemini" -> GEMINI_DEFAULT_MODEL;
            case "openai" -> SteveConfig.OPENAI_MODEL.get();
            default -> SteveConfig.OPENAI_MODEL.get();
        };
    }

    /**
     * Returns the LLM cache for monitoring.
     *
     * @return LLM cache instance
     */
    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * Checks if the specified provider's async client is healthy.
     *
     * @param provider Provider name
     * @return true if healthy (circuit breaker not OPEN)
     */
    public boolean isProviderHealthy(String provider) {
        return getAsyncClient(provider).isHealthy();
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();
        java.util.Map<String, Object> p = task.getParameters();
        boolean hasQuantity = p.containsKey("quantity") || p.containsKey("count");

        boolean schemaValid = switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> (p.containsKey("block") || p.containsKey("blockType")) && hasQuantity;
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> (p.containsKey("item") || p.containsKey("recipe")) && hasQuantity;
            case "smelt" -> p.containsKey("item") && hasQuantity;
            case "attack" -> task.hasParameters("target");
            case "feed" -> p.containsKey("species") && hasQuantity;
            case "follow" -> p.containsKey("player") || p.containsKey("playerName");
            case "gather" -> p.containsKey("resource") && hasQuantity;
            case "farm" -> p.containsKey("crop") && hasQuantity;
            case "retrieve_chest" -> p.containsKey("item") && hasQuantity;
            case "build" -> task.hasParameters("structure", "blocks", "dimensions");
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
        if (!schemaValid) {
            return false;
        }

        MinecraftLegalityChecker.CheckResult legal = MinecraftLegalityChecker.validateTaskDefinition(task);
        if (!legal.legal()) {
            SteveMod.LOGGER.warn("[LEGALITY] Rejected task action={} reason={} params={}", action, legal.reason(), p);
            return false;
        }
        return true;
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }

    private ResponseParser.ParsedResponse applyCommandTaskGuards(String command, ResponseParser.ParsedResponse parsed) {
        if (parsed == null) {
            return null;
        }
        String targetPickaxe = detectTargetPickaxe(command);
        if (targetPickaxe == null) {
            return parsed;
        }

        List<Task> tasks = parsed.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            List<Task> guardedTasks = new ArrayList<>();
            guardedTasks.add(new Task("craft", Map.of("item", targetPickaxe, "quantity", 1)));
            SteveMod.LOGGER.info("[PLAN_GUARD] Added missing craft task for '{}'", targetPickaxe);
            return new ResponseParser.ParsedResponse(parsed.getReasoning(), parsed.getPlan(), guardedTasks);
        }

        boolean alreadyCraftsTarget = tasks.stream().anyMatch(task -> {
            if (task == null || task.getAction() == null || task.getParameters() == null) {
                return false;
            }
            if (!"craft".equalsIgnoreCase(task.getAction())) {
                return false;
            }
            String item = firstNonBlank(
                toStringOrNull(task.getParameters().get("item")),
                toStringOrNull(task.getParameters().get("recipe"))
            );
            return item != null && normalizeItemName(item).endsWith(targetPickaxe);
        });
        if (alreadyCraftsTarget) {
            return parsed;
        }

        List<Task> guardedTasks = new ArrayList<>(tasks);
        guardedTasks.add(new Task("craft", Map.of("item", targetPickaxe, "quantity", 1)));
        SteveMod.LOGGER.info("[PLAN_GUARD] Appended craft task for '{}' to prevent shallow tool plan", targetPickaxe);
        return new ResponseParser.ParsedResponse(parsed.getReasoning(), parsed.getPlan(), guardedTasks);
    }

    private String detectTargetPickaxe(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        if (!PICKAXE_INTENT.matcher(lower).matches()) {
            return null;
        }
        if (lower.contains("wooden pickaxe") || lower.contains("wood pickaxe")) {
            return "wooden_pickaxe";
        }
        if (lower.contains("stone pickaxe")) {
            return "stone_pickaxe";
        }
        if (lower.contains("iron pickaxe")) {
            return "iron_pickaxe";
        }
        if (lower.contains("gold pickaxe") || lower.contains("golden pickaxe")) {
            return "golden_pickaxe";
        }
        if (lower.contains("diamond pickaxe")) {
            return "diamond_pickaxe";
        }
        if (lower.contains("netherite pickaxe")) {
            return "netherite_pickaxe";
        }
        return "wooden_pickaxe";
    }

    private String toStringOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeItemName(String item) {
        String normalized = item.toLowerCase(Locale.ROOT).replace(" ", "_");
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        return normalized;
    }
}
