package com.arcclaw.app.session;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.SessionIdResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

/**
 * Persistent session registry backed by the {@code session_registry} table using JDBI.
 * Inactive when no database backend is available (e.g., filesystem mode).
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.flyway.enabled", stringValue = "true", enableIfMissing = true)
public class SessionRegistry implements SessionIdResolver {

    private static final Logger log = Logger.getLogger(SessionRegistry.class);

    private final Jdbi jdbi;

    private final Cache<String, ReentrantLock> userLocks = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build();

    @Inject
    public SessionRegistry(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SessionRegistry() {
        this(null);
    }

    protected String lookupSession(String channelType, String channelId, String userId) {
        return jdbi.withHandle(handle ->
            handle.createQuery("SELECT session_id FROM session_registry WHERE channel_type = :ct AND channel_id = :ci AND user_id = :uid")
                .bind("ct", channelType)
                .bind("ci", channelId)
                .bind("uid", userId)
                .mapTo(String.class)
                .findFirst()
                .orElse(null)
        );
    }

    protected String insertSession(String channelType, String channelId, String userId, String newSessionId) {
        jdbi.useHandle(handle ->
            handle.createUpdate("INSERT INTO session_registry (channel_type, channel_id, user_id, session_id, created_at) " +
                                "VALUES (:ct, :ci, :uid, :sid, :createdAt)")
                .bind("ct", channelType)
                .bind("ci", channelId)
                .bind("uid", userId)
                .bind("sid", newSessionId)
                .bind("createdAt", Instant.now())
                .execute()
        );
        return newSessionId;
    }

    @Override
    public String resolveOrCreate(String channelType, String channelId, String userId) {
        String key = channelType + ":" + channelId + ":" + userId;
        ReentrantLock lock = userLocks.get(key, k -> new ReentrantLock());
        lock.lock();
        try {
            String existing = lookupSession(channelType, channelId, userId);
            if (existing != null) return existing;

            String newSessionId = UUID.randomUUID().toString();
            String id = insertSession(channelType, channelId, userId, newSessionId);
            log.infof("SessionRegistry: created session %s for [%s/%s/%s]",
                      id, channelType, channelId, userId);
            return id;
        } finally {
            lock.unlock();
        }
    }
}
