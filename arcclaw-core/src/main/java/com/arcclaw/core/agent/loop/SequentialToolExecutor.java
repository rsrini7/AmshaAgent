package com.arcclaw.core.agent.loop;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.tools.ToolResponse;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.jboss.logging.Logger;

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
    public SequentialToolExecutor(ToolInvoker toolInvoker,
                                  ToolInvocationGuard guard) {
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
                    context.sessionId(), context.userId(), context.chatId(),
                    context.autonomyLevel(), context.agentRole(), context.channelType(),
                    () -> {
                        ToolResponse response = invoke(call.toolName(), call.params(), context.sessionId());
                        if (response.success()) {
                            guard.recordToolCall(context.sessionId(), call.toolName(), call.params(), response.output());
                        }
                        return call.withResult(response.output(), response.success());
                    }
                );
            } catch (Exception e) {
                log.errorf("[SEQUENTIAL-EXEC] session=%s tool=%s failed: %s",
                          context.sessionId(), call.toolName(), e.getMessage());
                return call.withResult("Internal context or execution error: " + e.getMessage(), false);
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
