package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.events.ToolExecutionEvent;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * Standardized executor for tool calls.
 * Wraps {@link ToolRegistry} with consistent exception handling.
 */
@ApplicationScoped
public class ToolInvoker {
    private static final Logger log = Logger.getLogger(ToolInvoker.class);

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    ToolObservationSanitizer observationSanitizer;

    @Inject
    AgentOtelContext agentOtelContext;

    @Inject
    MeterRegistry registry;

    @Inject
    Event<ToolExecutionEvent> toolEvent;

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
        // Fire TOOL_CALL event for self-evolve trace capture
        String userId = WorkspaceContext.USER_ID.isBound() ? WorkspaceContext.USER_ID.get() : "unknown";
        String role = WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null;
        toolEvent.fireAsync(ToolExecutionEvent.call(toolName, sessionId, userId, role, params, null));
        try (Scope _ = toolSpan.makeCurrent()) {
            ToolResponse response = toolRegistry.invokeTool(toolName, params);
            String rawObservation = response.output();
            String sanitizedObservation = observationSanitizer.sanitize(toolName, rawObservation);

            if (response instanceof ToolResponse.ToolFailure failure) {
                int exit = failure.exitCode();
                if (exit == 403) {
                    log.warnf(
                            "Tool '%s' denied by capability/permission in session %s: %s (exitCode=%d)",
                            toolName, sessionId, sanitizedObservation, exit);
                } else {
                    log.errorf(
                            "Tool '%s' execution failed in session %s: %s (exitCode=%d)",
                            toolName, sessionId, sanitizedObservation, exit);
                }
            }

            ToolResponse result =
                    switch (response) {
                        case ToolResponse.ToolSuccess _ -> ToolResponse.success(sanitizedObservation);
                        case ToolResponse.ToolFailure failure ->
                            new ToolResponse.ToolFailure(
                                    failure.exitCode(),
                                    sanitizedObservation,
                                    failure.isInfraFailure(),
                                    failure.errorCode(),
                                    failure.traceId(),
                                    failure.rootCauseClass());
                        case ToolResponse.ToolPending pending ->
                            new ToolResponse.ToolPending(sanitizedObservation, pending.approvalToken());
                    };
            // Fire TOOL_RESULT/TOOL_ERROR event for self-evolve trace capture
            boolean isSuccess = result instanceof ToolResponse.ToolSuccess;
            toolEvent.fireAsync(ToolExecutionEvent.result(
                    toolName, sessionId, userId, role, sanitizedObservation, isSuccess, null));
            return result;
        } catch (RequiresApprovalException e) {
            // Re-throw for runner checkpoint persistence
            throw e;
        } catch (Exception e) {
            toolSpan.recordException(e);
            toolSpan.setStatus(StatusCode.ERROR, LogUtil.rootMessage(e));

            if (e instanceof SecurityException) {
                log.warnf(
                        "Tool '%s' blocked by governance in session %s: %s",
                        toolName, sessionId, LogUtil.rootMessage(e));
                toolEvent.fireAsync(ToolExecutionEvent.result(
                        toolName,
                        sessionId,
                        userId,
                        role,
                        "Security violation: " + LogUtil.rootMessage(e),
                        false,
                        null));
                return ToolResponse.failure(1, "Security violation: " + LogUtil.rootMessage(e));
            }

            log.errorf(
                    "Tool '%s' threw unexpected exception in session %s: %s",
                    toolName, sessionId, LogUtil.rootMessage(e));
            log.debugf("Full stacktrace for tool '%s' failure:\n%s", toolName, LogUtil.trimmedStack(e));
            toolEvent.fireAsync(ToolExecutionEvent.result(
                    toolName, sessionId, userId, role, "Tool execution error: " + LogUtil.rootMessage(e), false, null));
            return ToolResponse.failure(1, "Tool execution error: " + LogUtil.rootMessage(e));
        } finally {
            toolSpan.end();
            if (sample != null && registry != null) {
                sample.stop(registry.timer("amsha.tool.execution.time", "tool", toolName));
            }
        }
    }
}
