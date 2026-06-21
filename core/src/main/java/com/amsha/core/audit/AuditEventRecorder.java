package com.amsha.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.concurrent.locks.ReentrantLock;

import com.amsha.core.domain.model.AuditEvent;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class AuditEventRecorder {

    @Inject
    AuditEventStorePort auditStore;

    @Inject
    AuditHashUtil hashUtil;

    @Inject
    SessionAuditSequencer sequencer;

    @Transactional
    public void record(AuditEvent event) {
        if (event.getIdempotencyKey() != null) {
            if (auditStore.countByIdempotencyKey(event.getIdempotencyKey()) > 0) {
                return;
            }
        }

        ReentrantLock lock = sequencer.lockFor(event.getSessionId());
        lock.lock();
        try {
            long currentMax = auditStore.getMaxSequence(event.getSessionId());
            event.setSequence(currentMax + 1);

            String prevHash = findLatestHash(event.getSessionId());
            hashUtil.calculateChainFields(event, prevHash);

            validateBeforePersist(event);
            auditStore.save(toDto(event));
        } finally {
            lock.unlock();
        }
    }

    public void validateBeforePersist(AuditEvent event) {
        if (event.getSessionId() == null || event.getSessionId().isBlank())
            throw new IllegalStateException("AuditEvent.sessionId must not be null/blank");
        if (event.getPrevHash() == null || event.getPrevHash().isBlank())
            throw new IllegalStateException("AuditEvent.prevHash must not be null - genesis hash failed");
        if (event.getHmacHash() == null || event.getHmacHash().isBlank())
            throw new IllegalStateException("AuditEvent.hmacHash must not be null - HMAC failed");
        if (event.getSequence() < 0) throw new IllegalStateException("AuditEvent.sequence must be >= 0");
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public String findLatestHash(String sessionId) {
        return auditStore.findLatestHash(sessionId).orElse(null);
    }

    private AuditEventDto toDto(AuditEvent entity) {
        return new AuditEventDto(
                entity.getId(),
                entity.getSessionId(),
                entity.getUserId(),
                entity.getAgentRole(),
                entity.getGoalHash(),
                entity.getOutcome() != null ? entity.getOutcome().name() : null,
                entity.getEventType() != null ? entity.getEventType().name() : null,
                entity.getEventDetail(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getUsdCost(),
                entity.getTimestamp(),
                entity.getIterationsUsed(),
                entity.getAppVersion(),
                entity.getIdempotencyKey(),
                entity.getSequence(),
                entity.getHmacHash(),
                entity.getPrevHash());
    }
}
