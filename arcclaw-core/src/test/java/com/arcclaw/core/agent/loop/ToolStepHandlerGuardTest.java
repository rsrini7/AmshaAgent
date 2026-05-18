package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolStepHandlerGuardTest {

    @Mock ToolRegistry toolRegistry;
    @Mock ToolInvocationGuard guard;
    @Mock Instance<AgentObserver> observers;
    @Mock ToolInvoker invoker;
    @Mock ChatMemoryWriter writer;
    @Mock Instance<DynamicToolLookup> dynamicToolLookup;
    @Mock ApprovalCheckpointManager checkpointManager;
    @Mock ParallelToolStepExecutor parallelExecutor;
    @Mock ExecutionFailureRecoveryService recoveryService;
    @InjectMocks ToolStepHandler handler;

    private AgentExecutionContext createCtx(String role, String goal) {
        return new AgentExecutionContext("sess1", "user1", "chat1", role, goal, AutonomyLevel.FULL, StructuredOutputMode.NATIVE, 5, 0, null, null, null, null);
    }

    @BeforeEach
    void setup() {
        // Minimal mock setup to bypass normal validation
    }

    @Test
    void validateTool_coordinatorGithub_noResource_blocks() {
        AgentExecutionContext ctx = createCtx("coordinator", "find bugs in the app");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("query", "NullPointerException"));
        
        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        // Silently rewrites to delegate_to_agent instead of blocking with hint
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), decision.step().action());
        // Thought is preserved from original step
        assertEquals("thought", decision.step().thought());
        // Parameter 'agent' should be 'researcher'
        assertEquals("researcher", decision.step().actionInput().get("agent"));
        // Parameter 'goal' should contain 'github'
        assertTrue(String.valueOf(decision.step().actionInput().get("goal")).contains("github"));
    }

    @Test
    void validateTool_coordinatorGithub_resourceInGoal_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "summarize rsrini7/arcclaw");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("query", "main"));
        
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_coordinatorGithub_resourceInArgs_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "check the code");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("repo", "rsrini7/config"));
        
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_nonCoordinator_noResource_allows() {
        AgentExecutionContext ctx = createCtx("saraswati", "find bugs in the app");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("query", "NullPointerException"));
        
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeToolAndObserve_pauseForApproval_persistsPendingStepInHistory() {
        AgentExecutionContext ctx = createCtx("researcher", "list gists");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("target", "rsrini7"));

        Mockito.lenient().doReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(
                        "github", "{\"target\":\"rsrini7\"}"))
                .when(guard).evaluate(
                        anyString(), anyString(), any(AutonomyLevel.class), anyString(), 
                        any(), anyList(), anyString(), anyList());
        when(checkpointManager.persist(any(), any(), anyList(), any(), any()))
                .thenReturn(AgentResult.awaitingApproval("github", "{\"target\":\"rsrini7\"}", "sess1", List.of()));

        StepDecision decision = handler.invokeToolAndObserve(ctx, List.of(), step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());

        ArgumentCaptor<List<ReasoningStep>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(checkpointManager).persist(any(), any(), historyCaptor.capture(), any(), any());
        assertEquals(1, historyCaptor.getValue().size());
        assertEquals("github", historyCaptor.getValue().getFirst().action());
    }
}
