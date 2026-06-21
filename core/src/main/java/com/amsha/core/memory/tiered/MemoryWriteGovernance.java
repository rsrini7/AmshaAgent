package com.amsha.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Per-tool token bucket rate limiter for memory write operations.
 * <p>
 * Prevents the agent from flooding memory writes by enforcing
 * configurable rate limits per tool type. Uses a simple token bucket
 * algorithm — each call consumes a token; tokens replenish at a fixed rate.
 * <p>
 * Unlike {@link com.amsha.core.governance.RateLimitTracker} (which tracks
 * LLM provider rate limits), this governs agent-initiated memory mutations.
 * <p>
 * <b>Scope:</b> Node-local, in-memory. Rate limit state is not persisted
 * across process restarts and is not shared across cluster nodes.
 * This is intentional — it provides agent-side guardrails against
 * runaway write loops, not distributed rate limiting. For cluster-wide
 * enforcement, use application-level quotas or external API gateways.
 *
 * @see CoreMemoryAppendTool
 * @see CoreMemoryReplaceTool
 * @see ArchivalMemoryInsertTool
 */
@ApplicationScoped
public class MemoryWriteGovernance {

    private static final Logger log = Logger.getLogger(MemoryWriteGovernance.class);

    @ConfigProperty(name = "amsha.memory.governance.core-writes-per-minute", defaultValue = "5")
    int coreWritesPerMinute;

    @ConfigProperty(name = "amsha.memory.governance.archival-writes-per-hour", defaultValue = "10")
    int archivalWritesPerHour;

    /**
     * Token bucket state: (tokensRemaining, lastRefillTimestamp).
     */
    private static class Bucket {
        volatile double tokens;
        volatile long lastRefillMs;
        final double maxTokens;
        final double refillRatePerMs;

        Bucket(double maxTokens, double refillRatePerMs) {
            this.maxTokens = maxTokens;
            this.refillRatePerMs = refillRatePerMs;
            this.tokens = maxTokens;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            double elapsed = now - lastRefillMs;
            tokens = Math.min(maxTokens, tokens + elapsed * refillRatePerMs);
            lastRefillMs = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket getOrCreateBucket(String toolType, double maxTokens, double refillRatePerMs) {
        return buckets.computeIfAbsent(toolType, k -> new Bucket(maxTokens, refillRatePerMs));
    }

    /**
     * Check if a core memory write is allowed under the rate limit.
     *
     * @throws RateLimitExceededException if rate limit exceeded
     */
    public void checkCoreWrite() {
        // 5 per minute = 5 tokens max, refill 5/60000 per ms
        Bucket bucket = getOrCreateBucket("core", coreWritesPerMinute, coreWritesPerMinute / 60_000.0);
        if (!bucket.tryConsume()) {
            log.warnf("Core memory write rate limit exceeded (%d/minute)", coreWritesPerMinute);
            throw new RateLimitExceededException("core_memory", coreWritesPerMinute, "minute");
        }
    }

    /**
     * Check if an archival memory write is allowed under the rate limit.
     *
     * @throws RateLimitExceededException if rate limit exceeded
     */
    public void checkArchivalWrite() {
        // 10 per hour = 10 tokens max, refill 10/3600000 per ms
        Bucket bucket = getOrCreateBucket("archival", archivalWritesPerHour, archivalWritesPerHour / 3_600_000.0);
        if (!bucket.tryConsume()) {
            log.warnf("Archival memory write rate limit exceeded (%d/hour)", archivalWritesPerHour);
            throw new RateLimitExceededException("archival_memory", archivalWritesPerHour, "hour");
        }
    }

    /**
     * Exception thrown when a memory write rate limit is exceeded.
     */
    public static class RateLimitExceededException extends RuntimeException {
        private final String toolType;
        private final int limit;
        private final String period;

        public RateLimitExceededException(String toolType, int limit, String period) {
            super(String.format("Rate limit exceeded for %s: max %d per %s", toolType, limit, period));
            this.toolType = toolType;
            this.limit = limit;
            this.period = period;
        }

        public String toolType() {
            return toolType;
        }

        public int limit() {
            return limit;
        }

        public String period() {
            return period;
        }
    }
}
