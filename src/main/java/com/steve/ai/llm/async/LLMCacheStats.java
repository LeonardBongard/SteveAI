package com.steve.ai.llm.async;

/**
 * Lightweight cache statistics snapshot.
 */
public final class LLMCacheStats {

    private final long hitCount;
    private final long missCount;
    private final long evictionCount;
    private final long size;

    public LLMCacheStats(long hitCount, long missCount, long evictionCount, long size) {
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.evictionCount = evictionCount;
        this.size = size;
    }

    public long hitCount() {
        return hitCount;
    }

    public long missCount() {
        return missCount;
    }

    public long evictionCount() {
        return evictionCount;
    }

    public long size() {
        return size;
    }

    public long requestCount() {
        return hitCount + missCount;
    }

    public double hitRate() {
        long total = requestCount();
        if (total == 0) {
            return 0.0;
        }
        return (double) hitCount / (double) total;
    }

    public double missRate() {
        long total = requestCount();
        if (total == 0) {
            return 0.0;
        }
        return (double) missCount / (double) total;
    }
}
