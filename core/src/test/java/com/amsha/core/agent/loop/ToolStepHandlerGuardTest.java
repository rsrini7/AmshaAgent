package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ToolStepHandlerGuardTest {

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ToolInvocationGuard guard;

    @Mock
    Instance<AgentObserver> observers;

    @Mock
    ToolInvoker invoker;

    @Mock
    ChatMemoryWriter writer;

    @Mock
    Instance<DynamicToolLookup> dynamicToolLookup;

    @Mock
    ApprovalCheckpointManager checkpointManager;

    @Mock
    ParallelToolStepExecutor parallelExecutor;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    ToolStepEscalationHandler escalationHandler;

    ToolStepHandler handler;

    private AgentExecutionContext createCtx(String role, String goal) {
        return new AgentExecutionContext(
                "sess1",
                "user1",
                "chat1",
                role,
                goal,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
    }

    @BeforeEach
    void setup() {
        ToolStepValidator validator = new ToolStepValidator(toolRegistry, observers, dynamicToolLookup);

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
                escalationHandler);
    }

    @Test
    void validateTool_coordinatorGithub_noResource_blocks() {
        AgentExecutionContext ctx = createCtx("coordinator", "find bugs in the app");
        ReasoningStep step = ReasoningStep.pending(
                "thought", ToolCapability.GITHUB.toolName(), Map.of("query", "NullPointerException"));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(
                ToolCapability.DELEGATE_TO_AGENT.toolName(), decision.step().action());
        assertEquals("thought", decision.step().thought());
        assertEquals("researcher", decision.step().actionInput().get("agent"));
        assertTrue(
                String.valueOf(decision.step().actionInput().get("goal")).contains(ToolCapability.GITHUB.toolName()));
    }

    @Test
    void validateTool_coordinatorGithub_resourceInGoal_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "summarize rsrini7/amsha");
        ReasoningStep step =
                ReasoningStep.pending("thought", ToolCapability.GITHUB.toolName(), Map.of("query", "main"));

        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName())).thenReturn(Optional.of(mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_coordinatorGithub_resourceInArgs_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "check the code");
        ReasoningStep step =
                ReasoningStep.pending("thought", ToolCapability.GITHUB.toolName(), Map.of("repo", "rsrini7/config"));

        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName())).thenReturn(Optional.of(mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_nonCoordinator_noResource_allows() {
        AgentExecutionContext ctx = createCtx("saraswati", "find bugs in the app");
        ReasoningStep step = ReasoningStep.pending(
                "thought", ToolCapability.GITHUB.toolName(), Map.of("query", "NullPointerException"));

        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName())).thenReturn(Optional.of(mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeToolAndObserve_pauseForApproval_persistsPendingStepInHistory() {
        AgentExecutionContext ctx = createCtx("researcher", "list gists");
        ReasoningStep step =
                ReasoningStep.pending("thought", ToolCapability.GITHUB.toolName(), Map.of("target", "rsrini7"));

        lenient()
                .doReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(new ApprovalFrame(
                        ToolCapability.GITHUB.toolName(), Map.of("target", "rsrini7"), ApprovalFrame.RiskLevel.MEDIUM)))
                .when(guard)
                .evaluate(
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        any(),
                        anyList(),
                        anyString(),
                        anyList());
        when(checkpointManager.persist(any(), any(), anyList(), any(), any()))
                .thenReturn(AgentResult.awaitingApproval(
                        ToolCapability.GITHUB.toolName(), "{\"target\":\"rsrini7\"}", "sess1", List.of()));

        StepDecision decision = handler.invokeToolAndObserve(ctx, List.of(), step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());

        ArgumentCaptor<List<ReasoningStep>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(checkpointManager).persist(any(), any(), historyCaptor.capture(), any(), any());
        assertEquals(1, historyCaptor.getValue().size());
        assertEquals(
                ToolCapability.GITHUB.toolName(),
                historyCaptor.getValue().getFirst().action());
    }

    @Test
    void invokeToolAndObserve_deduplicate() {
        AgentExecutionContext ctx = createCtx("researcher", "cached call");
        ReasoningStep step = ReasoningStep.pending("thought", "cached_tool", Map.of());

        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Deduplicate("cached observation"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(1, history.size());
    }

    @Test
    void invokeToolAndObserve_successfulInvocation() {
        AgentExecutionContext ctx = createCtx("researcher", "execute tool");
        ReasoningStep step = ReasoningStep.pending("thought", "my_tool", Map.of("key", "val"));

        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), anyString(), anyList()))
                .thenReturn(null);
        when(invoker.invoke(eq("my_tool"), any(), anyString())).thenReturn(ToolResponse.success("Tool Output"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.PROCEED, decision.type());
        verify(guard).recordToolCall(eq("sess1"), eq("my_tool"), any(), anyString());
    }

    @Test
    void invokeToolAndObserve_readonlyAutonomyLevel() {
        AgentExecutionContext readonlyCtx = new AgentExecutionContext(
                "sess1",
                "user1",
                "chat1",
                "researcher",
                "read task",
                AutonomyLevel.READONLY,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        ReasoningStep step = ReasoningStep.pending("thought", "my_tool", Map.of());

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(readonlyCtx, history, step, 0, List.of());

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertEquals(1, history.size());
        assertTrue(history.get(0).observation().contains("READONLY"));
    }

    @Test
    void invokeToolAndObserve_parallelBatchDelegatesToParallelExecutor() {
        AgentExecutionContext ctx = createCtx("researcher", "parallel batch");
        ReasoningStep batchStep = ReasoningStep.parallelBatch("thought", Map.of("batch", List.of()), 0, 0);

        when(parallelExecutor.execute(eq(ctx), anyList(), eq(batchStep), anyInt()))
                .thenReturn(StepDecision.terminal(AgentResult.finished("Parallel batch complete", List.of())));

        StepDecision decision = handler.invokeToolAndObserve(ctx, new ArrayList<>(), batchStep, 0, List.of());
        assertEquals(DecisionType.TERMINAL, decision.type());
        verify(parallelExecutor).execute(eq(ctx), anyList(), eq(batchStep), eq(0));
    }
}
