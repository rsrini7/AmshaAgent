package com.arcclaw.core.audit;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

@ApplicationScoped
public class AuditService {

    private static final Logger log = Logger.getLogger(AuditService.class);

    @Inject
    AuditEventRepository auditEventRepository;

    @Inject
    AsyncPersistenceEngine persistenceEngine;

    /**
     * Records a point-in-time security event.
     * Non-critical path — TELEMETRY tier (fire-and-forget).
     * Swallows all exceptions to prevent crashing the caller.
     */
    public void record(String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        log.infof("[AUDIT-PROBE] type=%s session=%s", type, sessionId);
        try {
            AuditEvent event = AuditEvent.securityEvent(sessionId, userId, agentRole, type, detail);
            PersistenceEvent pe = PersistenceEvent.telemetry(sessionId, type, em -> {
                auditEventRepository.record(event);
            });
            persistenceEngine.submitTelemetry(pe);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("[TEST-EXPECTED]")) {
                log.warnf("[AUDIT] Failed to submit TELEMETRY event %s for session %s: %s (EXPECTED)", type, sessionId, e.getMessage());
            } else {
                log.warnf(e, "[AUDIT] Failed to submit TELEMETRY event %s for session %s", type, sessionId);
            }
        }
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
        PersistenceEvent pe = PersistenceEvent.critical(sessionId, type, em -> {
            auditEventRepository.record(event);
        });
        // Blocks — throws ComplianceViolationException if write fails
        persistenceEngine.submitCritical(pe);
    }

    /**
     * Records the outcome of an agent loop execution — TELEMETRY tier.
     * Swallows all exceptions to prevent crashing the caller.
     */
    public void recordLoopCompletion(String sessionId, String userId, String agentRole,
                                     String goalHash, AgentResult result,
                                     int inputTokens, int outputTokens, BigDecimal usdCost) {
        try {
            AuditOutcome outcome = resolveOutcome(result);
            SecurityEventType eventType = resolveLoopEventType(outcome);
            String detail = switch(result) {
                case AgentResult.Failed f -> "reason=" + f.reason();
                case AgentResult.LoopExceeded _ -> "reason=MAX_ITERATIONS";
                default -> null;
            };

            AuditEvent event = AuditEvent.of(sessionId, userId, agentRole, goalHash, outcome, eventType,
                                             inputTokens, outputTokens, usdCost, result.steps().size(), detail);
            PersistenceEvent pe = PersistenceEvent.telemetry(sessionId, eventType, em -> {
                auditEventRepository.record(event);
            });
            persistenceEngine.submitTelemetry(pe);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("[TEST-EXPECTED]")) {
                log.warnf("[AUDIT] Failed to submit loop completion TELEMETRY for session %s: %s (EXPECTED)", sessionId, e.getMessage());
            } else {
                log.warnf(e, "[AUDIT] Failed to submit loop completion TELEMETRY for session %s", sessionId);
            }
        }
    }

    private AuditOutcome resolveOutcome(AgentResult result) {
        return switch(result) {
            case AgentResult.Finished _ -> AuditOutcome.FINISH;
            case AgentResult.AwaitingApproval _ -> AuditOutcome.AWAITING_APPROVAL;
            case AgentResult.LoopExceeded _ -> AuditOutcome.MAX_ITERATIONS;
            case AgentResult.Failed f -> {
                if (f.category() == AgentResult.FailureCategory.BUDGET_EXCEEDED) yield AuditOutcome.BUDGET_EXCEEDED;
                yield AuditOutcome.ERROR;
            }
        };
    }

    private SecurityEventType resolveLoopEventType(AuditOutcome outcome) {
        return switch (outcome) {
            case FINISH -> SecurityEventType.LOOP_FINISH;
            case ERROR -> SecurityEventType.LOOP_ERROR;
            case BUDGET_EXCEEDED -> SecurityEventType.LOOP_BUDGET_EXCEEDED;
            case MAX_ITERATIONS -> SecurityEventType.LOOP_MAX_ITERATIONS;
            case AWAITING_APPROVAL -> SecurityEventType.LOOP_AWAITING_APPROVAL;
            case APPROVAL_TIMEOUT -> SecurityEventType.LOOP_ERROR;
        };
    }
    /**
     * Blocks until all pending asynchronous audit events are processed.
     * Use only in tests to avoid race conditions during assertions.
     */
    public void waitForIdle() {
        persistenceEngine.waitForIdle();
    }
}
