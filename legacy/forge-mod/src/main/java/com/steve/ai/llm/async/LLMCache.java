package com.steve.ai.llm.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * LRU cache for LLM responses using a lightweight in-memory map.
 *
 * <p>Caches LLM responses to reduce API calls and costs. Cache key is a SHA-256 hash
 * of the combination of provider, model, and prompt.</p>
 *
 * <p><b>Cache Configuration:</b></p>
 * <ul>
 *   <li>Maximum size: 500 entries (~25MB estimated)</li>
 *   <li>TTL: 5 minutes (expireAfterWrite)</li>
 *   <li>Eviction: LRU (Least Recently Used)</li>
 *   <li>Stats: Lightweight counters for monitoring hit/miss rates</li>
 * </ul>
 *
 * <p><b>Performance Impact:</b></p>
 * <ul>
 *   <li>Cache hit: ~1ms latency (vs 500-2000ms for API call)</li>
 *   <li>Expected hit rate: 40-60% after warmup</li>
 *   <li>Cost savings: $0.002 per 1K tokens saved</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> All operations are synchronized</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * LLMCache cache = new LLMCache();
 *
 * // Check cache before API call
 * Optional&lt;LLMResponse&gt; cached = cache.get("user prompt", "gpt-3.5-turbo", "openai");
 * if (cached.isPresent()) {
 *     return cached.get(); // Cache hit!
 * }
 *
 * // Cache miss - make API call
 * LLMResponse response = makeApiCall(...);
 * cache.put("user prompt", "gpt-3.5-turbo", "openai", response);
 *
 * // Monitor cache performance
 * LLMCacheStats stats = cache.getStats();
 * double hitRate = stats.hitRate();
 * System.out.println("Cache hit rate: " + (hitRate * 100) + "%");
 * </pre>
 *
 * @since 1.1.0
 */
