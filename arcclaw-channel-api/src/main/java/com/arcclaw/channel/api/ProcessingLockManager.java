package com.arcclaw.channel.api;

/**
 * Pluggable lock manager for chat-level processing serialization.
 * <p>
 * A default runtime implementation is provided outside this API module and
 * uses Caffeine with a 30-second TTL. For clustered deployments, provide a
 * CDI bean backed by Redis or database advisory locks that overrides this
 * default.
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
}
