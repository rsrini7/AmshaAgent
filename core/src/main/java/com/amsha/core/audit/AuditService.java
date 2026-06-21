package com.amsha.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.persistence.api.AuditEventStorePort;

@ApplicationScoped
public class AuditService {

    private final TelemetryAuditService telemetryAuditService;
    private final ComplianceAuditService complianceAuditService;
    private final AuditEventStorePort auditStore;

    protected AuditService() {
        this.telemetryAuditService = null;
        this.complianceAuditService = null;
        this.auditStore = null;
    }

    @Inject
    public AuditService(
            TelemetryAuditService telemetryAuditService,
            ComplianceAuditService complianceAuditService,
            AuditEventStorePort auditStore) {
        this.telemetryAuditService = telemetryAuditService;
        this.complianceAuditService = complianceAuditService;
        this.auditStore = auditStore;
    }

    public void record(String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        telemetryAuditService.record(sessionId, userId, agentRole, type, detail);
    }

    public void recordWithIdempotency(
            String sessionId,
            String userId,
            String agentRole,
            SecurityEventType type,
            String detail,
            String idempotencyKey) {
        telemetryAuditService.recordWithIdempotency(sessionId, userId, agentRole, type, detail, idempotencyKey);
    }

    public void recordMandatory(
            String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        complianceAuditService.recordMandatory(sessionId, userId, agentRole, type, detail);
    }

    /**
     * Shorthand for mandatory security events (COMPLIANCE tier) without a session context.
     */
    public void logSecurityEvent(SecurityEventType type, String userId, String detail) {
        recordMandatory(null, userId, null, type, detail);
    }

    public void recordLoopCompletion(
            String sessionId,
            String userId,
            String agentRole,
            String goalHash,
            AgentResult result,
            int inputTokens,
            int outputTokens,
            BigDecimal usdCost) {
        telemetryAuditService.recordLoopCompletion(
                sessionId, userId, agentRole, goalHash, result, inputTokens, outputTokens, usdCost);
    }

    public void waitForIdle() {
        telemetryAuditService.waitForIdle();
    }

    public boolean hasEventInSession(String sessionId, SecurityEventType eventType) {
        return auditStore.countEventsByType(sessionId, eventType.name()) > 0;
    }
}
