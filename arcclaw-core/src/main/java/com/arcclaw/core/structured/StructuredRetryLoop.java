package com.arcclaw.core.structured;

import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.opentelemetry.api.trace.Span;
import org.jboss.logging.Logger;

/**
 * Executes an LLM call with at most one schema-correction retry. 
 * Handles budget gates, OTel spans, and audit records.
 */
@ApplicationScoped
public class StructuredRetryLoop {
    private static final Logger log = Logger.getLogger(StructuredRetryLoop.class);

    @Inject
    GovernanceLayer governanceLayer;

    @Inject
    TelemetryAuditService auditService;

    @Inject
    AgentOtelContext otelContext;

    /**
     * Executes the call with optional single retry.
     * 
     * @param sessionId The current session ID
     * @param agentRole The role of the agent making the call
     * @param operationName Name of the operation for OTel
     * @param call The primary LLM call + extraction logic
     * @return The extracted result
     * @throws StructuredOutputException if all retries fail
     */
    public <T> T execute(String sessionId, String agentRole, String operationName, Supplier<T> call) {
        Span span = otelContext.childSpan(sessionId, operationName);
        try {
            // Attempt 1
            return executeWithBudgetGate(sessionId, call);
        } catch (BudgetWarningException e1) {
            log.warnf("Budget warning for session %s during %s: %s", sessionId, operationName, e1.getMessage());
            // Absorb warning and proceed
            return call.get();
        } catch (JsonExtractionException e1) {
            log.warnf("First extraction attempt failed for %s. Retrying...", operationName);
            
            // Attempt 2 (Single Retry)
            try {
                T result = executeWithBudgetGate(sessionId, call);
                
                // Record success after retry in Audit
                String userId = WorkspaceScopeHolder.getUserIdOrUnknown();
                auditService.record(sessionId, userId, agentRole, 
                        SecurityEventType.STRUCTURED_OUTPUT_RETRY, 
                        "Success after recovery: " + e1.getMessage());
                
                return result;
            } catch (BudgetWarningException ew) {
                log.warnf("Budget warning during retry for %s: %s", operationName, ew.getMessage());
                return call.get();
            } catch (JsonExtractionException e2) {
                log.errorf("Extraction failed after retry for %s. Raw: %s", operationName, e2.getRawResponse());
                throw new StructuredOutputException("Failed to extract structured output after retry: " + e2.getMessage(), e2);
            }
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    private <T> T executeWithBudgetGate(String sessionId, Supplier<T> call) {
        // Hard budget Check BEFORE call
        governanceLayer.assertWithinBudget(sessionId);
        return call.get();
    }
}
