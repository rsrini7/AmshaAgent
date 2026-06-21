package com.amsha.core.channel;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.TimeUnit;

import com.amsha.channel.api.ProcessingLockManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.quarkus.arc.DefaultBean;

/**
 * Default runtime implementation of {@link ProcessingLockManager} backed by
 * Caffeine with a 30-second TTL and a maximum of 100,000 concurrent lock
 * entries. Registered as an {@link ApplicationScoped} CDI bean.
 * <p>
 * Lock acquisition uses {@link Cache#asMap() asMap().putIfAbsent()} for
 * atomic check-and-set. Automatic TTL expiry via
 * {@link Caffeine#expireAfterWrite} prevents stale lock accumulation without
 * explicit pruning.
 */
@ApplicationScoped
@DefaultBean
public class CaffeineProcessingLockManager implements ProcessingLockManager {

    private static final Boolean PRESENT = Boolean.TRUE;

    private final Cache<String, Boolean> locks = Caffeine.newBuilder()
            .expireAfterWrite(DEFAULT_TTL_MILLIS, TimeUnit.MILLISECONDS)
            .maximumSize(100_000)
            .build();

    @Override
    public boolean tryLock(String chatId) {
        return locks.asMap().putIfAbsent(chatId, PRESENT) == null;
    }

    @Override
    public void unlock(String chatId) {
        locks.invalidate(chatId);
    }

    @Override
    public boolean isLocked(String chatId) {
        return locks.getIfPresent(chatId) != null;
    }
}
