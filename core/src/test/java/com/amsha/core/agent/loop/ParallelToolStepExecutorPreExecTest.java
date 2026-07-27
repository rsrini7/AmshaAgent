package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class ParallelToolStepExecutorPreExecTest {

    @Mock
    ToolInvocationGuard guard;

    @Mock
    ToolInvoker toolInvoker;

    @Mock
    ApprovalCheckpointManager checkpointManager;

    @Mock
    GovernanceStrategy governanceLayer;

    @Mock
    RateLimitTracker rateLimitTracker;

    @Mock
    Instance<AgentObserver> observers;

    ObjectMapper objectMapper = new ObjectMapper();

    ParallelToolStepExecutor executor;

    @BeforeEach
    void setup() throws Exception {
        executor = new ParallelToolStepExecutor();
        setField(executor, "objectMapper", objectMapper);
        setField(executor, "guard", guard);
        setField(executor, "checkpointManager", checkpointManager);
        setField(executor, "governanceLayer", governanceLayer);
        setField(executor, "rateLimitTracker", rateLimitTracker);
        setField(executor, "observers", observers);
        setField(executor, "preExecutableTools", Optional.of(List.of("safe_read")));

        SequentialToolExecutor sequentialToolExecutor = new SequentialToolExecutor();
        setField(sequentialToolExecutor, "toolInvoker", toolInvoker);
        setField(sequentialToolExecutor, "guard", guard);
        setField(executor, "sequentialToolExecutor", sequentialToolExecutor);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void executeWithCorruptBatch_ReturnsTerminalFailed() {
        AgentExecutionContext context = new AgentExecutionContext(
                "s-corrupt",
                "u1",
                "c1",
                "COORDINATOR",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        ReasoningStep batchStep = ReasoningStep.parallelBatch("thought", Map.of("batch", "invalid-json"), 0, 0);

        StepDecision decision = executor.execute(context, new ArrayList<>(), batchStep, 0);
        assertNotNull(decision);
        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.Failed);
    }

    @Test
    void executeWithBlockedTool_ReturnsRetryValidation() {
        AgentExecutionContext context = new AgentExecutionContext(
                "s-blocked",
                "u1",
                "c1",
                "COORDINATOR",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);

        var calls = List.of(new ParallelToolCall("c1", "blocked_tool", Map.of(), "{}", null, true));
        ReasoningStep batchStep = ReasoningStep.parallelBatch("thought", Map.of("batch", calls), 0, 0);

        when(guard.evaluate(any(), any(), any(), eq("blocked_tool"), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Blocked("Not allowed"));

        StepDecision decision = executor.execute(context, new ArrayList<>(), batchStep, 0);
        assertNotNull(decision);
        assertNull(decision.result());
        assertEquals(1, decision.consecutiveFailures());
    }

    @Test
    void executeWithDeduplicatedTools_ReturnsCompletedBatch() {
        AgentExecutionContext context = new AgentExecutionContext(
                "s-dedup",
                "u1",
                "c1",
                "COORDINATOR",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);

        var calls = List.of(new ParallelToolCall("c1", "dup_tool", Map.of(), "{}", null, true));
        ReasoningStep batchStep = ReasoningStep.parallelBatch("thought", Map.of("batch", calls), 0, 0);

        when(guard.evaluate(any(), any(), any(), eq("dup_tool"), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Deduplicate("Cached result"));

        StepDecision decision = executor.execute(context, new ArrayList<>(), batchStep, 0);
        assertNotNull(decision);
    }

    @Test
    void executeWithMixedTools_PreExecutesSafeTool() {
        String sessionId = "123-456";
        String agentRole = "COORDINATOR";

        AgentExecutionContext context = new AgentExecutionContext(
                sessionId,
                "user1",
                "chat1",
                agentRole,
                "goal",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);

        var calls = List.of(
                new ParallelToolCall("c1", "safe_read", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "unsafe_write", Map.of(), "{}", null, true));

        ReasoningStep batchStep = ReasoningStep.parallelBatch("thought", Map.of("batch", calls), 0, 0)
                .withObservation("");

        when(guard.evaluate(any(), any(), any(), eq("safe_read"), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());

        when(guard.evaluate(any(), any(), any(), eq("unsafe_write"), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(
                        new ApprovalFrame("unsafe_write", Map.of(), ApprovalFrame.RiskLevel.MEDIUM)));

        when(toolInvoker.invoke(eq("safe_read"), any(), eq(sessionId)))
                .thenReturn(ToolResponse.success("pre-exec-data"));

        executor.execute(context, Collections.emptyList(), batchStep, 0);

        verify(toolInvoker, times(1)).invoke(eq("safe_read"), any(), any());
        verify(checkpointManager).persistBatchWithPreExecuted(eq(context), anyList(), any(), anyList(), any(), any());
    }

    @Test
    void executeWithPreExecutionFailure_HandlesGracefully() {
        String sessionId = "s-fail-pre";
        AgentExecutionContext context = new AgentExecutionContext(
                sessionId,
                "user1",
                "chat1",
                "COORDINATOR",
                "goal",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);

        var calls = List.of(
                new ParallelToolCall("c1", "safe_read", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "unsafe_write", Map.of(), "{}", null, true));

        ReasoningStep batchStep = ReasoningStep.parallelBatch("thought", Map.of("batch", calls), 0, 0)
                .withObservation("");

        when(guard.evaluate(any(), any(), any(), eq("safe_read"), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());

        when(guard.evaluate(any(), any(), any(), eq("unsafe_write"), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(
                        new ApprovalFrame("unsafe_write", Map.of(), ApprovalFrame.RiskLevel.MEDIUM)));

        when(toolInvoker.invoke(eq("safe_read"), any(), eq(sessionId)))
                .thenThrow(new RuntimeException("IO error during pre-exec"));

        executor.execute(context, Collections.emptyList(), batchStep, 0);

        verify(checkpointManager).persistBatchWithPreExecuted(eq(context), anyList(), any(), anyList(), any(), any());
    }
}
