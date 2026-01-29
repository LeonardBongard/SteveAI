package com.steve.ai.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SteveConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_MODEL;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STEVES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEBUG_OVERLAY;
    
    // Ollama configuration
    public static final ForgeConfigSpec.ConfigValue<String> OLLAMA_BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> OLLAMA_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> OLLAMA_API_KEY;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("AI API Configuration").push("ai");
        
        AI_PROVIDER = builder
            .comment("AI provider to use: 'groq' (FASTEST, FREE), 'openai', 'gemini', or 'ollama'")
            .define("provider", "groq");
        
        builder.pop();

        builder.comment("OpenAI/Gemini API Configuration (same key field used for both)").push("openai");
        
        OPENAI_API_KEY = builder
            .comment("Your OpenAI API key (required)")
            .define("apiKey", "");
        
        OPENAI_MODEL = builder
            .comment("OpenAI model to use (gpt-4, gpt-4-turbo-preview, gpt-3.5-turbo)")
            .define("model", "gpt-4-turbo-preview");
        
        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 8000, 100, 65536);
        
        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);
        
        builder.pop();

        builder.comment("Ollama Self-hosted LLM Configuration").push("ollama");
        
        OLLAMA_BASE_URL = builder
            .comment("Ollama server base URL (e.g., http://127.0.0.1:11434)")
            .define("baseUrl", "http://127.0.0.1:11434");
        
        OLLAMA_MODEL = builder
            .comment("Ollama model to use (e.g., llama3.1:8b, mistral)")
            .define("model", "llama3.1:8b");
        
        OLLAMA_API_KEY = builder
            .comment("API key for Ollama (optional, only needed if using reverse proxy with auth)")
            .define("apiKey", "");
        
        builder.pop();

        builder.comment("Steve Behavior Configuration").push("behavior");
        
        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);
        
        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Steves to respond in chat")
            .define("enableChatResponses", true);

        ENABLE_DEBUG_OVERLAY = builder
            .comment("Show on-screen debug overlay with Steve status")
            .define("enableDebugOverlay", true);

        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);
        
        builder.pop();

        SPEC = builder.build();
    }
}
