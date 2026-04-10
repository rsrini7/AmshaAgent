package com.arcclaw.core.audit;

import java.math.BigDecimal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.config.SystemMetadataRegistry;
import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TelemetryAuditService {

    private static final Logger log = Logger.getLogger(TelemetryAuditService.class);

    private final AuditEventRepository auditEventRepository;
    private final AsyncPersistenceEngine persistenceEngine;
    private final SystemMetadataRegistry registry;

    protected TelemetryAuditService() {
        this.auditEventRepository = null;
        this.persistenceEngine = null;
        this.registry = null;
    }

    @Inject
    public TelemetryAuditService(AuditEventRepository auditEventRepository,
                                 AsyncPersistenceEngine persistenceEngine,
                                 SystemMetadataRegistry registry) {
        this.auditEventRepository = auditEventRepository;
        this.persistenceEngine = persistenceEngine;
        this.registry = registry;
    }

    /**
     * Records a point-in-time security event.
     * Non-critical path — TELEMETRY tier (fire-and-forget).
     * Swallows all exceptions to prevent crashing the caller.
     */
    public void record(String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        recordWithIdempotency(sessionId, userId, agentRole, type, detail, null);
    }

    /**
     * Records a point-in-time security event with an optional idempotency key.
     */
    public void recordWithIdempotency(String sessionId, String userId, String agentRole,
                                      SecurityEventType type, String detail, String idempotencyKey) {
        log.infof("[AUDIT-PROBE] type=%s session=%s idempotency=%s", type, sessionId, idempotencyKey);
        try {
            AuditEvent event = AuditEvent.securityEvent(sessionId, userId, agentRole, type, detail);
            event.appVersion = registry.getAppVersion();
            event.idempotencyKey = idempotencyKey;
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
            event.appVersion = registry.getAppVersion();
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
            case AgentResult.StructuredFinished _ -> AuditOutcome.FINISH;
            case AgentResult.AwaitingApproval _ -> AuditOutcome.AWAITING_APPROVAL;
            case AgentResult.AwaitingRetry _ -> AuditOutcome.AWAITING_RETRY;
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
            case AWAITING_RETRY -> SecurityEventType.LOOP_AWAITING_RETRY;
            case SECURITY_EVENT -> SecurityEventType.SECURITY;
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
