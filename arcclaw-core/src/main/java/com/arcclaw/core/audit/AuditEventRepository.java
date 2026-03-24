package com.arcclaw.core.audit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

@ApplicationScoped
public class AuditEventRepository {

    @Inject
    EntityManager em;

    @PostConstruct
    void warmupQueryCache() {
        // Force HQL compilation at startup (on the main thread, before first transaction)
        // This prevents ANTLR cold-start under Narayana transaction timeout in native mode
        try {
            em.createNamedQuery("AuditEvent.recordInsert", AuditEvent.class)
                .setMaxResults(1);
        } catch (Exception ignored) {
            // Best-effort warmup; real queries will still work
        }
    }

    @Inject
    AuditHashUtil hashUtil;

    @Inject
    SessionAuditSequencer sequencer;

    /**
     * Persists an AuditEvent and computes its HMAC chain fields.
     * This method is deliberately NOT annotated with @Transactional.
     * It relies on the caller (AuditService) to provide the appropriate
     * REQUIRED transaction boundary.
     */
    public void record(AuditEvent event) {
        ReentrantLock lock = sequencer.lockFor(event.sessionId);
        lock.lock();
        try {
            // Step 1: assign monotonic sequence for this session
            List<Long> maxSeqResult = em.createQuery(
                    "SELECT COALESCE(MAX(a.sequence), 0) FROM AuditEvent a WHERE a.sessionId = :sid",
                    Long.class)
                    .setParameter("sid", event.sessionId)
                    .getResultList();
            event.sequence = (maxSeqResult.isEmpty() ? 0L : maxSeqResult.getFirst()) + 1;

            // Step 2: chain to previous hash
            String prevHash = findLatestHash(event.sessionId);
            if (prevHash == null) {
                prevHash = hashUtil.sha256hex("GENESIS:" + event.sessionId);
            }
            event.prevHash = prevHash;
            
            String payload = event.computePayloadStr();
            event.hmacHash = hashUtil.hmacHex(prevHash + "|" + payload);
            
            if (event.hmacHash == null) {
                Logger.getLogger(AuditEventRepository.class)
                    .errorf("CRITICAL: hmacHash is null for session %s. HMAC production failed unexpectedly.", event.sessionId);
                event.hmacHash = "error-null-fallback-" + UUID.randomUUID();
            }

            em.persist(event);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the latest HMAC hash for a specific session to continue the chain.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public String findLatestHash(String sessionId) {
        List<String> results = em.createQuery(
                "SELECT a.hmacHash FROM AuditEvent a WHERE a.sessionId = :sid ORDER BY a.sequence DESC",
                String.class)
                .setParameter("sid", sessionId)
                .setMaxResults(1)
                .getResultList();
        
        return results.isEmpty() ? null : results.getFirst();
    }


    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AuditEvent> findBySessionId(String sessionId) {
        return em.createQuery(
                "SELECT a FROM AuditEvent a WHERE a.sessionId = :sid ORDER BY a.sequence ASC",
                AuditEvent.class)
                .setParameter("sid", sessionId)
                .getResultList();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean hasEventInSession(String sessionId, SecurityEventType eventType) {
        Long count = em.createQuery(
                "SELECT COUNT(a) FROM AuditEvent a " +
                "WHERE a.sessionId = :sid AND a.eventType = :type",
                Long.class)
            .setParameter("sid", sessionId)
            .setParameter("type", eventType)
            .getSingleResult();
        return count > 0;
    }
}
