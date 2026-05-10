package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for Ollama self-hosted LLM API
 * Uses Ollama's native /api/chat endpoint for better compatibility
 */
public class OllamaClient {
    private static final int MAX_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 1000;

    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey; // optional

    public OllamaClient() {
        this.baseUrl = SteveConfig.OLLAMA_BASE_URL.get();
        this.apiKey = SteveConfig.OLLAMA_API_KEY.get();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public String sendRequest(String systemPrompt, String userPrompt) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            SteveMod.LOGGER.error("Ollama baseUrl not configured!");
            return null;
        }

        JsonObject requestBody = buildRequestBody(systemPrompt, userPrompt);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));

        // Optional Auth (only if apiKey is set, for reverse proxy scenarios)
        if (apiKey != null && !apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = reqBuilder.build();

        // Retry logic with exponential backoff
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String body = response.body();
                    if (body == null || body.isEmpty()) {
                        SteveMod.LOGGER.error("Ollama returned empty response");
                        return null;
                    }
                    return parseResponse(body);
                }

                // Check if error is retryable (rate limit, server error)
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < MAX_RETRIES - 1) {
                        int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                        SteveMod.LOGGER.warn("Ollama request failed with status {}, retrying in {}ms (attempt {}/{})",
                            response.statusCode(), delayMs, attempt + 1, MAX_RETRIES);
                        Thread.sleep(delayMs);
                        continue;
                    }
                }

                // Non-retryable error or final attempt
                SteveMod.LOGGER.error("Ollama request failed: {}", response.statusCode());
                SteveMod.LOGGER.error("Response body: {}", response.body());
                return null;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SteveMod.LOGGER.error("Request interrupted", e);
                return null;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                    SteveMod.LOGGER.warn("Error communicating with Ollama, retrying in {}ms (attempt {}/{})",
                        delayMs, attempt + 1, MAX_RETRIES, e);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    SteveMod.LOGGER.error("Error communicating with Ollama after {} attempts", MAX_RETRIES, e);
                    return null;
                }
            }
        }

        return null;
    }

    private JsonObject buildRequestBody(String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", SteveConfig.OLLAMA_MODEL.get());
        body.addProperty("stream", false);

        // Ollama options: temperature and num_predict (similar to max_tokens)
        JsonObject options = new JsonObject();
        options.addProperty("temperature", SteveConfig.TEMPERATURE.get());
        options.addProperty("num_predict", SteveConfig.MAX_TOKENS.get());
        body.add("options", options);

        // Build messages array
        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", userPrompt);
        messages.add(usr);

        body.add("messages", messages);
        return body;
    }

    private String parseResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            // Ollama /api/chat response format: { message: { content: "..." }, ... }
            if (json.has("message")) {
                JsonObject message = json.getAsJsonObject("message");
                if (message.has("content")) {
                    return message.get("content").getAsString();
                }
            }
            SteveMod.LOGGER.error("Unexpected Ollama response format: {}", responseBody);
            return null;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error parsing Ollama response", e);
            return null;
        }
    }
}
