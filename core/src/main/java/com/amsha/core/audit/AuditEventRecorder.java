package com.amsha.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Arrays;

import com.amsha.core.domain.model.AuditEvent;
import com.amsha.core.domain.model.AuditEventRecord;
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

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void record(AuditEvent event) {
        recordWithReceipt(event);
    }

    /**
     * Appends a chained audit event and returns the durable event ID. The store performs the
     * idempotency check under the same serialization boundary as sequence allocation.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public String recordWithReceipt(AuditEvent event) {
        byte[] resolvedKey = hashUtil.copyHmacKeyForLocalAppend();
        try {
            AuditEventDto finalized = auditStore.appendChained(toDto(event), (seed, sequence, previousHash) -> {
                AuditEventRecord chained = AuditEventRecord.fromDto(seed);
                chained.setSequence(sequence);
                if (resolvedKey == null) {
                    hashUtil.calculateChainFields(chained, previousHash);
                } else {
                    hashUtil.calculateChainFields(chained, previousHash, resolvedKey);
                }
                validateBeforePersist(chained);
                return toDto(chained);
            });
            event.setSequence(finalized.sequence());
            event.setPrevHash(finalized.prevHash());
            event.setHmacHash(finalized.hmacHash());
            return finalized.id().toString();
        } finally {
            if (resolvedKey != null) {
                Arrays.fill(resolvedKey, (byte) 0);
            }
        }
    }

    public void validateBeforePersist(AuditEvent event) {
        if (event.getSessionId() == null || event.getSessionId().isBlank()) {
            throw new IllegalStateException("AuditEvent.sessionId must not be null or blank");
        }
        if (event.getPrevHash() == null || event.getPrevHash().isBlank()) {
            throw new IllegalStateException("AuditEvent.prevHash must not be null; genesis hash failed");
        }
        if (event.getHmacHash() == null || event.getHmacHash().isBlank()) {
            throw new IllegalStateException("AuditEvent.hmacHash must not be null; HMAC failed");
        }
        if (event.getSequence() <= 0) {
            throw new IllegalStateException("AuditEvent.sequence must be positive");
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public String findLatestHash(String sessionId) {
        return auditStore.findLatestHash(sessionId).orElse(null);
    }

    AuditEventDto toDto(AuditEvent entity) {
        return new AuditEventDto(
                entity.getId(),
                entity.getSessionId(),
                entity.getUserId(),
                entity.getAgentRole(),
                entity.getGoalHash(),
                entity.getOutcome() == null ? null : entity.getOutcome().name(),
                entity.getEventType() == null ? null : entity.getEventType().name(),
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
