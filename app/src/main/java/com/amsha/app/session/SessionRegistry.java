package com.amsha.app.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.amsha.core.pipeline.SessionIdResolver;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.jboss.logging.Logger;

/**
 * Persistent session registry backed by the {@code SessionCatalogStore}.
 */
@ApplicationScoped
public class SessionRegistry implements SessionIdResolver {

    private static final Logger log = Logger.getLogger(SessionRegistry.class);

    @Inject
    SessionCatalogStore sessionCatalogStore;

    private final Cache<String, ReentrantLock> userLocks = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    protected String lookupSession(String channelType, String channelId, String userId) {
        return sessionCatalogStore
                .resolveActive(userId, channelType.toLowerCase(Locale.ROOT), channelId)
                .map(SessionCatalogEntry::sessionId)
                .orElse(null);
    }

    protected String insertSession(String channelType, String channelId, String userId, String newSessionId) {
        SessionCatalogEntry entry = new SessionCatalogEntry(
                UUID.randomUUID().toString(),
                userId,
                channelType.toLowerCase(Locale.ROOT),
                channelId,
                newSessionId,
                "coordinator",
                "",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");
        sessionCatalogStore.activateNew(entry);
        return newSessionId;
    }

    @Override
    public String resolveOrCreate(String channelType, String channelId, String userId) {
        String key = channelType.toLowerCase(Locale.ROOT) + ":" + channelId + ":" + userId;
        ReentrantLock lock = userLocks.get(key, k -> new ReentrantLock());
        lock.lock();
        try {
            String existing = lookupSession(channelType, channelId, userId);
            if (existing != null) return existing;

            // Legacy Upgrade fallback check: if original is lowercase, check uppercase
            if (!channelType.equals(channelType.toUpperCase(Locale.ROOT))) {
                String legacyUpper = lookupSession(channelType.toUpperCase(Locale.ROOT), channelId, userId);
                if (legacyUpper != null) return legacyUpper;
            }
            // If original is uppercase, check lowercase
            if (!channelType.equals(channelType.toLowerCase(Locale.ROOT))) {
                String legacyLower = lookupSession(channelType.toLowerCase(Locale.ROOT), channelId, userId);
                if (legacyLower != null) return legacyLower;
            }

            String newSessionId = UUID.randomUUID().toString();
            String id = insertSession(channelType, channelId, userId, newSessionId);
            log.infof("SessionRegistry: created session %s for [%s/%s/%s]", id, channelType, channelId, userId);
            return id;
        } finally {
            lock.unlock();
        }
    }
}
