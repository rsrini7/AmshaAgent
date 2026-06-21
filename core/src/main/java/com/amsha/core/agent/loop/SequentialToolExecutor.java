package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.ToolResponse;

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

    @Inject
    public SequentialToolExecutor(ToolInvoker toolInvoker, ToolInvocationGuard guard) {
        this.toolInvoker = toolInvoker;
        this.guard = guard;
    }

    protected SequentialToolExecutor() {
        this(null, null);
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
     */
    public ToolResponse invoke(String toolName, Map<String, Object> params, String sessionId) {
        return toolInvoker.invoke(toolName, params, sessionId);
    }
}
