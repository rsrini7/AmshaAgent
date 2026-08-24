package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.context.Context;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class SequentialToolExecutorTest {

    @Mock
    ToolInvoker toolInvoker;

    @Mock
    ToolInvocationGuard guard;

    SequentialToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SequentialToolExecutor(toolInvoker, guard);
    }

    @Test
    void invoke_DelegatesToToolInvoker() {
        Map<String, Object> params = Map.of("query", "hello");
        when(toolInvoker.invoke("search", params, "sess-1")).thenReturn(ToolResponse.success("result"));

        ToolResponse response = executor.invoke("search", params, "sess-1");

        assertEquals("result", response.output());
        assertTrue(response.success());
        verify(toolInvoker).invoke("search", params, "sess-1");
    }

    @Test
    void execute_WithToolSuccess_RecordsToolCallAndReturnsResult() {
        var ctx = context();
        var call = ParallelToolCall.pending("call_0", "search", Map.of("q", "test"), "{\"q\":\"test\"}");
        when(toolInvoker.invoke(eq("search"), any(), eq("sess-1"))).thenReturn(ToolResponse.success("found"));

        ParallelToolCall result = executor.execute(ctx, call, Context.current());

        assertTrue(result.success());
        assertEquals("found", result.observation());
        verify(guard).recordToolCall(eq("sess-1"), eq("search"), any(), eq("found"));
    }

    @Test
    void execute_WithToolFailure_ReturnsFailedResult() {
        var ctx = context();
        var call = ParallelToolCall.pending("call_0", "calc", Map.of(), "{}");
        when(toolInvoker.invoke(eq("calc"), any(), eq("sess-1")))
                .thenReturn(ToolResponse.failure(1, "division by zero"));

        ParallelToolCall result = executor.execute(ctx, call, Context.current());

        assertFalse(result.success());
        assertTrue(result.observation().contains("division by zero"));
    }

    @Test
    void execute_WithToolPending_ReturnsFailedWithPendingOutput() {
        var ctx = context();
        var call = ParallelToolCall.pending("call_0", "approve_me", Map.of(), "{}");
        when(toolInvoker.invoke(eq("approve_me"), any(), eq("sess-1")))
                .thenReturn(ToolResponse.pending("need approval", "tok_x"));

        ParallelToolCall result = executor.execute(ctx, call, Context.current());

        assertFalse(result.success());
        assertEquals("need approval", result.observation());
    }

    @Test
    void execute_WithOtelContextNull_DoesNotThrow() {
        var ctx = context();
        var call = ParallelToolCall.pending("call_0", "search", Map.of(), "{}");
        when(toolInvoker.invoke(eq("search"), any(), eq("sess-1"))).thenReturn(ToolResponse.success("ok"));

        assertDoesNotThrow(() -> executor.execute(ctx, call, null));
    }

    @Test
    void execute_WhenToolInvokerThrows_ReturnsErrorResult() {
        var ctx = context();
        var call = ParallelToolCall.pending("call_0", "search", Map.of(), "{}");
        when(toolInvoker.invoke(eq("search"), any(), eq("sess-1"))).thenThrow(new RuntimeException("timeout"));

        ParallelToolCall result = executor.execute(ctx, call, Context.current());

        assertFalse(result.success());
        assertTrue(result.observation().contains("timeout"));
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "researcher",
                "search the web",
                AutonomyLevel.FULL,
                null,
                5,
                0,
                null,
                null,
                null,
                null);
    }
}
