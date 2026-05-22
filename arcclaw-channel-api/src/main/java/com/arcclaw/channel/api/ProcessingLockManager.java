package com.arcclaw.channel.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pluggable lock manager for chat-level processing serialization.
 * <p>
 * The default JVM implementation uses a {@link ConcurrentHashMap} with TTL
 * expiry. For clustered deployments, provide a CDI bean backed by Redis or
 * database advisory locks that overrides this default.
 *
 * @see AbstractInboundMessagePipeline
 */
public interface ProcessingLockManager {

    /** Default TTL for locks in milliseconds. */
    long DEFAULT_TTL_MILLIS = 30_000L; // 30 seconds

    /**
     * Attempt to acquire a processing lock for the given chat.
     *
     * @param chatId the chat/thread identifier
     * @return {@code true} if the lock was acquired, {@code false} if already locked
     */
    boolean tryLock(String chatId);

    /** Release the processing lock for the given chat. */
    void unlock(String chatId);

    /** Check if the given chat is currently locked. */
    boolean isLocked(String chatId);

    /**
     * Default JVM-local implementation backed by a {@link ConcurrentHashMap}
     * with a 30-second TTL. Suitable for single-node deployments.
     * <p>
     * Lock acquisition uses {@link ConcurrentHashMap#putIfAbsent} for atomic
     * check-and-set. Expired entries are pruned when the map exceeds a
     * threshold rather than on every call, amortizing the O(N) scan cost.
     * <p>
     * TODO: Replace with Caffeine expireAfterWrite or external distributed lock.
     * Current ConcurrentHashMap + pruning is a temporary JVM-local implementation.
     */
    final class Default implements ProcessingLockManager {

        /**
         * Prune expired locks when map size exceeds this threshold.
         * For high-concurrency deployments (500+ concurrent chats),
         * reduce this value or use an external time-based scheduler.
         */
        private static final int PRUNE_THRESHOLD = 500;

        private final Map<String, Long> locks = new ConcurrentHashMap<>();
        private final long ttlMillis;

        public Default() {
            this(DEFAULT_TTL_MILLIS);
        }

        public Default(long ttlMillis) {
            this.ttlMillis = ttlMillis;
        }

        @Override
        public boolean tryLock(String chatId) {
            long now = System.currentTimeMillis();
            // Amortized O(N) — only prune when the map exceeds the threshold,
            // not on every single lock attempt.
            if (locks.size() > PRUNE_THRESHOLD) {
                pruneExpired(now);
            }
            return locks.putIfAbsent(chatId, now) == null;
        }

        @Override
        public void unlock(String chatId) {
            locks.remove(chatId);
        }

        @Override
        public boolean isLocked(String chatId) {
            long now = System.currentTimeMillis();
            Long lockTime = locks.get(chatId);
            if (lockTime == null) return false;
            if ((now - lockTime) > ttlMillis) {
                locks.remove(chatId);
                return false;
            }
            return true;
        }

        private void pruneExpired(long now) {
            locks.entrySet().removeIf(e -> (now - e.getValue()) > ttlMillis);
        }
    }
}
