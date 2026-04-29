package com.arcclaw.core.agent.loop;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.jboss.logging.Logger;

/**
 * Standardized executor for tool calls.
 * Wraps {@link ToolRegistry} with consistent exception handling.
 */
@ApplicationScoped
public class ToolInvoker {
    private static final Logger log = Logger.getLogger(ToolInvoker.class);

    @Inject ToolRegistry toolRegistry;
    @Inject ToolObservationSanitizer observationSanitizer;
    @Inject AgentOtelContext agentOtelContext;

    /**
     * Invokes a tool and wraps exceptions into a {@link ToolResponse}.
     * <p>
     * Re-throws {@link RequiresApprovalException} so the runner can pause the loop.
     *
     * @param toolName  The unique tool name.
     * @param params    Tool parameters.
     * @param sessionId For logging only.
     * @return The tool response.
     * @throws RequiresApprovalException If the tool requires human approval.
     */
    public ToolResponse invoke(String toolName, Map<String, Object> params, String sessionId) {
        Span toolSpan = agentOtelContext.startToolSpan(sessionId, toolName, "invoke");
        try (Scope scope = toolSpan.makeCurrent()) {
            ToolResponse response = toolRegistry.invokeTool(toolName, params);
            String rawObservation = response.output();
            String sanitizedObservation = observationSanitizer.sanitize(toolName, rawObservation);
            if (!response.success()) {
                log.errorf("Tool '%s' execution failed in session %s: %s (exitCode=%d)", toolName, sessionId, sanitizedObservation, response.exitCode());
            }
            return new ToolResponse(response.exitCode(), sanitizedObservation, response.success(), response.isInfraFailure(),
                    response.errorCode(), response.traceId(), response.rootCauseClass());
        } catch (RequiresApprovalException e) {
            // Re-throw for runner checkpoint persistence
            throw e;
        } catch (Exception e) {
            toolSpan.recordException(e);
            toolSpan.setStatus(StatusCode.ERROR, e.getMessage());
            
            if (e instanceof SecurityException) {
                log.warnf("Tool '%s' blocked by governance in session %s: %s", toolName, sessionId, e.getMessage());
                return ToolResponse.failure(1, "Security violation: " + e.getMessage());
            }

            log.errorf("Tool '%s' threw unexpected exception in session %s: %s", toolName, sessionId, e.getMessage());
            log.debugf(e, "Full stacktrace for tool failure: %s", toolName);
            return ToolResponse.failure(1, "Tool execution error: " + e.getMessage());
        } finally {
            toolSpan.end();
        }
    }
}
