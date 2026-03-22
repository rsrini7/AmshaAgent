package com.arcclaw.core.agent.loop;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.GithubTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolInvocationGuardTest {

    private ToolInvocationGuard guard;
    private RoleRegistry roleRegistry;
    private ObjectMapper objectMapper;
    private StatefulAgentStore agentStore;
    private ToolRegistry mockToolRegistry;

    @BeforeEach
    void setUp() {
        guard = new ToolInvocationGuard();
        roleRegistry = mock(RoleRegistry.class);
        objectMapper = new ObjectMapper();
        agentStore = mock(StatefulAgentStore.class);
        mockToolRegistry = mock(ToolRegistry.class);

        guard.roleRegistry = roleRegistry;
        guard.objectMapper = objectMapper;
        guard.agentStore = agentStore;
        guard.toolRegistry = mockToolRegistry;
    }

    @Test
    void testDuplicateToolCallReturnsCachedObservation() {
        ReasoningStep past = ReasoningStep.pending("thought", "tool", Map.of("arg", 1), 0, 0)
                .withObservation("result 1");
        List<ReasoningStep> history = List.of(past);

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", "tool", Map.of("arg", 1), history, "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Deduplicate.class, result);
        assertTrue(((ToolInvocationGuard.GuardDecision.Deduplicate) result).cachedObservation().contains("result 1"));
    }

    @Test
    void testFailedToolObservationIsNotCached() {
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());
        ReasoningStep past = ReasoningStep.pending("thought", "tool", Map.of("arg", 1), 0, 0);
        // observation is null by default
        List<ReasoningStep> history = List.of(past);

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", "tool", Map.of("arg", 1), history, "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, result);
    }

    @Test
    void testGithubBlockedWhenGoalHasNoKeywords() {
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", GithubTool.NAME, Map.of(), List.of(), "how are you?", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, result);
        assertTrue(((ToolInvocationGuard.GuardDecision.Blocked) result).observation().contains("blocked by the Tool Intent Guard"));
    }

    @Test
    void testGithubPreApprovedReturnsAllow() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(GithubTool.NAME);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        // Wrap in scope — after GitHub guard passes, evaluate() reaches HITL step
        // which calls WorkspaceContext.autonomy() (ScopedValue — must be bound)
        workspaceRun("s1", AutonomyLevel.FULL, () -> {
            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1", "coordinator", GithubTool.NAME, Map.of(), List.of(), "how are you?", List.of());
            assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, result);
        });
    }

    @Test
    void testHitlPauseForApproval() {
        workspaceRun("s1", AutonomyLevel.SUPERVISED, () -> {
            when(roleRegistry.requiresApprovalForTools(any())).thenReturn(true);
            when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1", "coordinator", "delete-all", Map.of(), List.of(), "goal", List.of());

            assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, result);
        });
    }

    @Test
    void testFullAutonomySkipsHitl() {
        workspaceRun("s1", AutonomyLevel.FULL, () -> {
            when(roleRegistry.requiresApprovalForTools(any())).thenReturn(true);
            when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1", "coordinator", "delete-all", Map.of(), List.of(), "goal", List.of());

            assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, result);
        });
    }

    private void workspaceRun(String sessionId, AutonomyLevel level, Runnable runnable) {
        WorkspaceContext.run(sessionId, "user", "chat", level, "coordinator", runnable);
    }
}
