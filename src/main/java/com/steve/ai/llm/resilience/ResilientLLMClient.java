package com.steve.ai.llm.resilience;

import com.steve.ai.llm.async.AsyncLLMClient;
import com.steve.ai.llm.async.LLMCache;
import com.steve.ai.llm.async.LLMException;
import com.steve.ai.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Decorator that adds lightweight resilience patterns to an AsyncLLMClient.
 *
 * <p>Wraps any AsyncLLMClient implementation (OpenAI, Groq, Gemini) with
 * fault tolerance patterns:</p>
 *
 * <ul>
 *   <li><b>Circuit Breaker:</b> Fail fast when provider is down</li>
 *   <li><b>Retry:</b> Automatic retry with exponential backoff</li>
 *   <li><b>Rate Limiter:</b> Prevent API quota exhaustion</li>
 *   <li><b>Bulkhead:</b> Limit concurrent requests</li>
 *   <li><b>Cache:</b> Response caching (40-60% hit rate)</li>
 *   <li><b>Fallback:</b> Pattern-based responses when all else fails</li>
 * </ul>
 *
 * <p><b>Design Pattern:</b> Decorator pattern - adds behavior without modifying original client</p>
 *
 * <p><b>Request Flow:</b></p>
 * <pre>
 * 1. Check cache → HIT: return cached response
 * 2. Check rate limiter → FULL: fallback
 * 3. Check bulkhead → FULL: fallback
 * 4. Check circuit breaker → OPEN: fallback
 * 5. Execute request with retry
 * 6. SUCCESS: cache response, return
 * 7. FAILURE: trigger fallback handler
 * </pre>
 *
 * @since 1.1.0
 */
public class ResilientLLMClient implements AsyncLLMClient {

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public static final class CircuitMetrics {
        private final CircuitState state;
        private final int windowSize;
        private final int callCount;
        private final int failureCount;
        private final float failureRate;
        private final long openUntilMs;

        public CircuitMetrics(CircuitState state, int windowSize, int callCount, int failureCount, float failureRate,
                              long openUntilMs) {
            this.state = state;
            this.windowSize = windowSize;
            this.callCount = callCount;
            this.failureCount = failureCount;
            this.failureRate = failureRate;
            this.openUntilMs = openUntilMs;
        }

        public CircuitState state() {
            return state;
        }

        public int windowSize() {
            return windowSize;
        }

        public int callCount() {
            return callCount;
        }

        public int failureCount() {
            return failureCount;
        }

        public float failureRate() {
            return failureRate;
        }

        public long openUntilMs() {
            return openUntilMs;
        }
    }

    public static final class RateLimiterMetrics {
        private final int limitPerMinute;
        private final int currentWindowCount;
        private final long windowResetMs;

        public RateLimiterMetrics(int limitPerMinute, int currentWindowCount, long windowResetMs) {
            this.limitPerMinute = limitPerMinute;
            this.currentWindowCount = currentWindowCount;
            this.windowResetMs = windowResetMs;
        }

        public int limitPerMinute() {
            return limitPerMinute;
        }

        public int currentWindowCount() {
            return currentWindowCount;
        }

        public long windowResetMs() {
            return windowResetMs;
        }
    }

    public static final class BulkheadMetrics {
        private final int maxConcurrentCalls;
        private final int availablePermits;

        public BulkheadMetrics(int maxConcurrentCalls, int availablePermits) {
            this.maxConcurrentCalls = maxConcurrentCalls;
            this.availablePermits = availablePermits;
        }

        public int maxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public int availablePermits() {
            return availablePermits;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientLLMClient.class);

    private final AsyncLLMClient delegate;
    private final LLMCache cache;
    private final LLMFallbackHandler fallbackHandler;

    private final Semaphore bulkhead;
    private final Object rateLock = new Object();
    private final Deque<Long> rateWindow = new ArrayDeque<>();

    private final Object circuitLock = new Object();
    private CircuitState circuitState = CircuitState.CLOSED;
    private long circuitOpenUntilMs;
    private final int[] outcomeWindow;
    private int outcomeIndex;
    private int outcomeCount;
    private int failureCount;
    private int halfOpenAttempts;
    private int halfOpenFailures;

