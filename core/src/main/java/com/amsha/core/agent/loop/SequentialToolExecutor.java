package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.tool.api.ToolResponse;

import org.jboss.logging.Logger;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Handles the sequential execution of a single tool call within the proper workspace context.
 * Used by ParallelToolStepExecutor for both parallel batches and pre-execution loops.
 */
@ApplicationScoped
public class SequentialToolExecutor {

    private static final Logger log = Logger.getLogger(SequentialToolExecutor.class);

    private final ToolInvoker toolInvoker;
    private final ToolInvocationGuard guard;
    private final TracePublisher tracePublisher;

    @Inject
    public SequentialToolExecutor(ToolInvoker toolInvoker, ToolInvocationGuard guard, TracePublisher tracePublisher) {
        this.toolInvoker = toolInvoker;
        this.guard = guard;
        this.tracePublisher = tracePublisher;
    }

    protected SequentialToolExecutor() {
        this(null, null, null);
    }

    /**
     * Executes a single ParallelToolCall.
     */
    public ParallelToolCall execute(AgentExecutionContext context, ParallelToolCall call, Context otelContext) {
        try (Scope _ = otelContext != null ? otelContext.makeCurrent() : null) {
            try {
                return WorkspaceContext.run(
                        context.sessionId(),
                        context.userId(),
                        context.chatId(),
                        context.autonomyLevel(),
                        context.agentRole(),
                        context.channelType(),
                        () -> {
                            ToolResponse response = invoke(call.toolName(), call.params(), context.sessionId());
                            return switch (response) {
                                case ToolResponse.ToolSuccess success -> {
                                    guard.recordToolCall(
                                            context.sessionId(), call.toolName(), call.params(), success.output());
                                    yield call.withResult(success.output(), true);
                                }
                                case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                                case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                            };
                        });
            } catch (Exception e) {
                log.errorf(
                        "[SEQUENTIAL-EXEC] session=%s tool=%s failed: %s",
                        context.sessionId(), call.toolName(), LogUtil.rootMessage(e));
                return call.withResult("Internal context or execution error: " + LogUtil.rootMessage(e), false);
            }
        }
    }

    /**
     * Executes a single tool call and returns a ToolResponse.
     *
     * <p>This is the single shared chokepoint for every parallel-batch and pre-execution tool
     * invocation, so the per-tool lifecycle trace (start / end / error) is emitted here rather
     * than in the callers. This is what makes the parallel path observable in the Visual Cockpit:
     * the sequential {@code ToolStepHandler} path emits its own trace, and the parallel path
     * bypasses that handler entirely, so without this instrumentation parallel tools would be
     * invisible on the live trace.
     */
    public ToolResponse invoke(String toolName, Map<String, Object> params, String sessionId) {
        publishToolTrace(TraceStage.TOOL_START, sessionId, toolName, "START");
        try {
            ToolResponse response = toolInvoker.invoke(toolName, params, sessionId);
            if (response instanceof ToolResponse.ToolSuccess) {
                publishToolTrace(TraceStage.TOOL_END, sessionId, toolName, "END");
            } else if (response instanceof ToolResponse.ToolFailure) {
                publishToolTrace(TraceStage.TOOL_ERROR, sessionId, toolName, "ERROR");
            }
            return response;
        } catch (RuntimeException e) {
            publishToolTrace(TraceStage.TOOL_ERROR, sessionId, toolName, "ERROR");
            throw e;
        }
    }

    /**
     * Emits a tool-lifecycle trace. Best-effort: a missing publisher (e.g. a lightweight unit test
     * built through the no-arg constructor) is skipped rather than changing the outcome of the step.
     */
    private void publishToolTrace(TraceStage stage, String sessionId, String toolName, String status) {
        if (tracePublisher == null) {
            return;
        }
        tracePublisher.publish(TracePublisher.newEvent(sessionId, stage)
                .withStation(StationCode.STATION_07_TOOL_EXECUTION)
                .withStatus(status)
                .withDetail(toolName));
    }
}
