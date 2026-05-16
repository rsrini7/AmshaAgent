package com.arcclaw.core.agent.loop;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.LogUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    @Inject MeterRegistry registry;

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
        Timer.Sample sample = (registry != null) ? Timer.start(registry) : null;
        log.debugf("[TOOL] invoke start: tool=%s session=%s", toolName, sessionId);
        try (Scope _ = toolSpan.makeCurrent()) {
            ToolResponse response = toolRegistry.invokeTool(toolName, params);
            String rawObservation = response.output();
            String sanitizedObservation = observationSanitizer.sanitize(toolName, rawObservation);
            
            if (response instanceof ToolResponse.ToolFailure failure) {
                log.errorf("Tool '%s' execution failed in session %s: %s (exitCode=%d)", 
                        toolName, sessionId, sanitizedObservation, failure.exitCode());
            }

            return switch (response) {
                case ToolResponse.ToolSuccess _ -> ToolResponse.success(sanitizedObservation);
                case ToolResponse.ToolFailure failure -> new ToolResponse.ToolFailure(
                        failure.exitCode(), sanitizedObservation, failure.isInfraFailure(),
                        failure.errorCode(), failure.traceId(), failure.rootCauseClass());
                case ToolResponse.ToolPending pending -> new ToolResponse.ToolPending(
                        sanitizedObservation, pending.approvalToken());
            };
        } catch (RequiresApprovalException e) {
            // Re-throw for runner checkpoint persistence
            throw e;
        } catch (Exception e) {
            toolSpan.recordException(e);
            toolSpan.setStatus(StatusCode.ERROR, LogUtil.rootMessage(e));
            
            if (e instanceof SecurityException) {
                log.warnf("Tool '%s' blocked by governance in session %s: %s",
                        toolName, sessionId, LogUtil.rootMessage(e));
                return ToolResponse.failure(1, "Security violation: " + LogUtil.rootMessage(e));
            }

            log.errorf("Tool '%s' threw unexpected exception in session %s: %s",
                    toolName, sessionId, LogUtil.rootMessage(e));
            log.debugf("Full stacktrace for tool '%s' failure:\n%s", toolName, LogUtil.trimmedStack(e));
            return ToolResponse.failure(1, "Tool execution error: " + LogUtil.rootMessage(e));
        } finally {
            toolSpan.end();
            if (sample != null && registry != null) {
                sample.stop(registry.timer("arcclaw.tool.execution.time", "tool", toolName));
            }
        }
    }
}
