package com.arcclaw.core.audit;

import com.arcclaw.core.agent.AgentResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

@ApplicationScoped
public class AuditService {

    private static final Logger log = Logger.getLogger(AuditService.class);

    @Inject
    AuditEventRepository auditEventRepository;

    /**
     * Records a point-in-time security event.
     * Executes in a new transaction to ensure audit survives parent rollbacks.
     * Swallows all exceptions to prevent crashing the caller.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        try {
            AuditEvent event = AuditEvent.securityEvent(sessionId, userId, agentRole, type, detail);
            auditEventRepository.record(event);
        } catch (Exception e) {
            log.warnf(e, "[AUDIT] Failed to persist %s for session %s", type, sessionId);
        }
    }

    /**
     * Records the outcome of an agent loop execution.
     * Executes in a new transaction to ensure audit survives parent rollbacks.
     * Swallows all exceptions to prevent crashing the caller.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordLoopCompletion(String sessionId, String userId, String agentRole,
                                     String goalHash, AgentResult result, 
                                     int inputTokens, int outputTokens, BigDecimal usdCost) {
        try {
            AuditOutcome outcome = resolveOutcome(result);
            SecurityEventType eventType = resolveLoopEventType(outcome);
            String detail = result.terminationReason() != null ? "reason=" + result.terminationReason() : null;

            AuditEvent event = AuditEvent.of(sessionId, userId, agentRole, goalHash, outcome, eventType, 
                                             inputTokens, outputTokens, usdCost, result.steps().size(), detail);
            auditEventRepository.record(event);
        } catch (Exception e) {
            log.warnf(e, "[AUDIT] Failed to persist loop completion for session %s", sessionId);
        }
    }

    private AuditOutcome resolveOutcome(AgentResult result) {
        if (result.success()) return AuditOutcome.FINISH;
        if (result.awaitingApproval()) return AuditOutcome.AWAITING_APPROVAL;
        
        String reason = result.terminationReason();
        if (reason != null) {
            if (reason.contains("BUDGET_EXCEEDED")) return AuditOutcome.BUDGET_EXCEEDED;
            if (reason.contains("MAX_ITERATIONS")) return AuditOutcome.MAX_ITERATIONS;
            if (reason.contains("APPROVAL_TIMEOUT")) return AuditOutcome.APPROVAL_TIMEOUT;
            if (reason.contains("SYSTEM_ERROR")) return AuditOutcome.ERROR;
        }
        return AuditOutcome.ERROR;
    }
    
    private SecurityEventType resolveLoopEventType(AuditOutcome outcome) {
        return switch (outcome) {
            case FINISH -> SecurityEventType.LOOP_FINISH;
            case ERROR -> SecurityEventType.LOOP_ERROR;
            case BUDGET_EXCEEDED -> SecurityEventType.LOOP_BUDGET_EXCEEDED;
            case MAX_ITERATIONS -> SecurityEventType.LOOP_MAX_ITERATIONS;
            case AWAITING_APPROVAL -> SecurityEventType.LOOP_AWAITING_APPROVAL;
            case APPROVAL_TIMEOUT -> SecurityEventType.LOOP_ERROR; // Mapped to LOOP_ERROR
        };
    }
}
