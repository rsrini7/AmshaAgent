package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
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
        // Build ToolStepValidator with real logic but mocked dependencies
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

        // Silently rewrites to garuda instead of blocking with hint
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(
                ToolCapability.DELEGATE_TO_AGENT.toolName(), decision.step().action());
        // Thought is preserved from original step
        assertEquals("thought", decision.step().thought());
        // Parameter 'agent' should be 'researcher'
        assertEquals("researcher", decision.step().actionInput().get("agent"));
        // Parameter 'goal' should contain 'github'
        assertTrue(
                String.valueOf(decision.step().actionInput().get("goal")).contains(ToolCapability.GITHUB.toolName()));
    }

    @Test
    void validateTool_coordinatorGithub_resourceInGoal_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "summarize rsrini7/amsha");
        ReasoningStep step =
                ReasoningStep.pending("thought", ToolCapability.GITHUB.toolName(), Map.of("query", "main"));

        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName()))
                .thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_coordinatorGithub_resourceInArgs_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "check the code");
        ReasoningStep step =
                ReasoningStep.pending("thought", ToolCapability.GITHUB.toolName(), Map.of("repo", "rsrini7/config"));

        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName()))
                .thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_nonCoordinator_noResource_allows() {
        AgentExecutionContext ctx = createCtx("saraswati", "find bugs in the app");
        ReasoningStep step = ReasoningStep.pending(
                "thought", ToolCapability.GITHUB.toolName(), Map.of("query", "NullPointerException"));

        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName()))
                .thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeToolAndObserve_pauseForApproval_persistsPendingStepInHistory() {
        AgentExecutionContext ctx = createCtx("researcher", "list gists");
        ReasoningStep step =
                ReasoningStep.pending("thought", ToolCapability.GITHUB.toolName(), Map.of("target", "rsrini7"));

        Mockito.lenient()
                .doReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(
                        ToolCapability.GITHUB.toolName(), "{\"target\":\"rsrini7\"}"))
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
}
