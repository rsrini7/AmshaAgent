package com.arcclaw.core.audit;

import java.math.BigDecimal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.result.AgentResult;

@ApplicationScoped
public class AuditService {

    private final TelemetryAuditService telemetryAuditService;
    private final ComplianceAuditService complianceAuditService;
    private final AuditEventRepository auditEventRepository;

    protected AuditService() {
        this.telemetryAuditService = null;
        this.complianceAuditService = null;
        this.auditEventRepository = null;
    }

    @Inject
    public AuditService(TelemetryAuditService telemetryAuditService,
                        ComplianceAuditService complianceAuditService,
                        AuditEventRepository auditEventRepository) {
        this.telemetryAuditService = telemetryAuditService;
        this.complianceAuditService = complianceAuditService;
        this.auditEventRepository = auditEventRepository;
    }

    public void record(String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        telemetryAuditService.record(sessionId, userId, agentRole, type, detail);
    }

    public void recordMandatory(String sessionId, String userId, String agentRole,
                                SecurityEventType type, String detail) {
        complianceAuditService.recordMandatory(sessionId, userId, agentRole, type, detail);
    }

    public void recordLoopCompletion(String sessionId, String userId, String agentRole,
                                     String goalHash, AgentResult result,
                                     int inputTokens, int outputTokens, BigDecimal usdCost) {
        telemetryAuditService.recordLoopCompletion(sessionId, userId, agentRole, goalHash, result, inputTokens, outputTokens, usdCost);
    }

    public void waitForIdle() {
        telemetryAuditService.waitForIdle();
    }

    public boolean hasEventInSession(String sessionId, SecurityEventType eventType) {
        return auditEventRepository.hasEventInSession(sessionId, eventType);
    }
    
}
