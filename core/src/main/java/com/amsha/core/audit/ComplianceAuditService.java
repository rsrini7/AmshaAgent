package com.amsha.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.domain.model.AuditEvent;
import com.amsha.core.domain.model.AuditEventRecord;
import com.amsha.core.persistence.AsyncPersistenceEngine;
import com.amsha.core.persistence.PersistenceEvent;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ComplianceAuditService {

    private static final Logger log = Logger.getLogger(ComplianceAuditService.class);

    private final AuditEventRecorder auditEventRecorder;
    private final AsyncPersistenceEngine persistenceEngine;

    protected ComplianceAuditService() {
        this.auditEventRecorder = null;
        this.persistenceEngine = null;
    }

    @Inject
    public ComplianceAuditService(AuditEventRecorder auditEventRecorder, AsyncPersistenceEngine persistenceEngine) {
        this.auditEventRecorder = auditEventRecorder;
        this.persistenceEngine = persistenceEngine;
    }

    /**
     * Records a MANDATORY security event - fail-closed.
     *
     * <p>This method BLOCKS on the background write. If the write fails
     * after all retries, a {@link ComplianceViolationException} is thrown
     * and the caller (typically AgentLoop) MUST halt immediately.
     *
     * @throws ComplianceViolationException if the mandatory write cannot be persisted
     */
    public void recordMandatory(
            String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        recordMandatory(sessionId, userId, agentRole, type, detail, null);
    }

    public void recordMandatory(
            String sessionId,
            String userId,
            String agentRole,
            SecurityEventType type,
            String detail,
            String idempotencyKey) {
        submitMandatory(sessionId, userId, agentRole, type, detail, idempotencyKey, false);
    }

    /**
     * Records a mandatory security event and returns its durable HMAC audit event ID.
     *
     * <p>Used by the tool-attempt outbox so its acknowledgement references the exact event
     * persisted by the mandatory audit path.
     */
    public String recordMandatoryWithReceipt(
            String sessionId,
            String userId,
            String agentRole,
            SecurityEventType type,
            String detail,
            String idempotencyKey) {
        return submitMandatory(sessionId, userId, agentRole, type, detail, idempotencyKey, true);
    }

    private String submitMandatory(
            String sessionId,
            String userId,
            String agentRole,
            SecurityEventType type,
            String detail,
            String idempotencyKey,
            boolean returnReceipt) {
        log.infof("[AUDIT-MANDATORY] type=%s session=%s", type, sessionId);
        AuditEvent event = AuditEventRecord.securityEvent(sessionId, userId, agentRole, type, detail);
        event.setIdempotencyKey(idempotencyKey);
        AtomicReference<String> auditEventId = new AtomicReference<>();
        PersistenceEvent pe = PersistenceEvent.critical(sessionId, type, () -> {
            if (returnReceipt) {
                auditEventId.set(auditEventRecorder.recordWithReceipt(event));
            } else {
                auditEventRecorder.record(event);
            }
        });
        // Blocks - throws ComplianceViolationException if write fails
        persistenceEngine.submitCritical(pe);
        return returnReceipt ? auditEventId.get() : null;
    }
}
