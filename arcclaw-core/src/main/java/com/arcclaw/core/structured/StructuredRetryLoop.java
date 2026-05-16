package com.arcclaw.core.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.util.LogUtil;
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
    GovernanceStrategy governanceLayer;

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
        int attempt = 0;
        int maxRetries = 2;
        List<Exception> retryReasons = new ArrayList<>();
        
        try {
            while (attempt <= maxRetries) {
                try {
                    // Hard budget Check BEFORE call
                    governanceLayer.assertWithinBudget(sessionId);
                    T result = call.get();
                    
                    if (attempt > 0) {
                        log.infof("LLM call for %s succeeded on retry attempt %d", operationName, attempt);
                        String userId = WorkspaceScopeHolder.getUserIdOrUnknown();
                        auditService.record(sessionId, userId, agentRole, 
                                SecurityEventType.STRUCTURED_OUTPUT_RETRY, 
                                "Success after recovery. Attempt " + attempt + ". Reasons: " + retryReasons.stream().map(Exception::getMessage).collect(Collectors.joining("; ")));
                    }
                    return result;
                } catch (BudgetWarningException | JsonExtractionException e) {
                    attempt++;
                    retryReasons.add(e);
                    
                    if (attempt > maxRetries) {
                        log.errorf("Extraction failed after max retries (%d) for %s. Last error: %s", maxRetries, operationName, LogUtil.rootMessage(e));
                        String userId = WorkspaceScopeHolder.getUserIdOrUnknown();
                        auditService.record(sessionId, userId, agentRole, 
                                SecurityEventType.STRUCTURED_OUTPUT_RETRY, 
                                "Failed after max retries (" + maxRetries + "). Reasons: " + retryReasons.stream().map(Exception::getMessage).collect(Collectors.joining("; ")));
                        throw new StructuredOutputException("Failed to extract structured output after " + maxRetries + " retries: " + e.getMessage(), e);
                    }
                    
                    log.warnf("Retrying LLM call for %s (attempt %d/%d): %s", operationName, attempt, maxRetries, LogUtil.rootMessage(e));
                }
            }
            throw new IllegalStateException("Should not reach here");
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
}
