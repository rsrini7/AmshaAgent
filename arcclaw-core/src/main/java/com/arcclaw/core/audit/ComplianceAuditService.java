package com.arcclaw.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ComplianceAuditService {

    private static final Logger log = Logger.getLogger(ComplianceAuditService.class);

    private final AuditEventRepository auditEventRepository;
    private final AsyncPersistenceEngine persistenceEngine;

    protected ComplianceAuditService() {
        this.auditEventRepository = null;
        this.persistenceEngine = null;
    }

    @Inject
    public ComplianceAuditService(AuditEventRepository auditEventRepository,
                                  AsyncPersistenceEngine persistenceEngine) {
        this.auditEventRepository = auditEventRepository;
        this.persistenceEngine = persistenceEngine;
    }

    /**
     * Records a MANDATORY security event — fail-closed.
     *
     * <p>This method BLOCKS on the background write. If the write fails
     * after all retries, a {@link ComplianceViolationException} is thrown
     * and the caller (typically AgentLoop) MUST halt immediately.
     *
     * @throws ComplianceViolationException if the mandatory write cannot be persisted
     */
    public void recordMandatory(String sessionId, String userId, String agentRole,
                                SecurityEventType type, String detail) {
        log.infof("[AUDIT-MANDATORY] type=%s session=%s", type, sessionId);
        AuditEvent event = AuditEvent.securityEvent(sessionId, userId, agentRole, type, detail);
        PersistenceEvent pe = PersistenceEvent.critical(sessionId, type, () -> {
            auditEventRepository.record(event);
        });
        // Blocks — throws ComplianceViolationException if write fails
        persistenceEngine.submitCritical(pe);
    }
}
