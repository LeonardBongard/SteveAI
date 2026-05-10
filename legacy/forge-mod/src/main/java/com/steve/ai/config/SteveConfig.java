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
    public static final ForgeConfigSpec.IntValue CHEST_SOURCE_RADIUS;
    public static final ForgeConfigSpec.IntValue CHEST_SOURCE_STALE_TICKS;
    public static final ForgeConfigSpec.IntValue CHEST_SOURCE_VERY_STALE_TICKS;
    public static final ForgeConfigSpec.IntValue CHEST_SOURCE_MAX_DISTANCE_CRITICAL;
    public static final ForgeConfigSpec.IntValue CHEST_SOURCE_MAX_DISTANCE_HIGH;
    public static final ForgeConfigSpec.DoubleValue CHEST_SOURCE_MIN_SCORE_NORMAL;
    public static final ForgeConfigSpec.DoubleValue CHEST_SOURCE_MIN_SCORE_CRITICAL;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEBUG_OVERLAY;
    public static final ForgeConfigSpec.IntValue DEBUG_VISIBLE_BLOCK_TICK_INTERVAL;
    public static final ForgeConfigSpec.IntValue DEBUG_VISIBLE_BLOCK_RADIUS;
    public static final ForgeConfigSpec.IntValue DEBUG_VISIBLE_BLOCK_MAX_ENTRIES;
    public static final ForgeConfigSpec.BooleanValue DEBUG_BROADCAST_VISIBLE_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VIEW_COVERAGE_OVERLAY;
    public static final ForgeConfigSpec.ConfigValue<String> PERSONA_DEFAULT;
    public static final ForgeConfigSpec.ConfigValue<String> PERSONA_OVERRIDES;
    public static final ForgeConfigSpec.BooleanValue AUTO_RUN_IRON_PICKAXE_PLAYTEST_ON_WORLD_LOAD;
    public static final ForgeConfigSpec.BooleanValue AUTO_PLAYTEST_AUTO_SPAWN_STEVE;
    public static final ForgeConfigSpec.ConfigValue<String> AUTO_PLAYTEST_STEVE_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> AUTO_PLAYTEST_MATRIX;
    public static final ForgeConfigSpec.IntValue AUTO_PLAYTEST_TIMEOUT_SECONDS;
    
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

        DEBUG_VISIBLE_BLOCK_TICK_INTERVAL = builder
            .comment("Ticks between visible-block snapshot updates (20 ticks = 1 second)")
            .defineInRange("debugVisibleBlockTickInterval", 10, 1, 200);

        DEBUG_VISIBLE_BLOCK_RADIUS = builder
            .comment("Radius around each Steve to sample visible blocks for debug snapshots")
            .defineInRange("debugVisibleBlockRadius", 24, 1, 32);

        DEBUG_VISIBLE_BLOCK_MAX_ENTRIES = builder
            .comment("Maximum number of block entries to include in a visible-block snapshot")
            .defineInRange("debugVisibleBlockMaxEntries", 800, 10, 2000);

        DEBUG_BROADCAST_VISIBLE_BLOCKS = builder
            .comment("Dev mode: broadcast visible-block snapshots to all players (ignores debug UI subscription)")
            .define("debugBroadcastVisibleBlocks", false);
        ENABLE_VIEW_COVERAGE_OVERLAY = builder
            .comment("Show view coverage summary and least-seen directions in the debug overlay")
            .define("enableViewCoverageOverlay", false);

        PERSONA_DEFAULT = builder
            .comment("Default Steve persona. Supported: tunneler, cave_explorer")
            .define("personaDefault", "tunneler");

        PERSONA_OVERRIDES = builder
            .comment("Per-Steve persona overrides: name:persona pairs separated by comma. Example: Steve:tunneler,Alex:cave_explorer")
            .define("personaOverrides", "Steve:tunneler,Alex:cave_explorer");

        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);

        CHEST_SOURCE_RADIUS = builder
            .comment("Max distance (blocks) for chest-memory sourcing and retrieval")
            .defineInRange("chestSourceRadius", 50, 1, 256);

        CHEST_SOURCE_STALE_TICKS = builder
            .comment("Chest memory age (ticks) after which sourcing gets penalized (20 ticks = 1 second)")
            .defineInRange("chestSourceStaleTicks", 12000, 200, 2_000_000);

        CHEST_SOURCE_VERY_STALE_TICKS = builder
            .comment("Chest memory age (ticks) considered very stale and often rejected")
            .defineInRange("chestSourceVeryStaleTicks", 36000, 400, 4_000_000);

        CHEST_SOURCE_MAX_DISTANCE_CRITICAL = builder
            .comment("Max chest travel distance in CRITICAL urgency")
            .defineInRange("chestSourceMaxDistanceCritical", 16, 1, 256);

        CHEST_SOURCE_MAX_DISTANCE_HIGH = builder
            .comment("Max chest travel distance in HIGH urgency")
            .defineInRange("chestSourceMaxDistanceHigh", 24, 1, 256);

        CHEST_SOURCE_MIN_SCORE_NORMAL = builder
            .comment("Minimum source score to accept chest candidate in NORMAL urgency")
            .defineInRange("chestSourceMinScoreNormal", 0.0, -20.0, 20.0);

        CHEST_SOURCE_MIN_SCORE_CRITICAL = builder
            .comment("Minimum source score to accept chest candidate in CRITICAL urgency")
            .defineInRange("chestSourceMinScoreCritical", 0.75, -20.0, 20.0);

        AUTO_RUN_IRON_PICKAXE_PLAYTEST_ON_WORLD_LOAD = builder
            .comment("If true, auto-start iron pickaxe playtest when world loads and a player is present")
            .define("autoRunIronPickaxePlaytestOnWorldLoad", true);

        AUTO_PLAYTEST_AUTO_SPAWN_STEVE = builder
            .comment("If true and target Steve is missing, auto-spawn target Steve before auto playtest")
            .define("autoPlaytestAutoSpawnSteve", true);

        AUTO_PLAYTEST_STEVE_NAME = builder
            .comment("Steve name(s) used for world-load auto playtest. Comma-separated values are allowed, e.g. Steve,Alex")
            .define("autoPlaytestSteveName", "Steve,Alex");

        AUTO_PLAYTEST_MATRIX = builder
            .comment("Auto playtest matrix. Entries: name:scenario or name:persona:scenario, comma-separated. Example: Steve:tunneler:iron_pickaxe,Alex:cave_explorer:iron_pickaxe")
            .define("autoPlaytestMatrix", "Steve:tunneler:iron_pickaxe,Alex:cave_explorer:iron_pickaxe");

        AUTO_PLAYTEST_TIMEOUT_SECONDS = builder
            .comment("Timeout seconds for world-load auto playtest")
            .defineInRange("autoPlaytestTimeoutSeconds", 420, 30, 1800);
        
        builder.pop();

        SPEC = builder.build();
    }
}