    /**
     * Constructs a ResilientLLMClient wrapping the given delegate.
     *
     * @param delegate        The underlying AsyncLLMClient to wrap
     * @param cache           Cache for storing responses
     * @param fallbackHandler Handler for fallback responses when all fails
     */
    public ResilientLLMClient(AsyncLLMClient delegate, LLMCache cache, LLMFallbackHandler fallbackHandler) {
        this.delegate = delegate;
        this.cache = cache;
        this.fallbackHandler = fallbackHandler;

        this.bulkhead = new Semaphore(ResilienceConfig.getBulkheadMaxConcurrentCalls());
        this.outcomeWindow = new int[ResilienceConfig.getCircuitBreakerSlidingWindowSize()];

        LOGGER.info("Initializing resilient client for provider: {} (circuit breaker: {}, retry: {}, rate limiter: {}, bulkhead: {})",
            delegate.getProviderId(),
            ResilienceConfig.getCircuitBreakerSlidingWindowSize(),
            ResilienceConfig.getRetryMaxAttempts(),
            ResilienceConfig.getRateLimitPerMinute(),
            ResilienceConfig.getBulkheadMaxConcurrentCalls());
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        String model = (String) params.getOrDefault("model", "unknown");
        String providerId = delegate.getProviderId();

        Optional<LLMResponse> cached = cache.get(prompt, model, providerId);
        if (cached.isPresent()) {
            LOGGER.debug("[{}] Cache hit for prompt (hash: {})", providerId, prompt.hashCode());
            return CompletableFuture.completedFuture(cached.get());
        }

        if (!tryAcquireRateLimit()) {
            LOGGER.warn("[{}] Rate limiter rejected request (limit: {} req/min)",
                providerId, ResilienceConfig.getRateLimitPerMinute());
            return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt,
                new LLMException("Rate limit exceeded", LLMException.ErrorType.RATE_LIMIT, providerId, true)));
        }

