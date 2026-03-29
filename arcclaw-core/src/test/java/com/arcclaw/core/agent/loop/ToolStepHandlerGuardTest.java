package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolStepHandlerGuardTest {

    @Mock ToolRegistry toolRegistry;
    @Mock ToolInvocationGuard guard;
    @Mock Instance<AgentObserver> observers;
    @InjectMocks ToolStepHandler handler;

    private AgentExecutionContext createCtx(String role, String goal) {
        return new AgentExecutionContext("sess1", "user1", "chat1", role, goal, AutonomyLevel.FULL, 5);
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
        
        assertEquals(AgentStateMachineRunner.DecisionType.CONTINUE, decision.type());
        assertTrue(decision.transientError() != null && decision.transientError().contains("use delegate_to_agent instead"));
    }

    @Test
    void validateTool_coordinatorGithub_resourceInGoal_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "summarize rsrini7/arcclaw");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("query", "main"));
        
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        assertEquals(AgentStateMachineRunner.DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_coordinatorGithub_resourceInArgs_allows() {
        AgentExecutionContext ctx = createCtx("coordinator", "check the code");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("repo", "rsrini7/config"));
        
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        assertEquals(AgentStateMachineRunner.DecisionType.PROCEED, decision.type());
    }

    @Test
    void validateTool_nonCoordinator_noResource_allows() {
        AgentExecutionContext ctx = createCtx("saraswati", "find bugs in the app");
        ReasoningStep step = ReasoningStep.pending("thought", "github", Map.of("query", "NullPointerException"));
        
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(Mockito.mock(AgentTool.class)));

        StepDecision decision = handler.validateTool(ctx, List.of(), step, 0);
        
        assertEquals(AgentStateMachineRunner.DecisionType.PROCEED, decision.type());
    }
}
