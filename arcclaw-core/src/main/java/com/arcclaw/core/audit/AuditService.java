package com.arcclaw.core.audit;

import com.arcclaw.core.agent.result.AgentResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;

@ApplicationScoped
public class AuditService {

    private final TelemetryAuditService telemetryAuditService;
    private final ComplianceAuditService complianceAuditService;

    protected AuditService() {
        this.telemetryAuditService = null;
        this.complianceAuditService = null;
    }

    @Inject
    public AuditService(TelemetryAuditService telemetryAuditService,
                        ComplianceAuditService complianceAuditService) {
        this.telemetryAuditService = telemetryAuditService;
        this.complianceAuditService = complianceAuditService;
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
}
