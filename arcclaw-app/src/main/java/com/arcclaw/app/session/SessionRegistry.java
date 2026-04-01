package com.arcclaw.app.session;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import com.arcclaw.core.pipeline.SessionIdResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

/**
 * Persistent session registry backed by the {@code session_registry} table.
 * <p>
 * Replaces the UUID v3 (MD5) derivation previously used in
 * {@code SessionResolutionHandler}. Each unique (channelType, channelId, userId)
 * triplet gets exactly one stable UUID v4 session ID, created on first contact
 * and thereafter always returned unchanged.
 *
 * <p><b>Nuance #17:</b> Uses raw {@code EntityManager} — no Panache.
 * <p><b>Nuance #29:</b> Writes use {@code TxType.REQUIRES_NEW} to avoid
 * enlisting Narayana XA in a read that SQLite has already auto-committed.
 */
@ApplicationScoped
public class SessionRegistry implements SessionIdResolver {

    private static final Logger log = Logger.getLogger(SessionRegistry.class);

    @Inject
    EntityManager em;

    private final Cache<String, ReentrantLock> userLocks = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build();

    protected String lookupSession(String channelType, String channelId, String userId) {
        try {
            return em.createQuery(
                "SELECT s.sessionId FROM SessionRegistryEntry s " +
                "WHERE s.channelType = :ct AND s.channelId = :ci AND s.userId = :uid",
                String.class)
                .setParameter("ct", channelType)
                .setParameter("ci", channelId)
                .setParameter("uid", userId)
                .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    protected String insertSession(String channelType, String channelId, String userId, String newSessionId) {
        SessionRegistryEntry entry = new SessionRegistryEntry();
        entry.channelType = channelType;
        entry.channelId   = channelId;
        entry.userId      = userId;
        entry.sessionId   = newSessionId;
        entry.createdAt   = Instant.now();
        em.persist(entry);
        em.flush();
        return newSessionId;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
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
