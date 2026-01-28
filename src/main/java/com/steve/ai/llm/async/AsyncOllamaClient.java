package com.steve.ai.llm.async;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.config.SteveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous Ollama API client using Java HttpClient's sendAsync().
 *
 * <p>Ollama provides self-hosted LLM inference for models like LLaMA, Mistral, etc.
 * Uses Ollama's native /api/chat endpoint for better compatibility.</p>
 *
 * <p><b>API Endpoint:</b> {baseUrl}/api/chat</p>
 *
 * <p><b>Performance:</b> Varies based on hardware and model size</p>
 *
 * <p><b>Supported Models:</b></p>
 * <ul>
 *   <li>llama3.1:8b (recommended for most use cases)</li>
 *   <li>mistral (good balance of speed and quality)</li>
 *   <li>codellama (optimized for code generation)</li>
 *   <li>Any model available in your Ollama instance</li>
 * </ul>
 *
 * <p><b>Authentication:</b> Optional. Only needed if Ollama is behind a reverse proxy with auth.</p>
 *
 * <p><b>Thread Safety:</b> Thread-safe. HttpClient is thread-safe and immutable.</p>
 *
 * @since 1.0.0
 */
public class AsyncOllamaClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncOllamaClient.class);
    private static final String PROVIDER_ID = "ollama";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey; // optional
    private final String model;
    private final int maxTokens;
    private final double temperature;

    /**
     * Constructs an AsyncOllamaClient.
     *
     * @param baseUrl     Ollama server base URL (e.g., "http://127.0.0.1:11434")
     * @param apiKey      API key (optional, can be null or empty for local instances)
     * @param model       Model to use (e.g., "llama3.1:8b")
     * @param maxTokens   Maximum tokens in response (mapped to num_predict)
     * @param temperature Response randomness (0.0 - 2.0)
     * @throws IllegalArgumentException if baseUrl is null or empty
     */
    public AsyncOllamaClient(String baseUrl, String apiKey, String model, int maxTokens, double temperature) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("Ollama baseUrl cannot be null or empty");
        }

        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        LOGGER.info("AsyncOllamaClient initialized (baseUrl: {}, model: {}, maxTokens: {}, temperature: {})",
            baseUrl, model, maxTokens, temperature);
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        String requestBody = buildRequestBody(prompt, params);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(60));

        // Optional Auth (only if apiKey is set)
        if (apiKey != null && !apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = reqBuilder.build();

        LOGGER.debug("[ollama] Sending async request (prompt length: {} chars)", prompt.length());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                long latencyMs = System.currentTimeMillis() - startTime;

                if (response.statusCode() != 200) {
                    LLMException.ErrorType errorType = determineErrorType(response.statusCode());
                    boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;

                    LOGGER.error("[ollama] API error: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));

                    throw new LLMException(
                        "Ollama API error: HTTP " + response.statusCode(),
                        errorType,
                        PROVIDER_ID,
                        retryable
                    );
                }

                return parseResponse(response.body(), latencyMs);
            });
    }

    /**
     * Builds the JSON request body for Ollama /api/chat endpoint.
     *
     * @param prompt User prompt
     * @param params Additional parameters
     * @return JSON string
     */
    private String buildRequestBody(String prompt, Map<String, Object> params) {
        JsonObject body = new JsonObject();

        String modelToUse = (String) params.getOrDefault("model", this.model);
        int maxTokensToUse = (int) params.getOrDefault("maxTokens", this.maxTokens);
        double tempToUse = (double) params.getOrDefault("temperature", this.temperature);

        body.addProperty("model", modelToUse);
        body.addProperty("stream", false);

        // Ollama uses "options" object for parameters
        JsonObject options = new JsonObject();
        options.addProperty("temperature", tempToUse);
        options.addProperty("num_predict", maxTokensToUse); // Ollama's equivalent to max_tokens
        body.add("options", options);

        JsonArray messages = new JsonArray();

        // System message
        String systemPrompt = (String) params.get("systemPrompt");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            messages.add(systemMessage);
        }

        // User message
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        body.add("messages", messages);

        return body.toString();
    }

    /**
     * Parses Ollama API response.
     *
     * @param responseBody Raw JSON response
     * @param latencyMs    Request latency
     * @return Parsed LLMResponse
     */
    private LLMResponse parseResponse(String responseBody, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            // Ollama /api/chat response format: { message: { content: "..." }, ... }
            if (!json.has("message")) {
                throw new LLMException(
                    "Ollama response missing 'message' field",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            JsonObject message = json.getAsJsonObject("message");
            if (!message.has("content")) {
                throw new LLMException(
                    "Ollama message missing 'content' field",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            String content = message.get("content").getAsString();

            // Extract token count if available
            int tokensUsed = 0;
            if (json.has("eval_count")) {
                tokensUsed = json.get("eval_count").getAsInt();
            }

            LOGGER.debug("[ollama] Response received (latency: {}ms, tokens: {})", latencyMs, tokensUsed);

            return LLMResponse.builder()
                .content(content)
                .model(model)
                .providerId(PROVIDER_ID)
                .latencyMs(latencyMs)
                .tokensUsed(tokensUsed)
                .fromCache(false)
                .build();

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[ollama] Failed to parse response: {}", truncate(responseBody, 200), e);
            throw new LLMException(
                "Failed to parse Ollama response: " + e.getMessage(),
                LLMException.ErrorType.INVALID_RESPONSE,
                PROVIDER_ID,
                false,
                e
            );
        }
    }

    private LLMException.ErrorType determineErrorType(int statusCode) {
        return switch (statusCode) {
            case 429 -> LLMException.ErrorType.RATE_LIMIT;
            case 401, 403 -> LLMException.ErrorType.AUTH_ERROR;
            case 400 -> LLMException.ErrorType.CLIENT_ERROR;
            case 408 -> LLMException.ErrorType.TIMEOUT;
            default -> {
                if (statusCode >= 500) {
                    yield LLMException.ErrorType.SERVER_ERROR;
                }
                yield LLMException.ErrorType.CLIENT_ERROR;
            }
        };
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }
}