public class LLMCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCache.class);

    private static final int MAX_CACHE_SIZE = 500;
    private static final int TTL_MINUTES = 5;
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(TTL_MINUTES);

    private final Object lock = new Object();
    private final Map<String, CacheEntry> cache;

    private long hitCount;
    private long missCount;
    private long evictionCount;

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    /**
     * Constructs a new LLMCache with default configuration.
     *
     * <p>Cache is configured with:</p>
     * <ul>
     *   <li>500 entry maximum (LRU eviction)</li>
     *   <li>5 minute TTL</li>
     *   <li>Statistics tracking enabled</li>
     * </ul>
     */
    public LLMCache() {
        LOGGER.info("Initializing LLM cache (max size: {}, TTL: {} minutes)", MAX_CACHE_SIZE, TTL_MINUTES);

        this.cache = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                boolean shouldEvict = size() > MAX_CACHE_SIZE;
                if (shouldEvict) {
                    evictionCount++;
                }
                return shouldEvict;
            }
        };

        LOGGER.info("LLM cache initialized successfully");
    }

    /**
     * Retrieves a cached response if available.
     *
     * <p><b>Performance:</b> O(1) lookup, typically <1ms</p>
     *
     * @param prompt     The prompt text (used in cache key)
     * @param model      The model name (used in cache key)
     * @param providerId The provider ID (used in cache key)
     * @return Optional containing cached response, or empty if cache miss
     */
    public Optional<LLMResponse> get(String prompt, String model, String providerId) {
        String key = generateKey(prompt, model, providerId);
        long now = System.currentTimeMillis();

        synchronized (lock) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                missCount++;
                LOGGER.debug("Cache MISS for provider={}, model={}, promptHash={}",
                    providerId, model, key.substring(0, 8));
                return Optional.empty();
            }

            if (isExpired(entry, now)) {
                cache.remove(key);
                missCount++;
                evictionCount++;
                LOGGER.debug("Cache EXPIRED for provider={}, model={}, promptHash={}",
                    providerId, model, key.substring(0, 8));
                return Optional.empty();
            }

            hitCount++;
            LOGGER.debug("Cache HIT for provider={}, model={}, promptHash={}",
                providerId, model, key.substring(0, 8));
            return Optional.of(entry.response);
        }
    }

    /**
     * Stores a response in the cache.
     *
     * <p>Automatically sets the {@code fromCache} flag to true on the stored response.</p>
     *
     * <p><b>Performance:</b> O(1) insertion, typically <1ms</p>
     *
     * @param prompt     The prompt text (used in cache key)
     * @param model      The model name (used in cache key)
     * @param providerId The provider ID (used in cache key)
     * @param response   The response to cache
     */
    public void put(String prompt, String model, String providerId, LLMResponse response) {
        String key = generateKey(prompt, model, providerId);
        long now = System.currentTimeMillis();

        LLMResponse cachedResponse = response.withCacheFlag(true);

        synchronized (lock) {
            pruneExpiredLocked(now);
            cache.put(key, new CacheEntry(cachedResponse, now));
        }

        LOGGER.debug("Cached response for provider={}, model={}, promptHash={}, tokens={}",
            providerId, model, key.substring(0, 8), response.getTokensUsed());
    }

    /**
     * Generates a cache key from prompt, model, and provider.
     *
     * <p>Uses SHA-256 hash to ensure consistent key length and prevent cache
     * key collision. Format: "{providerId}:{model}:{prompt}" → SHA-256 hex</p>
     *
     * <p><b>Why SHA-256?</b></p>
     * <ul>
     *   <li>Fixed length (64 hex chars) regardless of prompt length</li>
     *   <li>Cryptographically secure (prevents collision attacks)</li>
     *   <li>Fast (~1μs on modern hardware)</li>
     * </ul>
     *
     * @param prompt     The prompt text
     * @param model      The model name
     * @param providerId The provider ID
     * @return SHA-256 hash as hex string (64 characters)
     */
    private String generateKey(String prompt, String model, String providerId) {
        String composite = providerId + ":" + model + ":" + prompt;
        byte[] digest = DIGEST.get().digest(composite.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    /**
     * Returns cache statistics for monitoring.
     *
     * @return Immutable snapshot of cache statistics
     */
    public LLMCacheStats getStats() {
        synchronized (lock) {
            return new LLMCacheStats(hitCount, missCount, evictionCount, cache.size());
        }
    }

    /**
     * Returns the approximate number of entries in the cache.
     *
     * @return Approximate cache size
     */
    public long size() {
        synchronized (lock) {
            return cache.size();
        }
    }

    /**
     * Invalidates all entries in the cache.
     *
     * <p><b>Use Case:</b> Testing, memory pressure, or after configuration changes</p>
     *
     * <p><b>Warning:</b> This clears all cached responses. Next requests will
     * result in cache misses and fresh API calls.</p>
     */
    public void clear() {
        long sizeBefore;
        synchronized (lock) {
            sizeBefore = cache.size();
            cache.clear();
        }
        LOGGER.info("Cache cleared, removed ~{} entries", sizeBefore);
    }

    /**
     * Logs current cache statistics at INFO level.
     *
     * <p>Useful for periodic monitoring and debugging.</p>
     */
    public void logStats() {
        LLMCacheStats stats = getStats();
        LOGGER.info("LLM Cache Stats - Size: ~{}/{}, Hit Rate: {:.2f}%, Hits: {}, Misses: {}, Evictions: {}",
            stats.size(),
            MAX_CACHE_SIZE,
            stats.hitRate() * 100,
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount()
        );
    }

    private boolean isExpired(CacheEntry entry, long now) {
        return now - entry.createdAtMillis >= TTL_MILLIS;
    }

    private void pruneExpiredLocked(long now) {
        Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (isExpired(entry.getValue(), now)) {
                iterator.remove();
                evictionCount++;
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static final class CacheEntry {
        private final LLMResponse response;
        private final long createdAtMillis;

        private CacheEntry(LLMResponse response, long createdAtMillis) {
            this.response = response;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