        if (!bulkhead.tryAcquire()) {
            LOGGER.warn("[{}] Bulkhead rejected request (max concurrent: {})",
                providerId, ResilienceConfig.getBulkheadMaxConcurrentCalls());
            return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt,
                new LLMException("Too many concurrent requests", LLMException.ErrorType.SERVER_ERROR, providerId, true)));
        }

        if (!allowRequest()) {
            LOGGER.warn("[{}] Circuit breaker is OPEN - rejecting request", providerId);
            bulkhead.release();
            return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt,
                new LLMException("Circuit breaker open", LLMException.ErrorType.CIRCUIT_OPEN, providerId, false)));
        }

        CompletableFuture<LLMResponse> requestFuture = executeWithRetry(prompt, params, providerId, 1);

        return requestFuture.handle((response, throwable) -> {
            if (throwable == null) {
                recordSuccess();
                cache.put(prompt, model, providerId, response);
                LOGGER.debug("[{}] Request successful, cached response (latency: {}ms, tokens: {})",
                    providerId, response.getLatencyMs(), response.getTokensUsed());
                return response;
            }

            Throwable cause = unwrap(throwable);
            recordFailure();
            LOGGER.error("[{}] Request failed after retries, using fallback: {}",
                providerId, cause.getMessage());
            return fallbackHandler.generateFallback(prompt, cause);
        }).whenComplete((response, throwable) -> bulkhead.release());
    }

    private CompletableFuture<LLMResponse> executeWithRetry(String prompt, Map<String, Object> params,
                                                           String providerId, int attempt) {
        CompletableFuture<LLMResponse> result = new CompletableFuture<>();
        attemptSend(prompt, params, providerId, attempt, result);
        return result;
    }

    private void attemptSend(String prompt, Map<String, Object> params, String providerId,
                             int attempt, CompletableFuture<LLMResponse> result) {
        if (result.isDone()) {
            return;
        }

        delegate.sendAsync(prompt, params).whenComplete((response, throwable) -> {
            if (throwable == null) {
                result.complete(response);
                return;
            }

            Throwable cause = unwrap(throwable);
            int maxAttempts = ResilienceConfig.getRetryMaxAttempts();
            boolean retryable = isRetryable(cause);

            if (attempt < maxAttempts && retryable) {
                long delayMs = ResilienceConfig.getRetryBackoffMillis(attempt);
                LOGGER.warn("[{}] Retry attempt {} of {} after {}ms (reason: {})",
                    providerId, attempt + 1, maxAttempts, delayMs,
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                    .execute(() -> attemptSend(prompt, params, providerId, attempt + 1, result));
            } else {
                result.completeExceptionally(cause);
            }
        });
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof LLMException) {
            return ((LLMException) throwable).isRetryable();
        }
        if (throwable instanceof IOException) {
            return true;
        }
        return throwable instanceof java.util.concurrent.TimeoutException;
    }

    private boolean tryAcquireRateLimit() {
        long now = System.currentTimeMillis();
        long cutoff = now - TimeUnit.MINUTES.toMillis(1);

        synchronized (rateLock) {
            while (!rateWindow.isEmpty() && rateWindow.peekFirst() < cutoff) {
                rateWindow.removeFirst();
            }

            if (rateWindow.size() >= ResilienceConfig.getRateLimitPerMinute()) {
                return false;
            }

            rateWindow.addLast(now);
            return true;
        }
    }

    private boolean allowRequest() {
        synchronized (circuitLock) {
            long now = System.currentTimeMillis();
            if (circuitState == CircuitState.OPEN) {
                if (now >= circuitOpenUntilMs) {
                    transitionToHalfOpenLocked();
                } else {
                    return false;
                }
            }

            if (circuitState == CircuitState.HALF_OPEN) {
                if (halfOpenAttempts >= ResilienceConfig.getCircuitBreakerHalfOpenCalls()) {
                    return false;
                }
                halfOpenAttempts++;
            }

            return true;
        }
    }

    private void recordSuccess() {
        synchronized (circuitLock) {
            recordOutcomeLocked(true);

            if (circuitState == CircuitState.HALF_OPEN) {
                if (halfOpenAttempts >= ResilienceConfig.getCircuitBreakerHalfOpenCalls()
                    && halfOpenFailures == 0) {
                    closeCircuitLocked();
                }
            }
        }
    }

    private void recordFailure() {
        synchronized (circuitLock) {
            recordOutcomeLocked(false);

            if (circuitState == CircuitState.HALF_OPEN) {
                halfOpenFailures++;
                openCircuitLocked("half-open failure");
                return;
            }

            if (circuitState == CircuitState.CLOSED && outcomeCount >= outcomeWindow.length) {
                float failureRate = (failureCount * 100.0f) / outcomeCount;
                if (failureRate >= ResilienceConfig.getCircuitBreakerFailureRateThreshold()) {
                    openCircuitLocked("failure rate " + failureRate + "%");
                }
            }
        }
    }

    private void recordOutcomeLocked(boolean success) {
        int value = success ? 0 : 1;
        if (outcomeCount < outcomeWindow.length) {
            outcomeWindow[outcomeIndex] = value;
            outcomeCount++;
            if (value == 1) {
                failureCount++;
            }
        } else {
            int old = outcomeWindow[outcomeIndex];
            if (old == 1) {
                failureCount--;
            }
            outcomeWindow[outcomeIndex] = value;
            if (value == 1) {
                failureCount++;
            }
        }

        outcomeIndex = (outcomeIndex + 1) % outcomeWindow.length;
    }

    private void openCircuitLocked(String reason) {
        circuitState = CircuitState.OPEN;
        circuitOpenUntilMs = System.currentTimeMillis() + ResilienceConfig.getCircuitBreakerWaitDurationMillis();
        halfOpenAttempts = 0;
        halfOpenFailures = 0;
        LOGGER.warn("[{}] Circuit breaker OPEN ({})", delegate.getProviderId(), reason);
    }

    private void transitionToHalfOpenLocked() {
        circuitState = CircuitState.HALF_OPEN;
        halfOpenAttempts = 0;
        halfOpenFailures = 0;
        LOGGER.warn("[{}] Circuit breaker HALF_OPEN", delegate.getProviderId());
    }

    private void closeCircuitLocked() {
        circuitState = CircuitState.CLOSED;
        outcomeIndex = 0;
        outcomeCount = 0;
        failureCount = 0;
        for (int i = 0; i < outcomeWindow.length; i++) {
            outcomeWindow[i] = 0;
        }
        LOGGER.info("[{}] Circuit breaker CLOSED", delegate.getProviderId());
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @Override
    public String getProviderId() {
        return delegate.getProviderId();
    }

    @Override
    public boolean isHealthy() {
        synchronized (circuitLock) {
            return circuitState != CircuitState.OPEN;
        }
    }

    public CircuitState getCircuitBreakerState() {
        synchronized (circuitLock) {
            return circuitState;
        }
    }

    public CircuitMetrics getCircuitBreakerMetrics() {
        synchronized (circuitLock) {
            float failureRate = outcomeCount == 0 ? 0.0f : (failureCount * 100.0f) / outcomeCount;
            return new CircuitMetrics(circuitState, outcomeWindow.length, outcomeCount, failureCount, failureRate,
                circuitOpenUntilMs);
        }
    }

    public RateLimiterMetrics getRateLimiterMetrics() {
        long now = System.currentTimeMillis();
        long cutoff = now - TimeUnit.MINUTES.toMillis(1);
        synchronized (rateLock) {
            while (!rateWindow.isEmpty() && rateWindow.peekFirst() < cutoff) {
                rateWindow.removeFirst();
            }
            long resetAt = rateWindow.isEmpty() ? now : rateWindow.peekFirst() + TimeUnit.MINUTES.toMillis(1);
            return new RateLimiterMetrics(ResilienceConfig.getRateLimitPerMinute(), rateWindow.size(), resetAt);
        }
    }

    public BulkheadMetrics getBulkheadMetrics() {
        return new BulkheadMetrics(ResilienceConfig.getBulkheadMaxConcurrentCalls(), bulkhead.availablePermits());
    }

    /**
     * Manually transitions the circuit breaker to CLOSED state.
     *
     * <p><b>Warning:</b> Use with caution. Only for testing or manual recovery.</p>
     */
    public void resetCircuitBreaker() {
        synchronized (circuitLock) {
            closeCircuitLocked();
        }
    }
}
