package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ToolFailureRetryTraceTest {

    private final TracePublisher tracePublisher = mock(TracePublisher.class);
    private final ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);

    private ToolStepHandler handler;
    private ToolInvoker invoker;
    private ToolInvocationGuard guard;
    private ExecutionFailureRecoveryService recoveryService;
    private ToolStepEscalationHandler escalationHandler;

    @BeforeEach
    void setup() {
        var observers = mock(Instance.class);
        when(observers.iterator()).thenReturn(Collections.emptyIterator());

        guard = mock(ToolInvocationGuard.class);
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());

        invoker = mock(ToolInvoker.class);
        var writer = mock(ChatMemoryWriter.class);
        var checkpointManager = mock(ApprovalCheckpointManager.class);
        var parallelExecutor = mock(ParallelToolStepExecutor.class);
        recoveryService = mock(ExecutionFailureRecoveryService.class);
        var validator = mock(ToolStepValidator.class);
        escalationHandler = mock(ToolStepEscalationHandler.class);

        handler = new ToolStepHandler(
                observers,
                guard,
                invoker,
                writer,
                checkpointManager,
                parallelExecutor,
                new SimpleMeterRegistry(),
                recoveryService,
                validator,
                escalationHandler,
                tracePublisher);
    }

    private AgentExecutionContext ctx() {
        return new AgentExecutionContext(
                "sess1",
                "user1",
                "chat1",
                "coder",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                Map.of());
    }

    @Test
    void failingToolEmitsToolStartAndToolError() {
        ReasoningStep step = ReasoningStep.pending("thought", "some_tool", Map.of(), 0, 0);
        when(invoker.invoke(anyString(), any(), anyString())).thenReturn(ToolResponse.failure(1, "boom"));
        when(recoveryService.shouldAttemptRecovery(anyString(), anyString(), any()))
                .thenReturn(false);
        when(escalationHandler.attemptGenericEscalation(any(), anyString(), any(), any()))
                .thenReturn(null);

        handler.invokeToolAndObserve(ctx(), new ArrayList<>(), step, 0, List.of());

        verify(tracePublisher, times(2)).publish(captor.capture());
        List<String> statuses =
                captor.getAllValues().stream().map(AgentTraceEvent::status).toList();
        assertTrue(statuses.contains("START"), "expected tool_start, got " + statuses);
        assertTrue(statuses.contains("ERROR"), "expected tool_error, got " + statuses);
        assertTrue(captor.getAllValues().stream().anyMatch(e -> e.stage() == TraceStage.TOOL_ERROR));
    }

    @Test
    void recoveryEmitsRetryThenToolEnd() {
        ReasoningStep step =
                ReasoningStep.pending("thought", "sandbox_exec", Map.of("code", "x", "language", "python"), 0, 0);
        when(invoker.invoke(anyString(), any(), anyString()))
                .thenReturn(ToolResponse.failure(1, "boom"))
                .thenReturn(ToolResponse.success("ok"));
        when(recoveryService.shouldAttemptRecovery(anyString(), anyString(), any()))
                .thenReturn(true);
        when(recoveryService.attemptRecovery(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ExecutionFailureRecoveryService.RecoveryResult.success("print('ok')", 1));
        when(recoveryService.validateForExecution(anyString(), anyString())).thenReturn(null);

        handler.invokeToolAndObserve(ctx(), new ArrayList<>(), step, 0, List.of());

        verify(tracePublisher, times(3)).publish(captor.capture());
        List<String> statuses =
                captor.getAllValues().stream().map(AgentTraceEvent::status).toList();
        assertTrue(statuses.contains("START"), "expected tool_start, got " + statuses);
        assertTrue(statuses.contains("RETRY"), "expected retry, got " + statuses);
        assertTrue(statuses.contains("END"), "expected tool_end, got " + statuses);
        assertTrue(captor.getAllValues().stream().anyMatch(e -> e.stage() == TraceStage.RETRY));
    }
}
