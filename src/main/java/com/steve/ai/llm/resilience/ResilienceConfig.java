package com.steve.ai.llm.resilience;

/**
 * Configuration for lightweight resilience patterns.
 *
 * <p>These values mirror the previous Resilience4j defaults and are used by
 * {@link ResilientLLMClient}.</p>
 */
public class ResilienceConfig {

    // Circuit Breaker thresholds
    private static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;
    private static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;
    private static final int CIRCUIT_BREAKER_WAIT_DURATION_SECONDS = 30;
    private static final int CIRCUIT_BREAKER_HALF_OPEN_CALLS = 3;

    // Retry configuration
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final int RETRY_INITIAL_INTERVAL_MS = 1000; // 1s, 2s, 4s

    // Rate Limiter configuration
    private static final int RATE_LIMIT_PER_MINUTE = 10;
    private static final int RATE_LIMITER_TIMEOUT_SECONDS = 5;

    // Bulkhead configuration
    private static final int BULKHEAD_MAX_CONCURRENT_CALLS = 5;
    private static final int BULKHEAD_MAX_WAIT_DURATION_SECONDS = 10;

    public static int getCircuitBreakerSlidingWindowSize() {
        return CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE;
    }

    public static float getCircuitBreakerFailureRateThreshold() {
        return CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD;
    }

    public static int getCircuitBreakerHalfOpenCalls() {
        return CIRCUIT_BREAKER_HALF_OPEN_CALLS;
    }

    public static long getCircuitBreakerWaitDurationMillis() {
        return CIRCUIT_BREAKER_WAIT_DURATION_SECONDS * 1000L;
    }

    public static int getRetryMaxAttempts() {
        return RETRY_MAX_ATTEMPTS;
    }

    public static long getRetryBackoffMillis(int attemptNumber) {
        int exponent = Math.max(0, attemptNumber - 1);
        return RETRY_INITIAL_INTERVAL_MS * (long) (1 << exponent);
    }

    public static int getRateLimitPerMinute() {
        return RATE_LIMIT_PER_MINUTE;
    }

    public static long getRateLimiterTimeoutMillis() {
        return RATE_LIMITER_TIMEOUT_SECONDS * 1000L;
    }

    public static int getBulkheadMaxConcurrentCalls() {
        return BULKHEAD_MAX_CONCURRENT_CALLS;
    }

    public static long getBulkheadMaxWaitDurationMillis() {
        return BULKHEAD_MAX_WAIT_DURATION_SECONDS * 1000L;
    }
}
