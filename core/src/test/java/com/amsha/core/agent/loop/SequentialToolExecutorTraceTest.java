package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.opentelemetry.context.Context;

/**
 * Verifies that the parallel tool execution path (ParallelToolStepExecutor -> SequentialToolExecutor)
 * emits the per-tool lifecycle trace (tool_start / tool_end / tool_error), which the sequential
 * ToolStepHandler path already covered. Without this, parallel tool calls would be invisible on the
 * Visual Cockpit live trace.
 */
class SequentialToolExecutorTraceTest {

    private final TracePublisher tracePublisher = mock(TracePublisher.class);
    private final ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);

    @Test
    void execute_EmitsToolStartAndToolEndOnSuccess() {
        var toolInvoker = mock(ToolInvoker.class);
        var guard = mock(ToolInvocationGuard.class);
        var executor = new SequentialToolExecutor(toolInvoker, guard, tracePublisher);

        var call = ParallelToolCall.pending("call_0", "search", Map.of("q", "x"), "{\"q\":\"x\"}");
        when(toolInvoker.invoke(eq("search"), any(), eq("sess-1"))).thenReturn(ToolResponse.success("found"));

        executor.execute(context(), call, Context.current());

        verify(tracePublisher, times(2)).publish(captor.capture());
        var statuses =
                captor.getAllValues().stream().map(AgentTraceEvent::status).toList();
        assertTrue(statuses.contains("START"), "expected tool_start, got " + statuses);
        assertTrue(statuses.contains("END"), "expected tool_end, got " + statuses);
        assertTrue(captor.getAllValues().stream()
                .allMatch(e -> e.stage() == TraceStage.TOOL_START || e.stage() == TraceStage.TOOL_END));
    }

    @Test
    void execute_EmitsToolStartAndToolErrorOnFailure() {
        var toolInvoker = mock(ToolInvoker.class);
        var guard = mock(ToolInvocationGuard.class);
        var executor = new SequentialToolExecutor(toolInvoker, guard, tracePublisher);

        var call = ParallelToolCall.pending("call_0", "calc", Map.of(), "{}");
        when(toolInvoker.invoke(eq("calc"), any(), eq("sess-1"))).thenReturn(ToolResponse.failure(1, "boom"));

        executor.execute(context(), call, Context.current());

        verify(tracePublisher, times(2)).publish(captor.capture());
        var statuses =
                captor.getAllValues().stream().map(AgentTraceEvent::status).toList();
        assertTrue(statuses.contains("START"), "expected tool_start, got " + statuses);
        assertTrue(statuses.contains("ERROR"), "expected tool_error, got " + statuses);
    }

    @Test
    void invoke_EmitsToolStartAndToolEndForPreExec() {
        var toolInvoker = mock(ToolInvoker.class);
        var guard = mock(ToolInvocationGuard.class);
        var executor = new SequentialToolExecutor(toolInvoker, guard, tracePublisher);

        when(toolInvoker.invoke(eq("safe_read"), any(), eq("sess-1"))).thenReturn(ToolResponse.success("ok"));

        executor.invoke("safe_read", Map.of("path", "/x"), "sess-1");

        verify(tracePublisher, times(2)).publish(captor.capture());
        var statuses =
                captor.getAllValues().stream().map(AgentTraceEvent::status).toList();
        assertTrue(statuses.contains("START"), "expected tool_start, got " + statuses);
        assertTrue(statuses.contains("END"), "expected tool_end, got " + statuses);
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
