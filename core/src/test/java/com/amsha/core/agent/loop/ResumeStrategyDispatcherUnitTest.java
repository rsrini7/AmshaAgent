package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeStrategyDispatcherUnitTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    ResumeStrategyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ResumeStrategyDispatcher(
                agentStore, toolRegistry, objectMapper, reasoningEngine, recoveryService, escalationService);
    }

    @Test
    void testResolveResumeGoal_StoreEmpty() {
        when(agentStore.load("s1", "coder")).thenReturn(Optional.empty());
        String goal = dispatcher.resolveResumeGoal("s1", "coder");
        assertEquals(AgentSentinels.GOAL_RESUME_APPROVED, goal);
    }

    @Test
    void testResolveResumeGoal_WithOriginalGoal() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getOriginalGoal()).thenReturn("Implement feature X");
        when(agentStore.load("s1", "coder")).thenReturn(Optional.of(cp));

        String goal = dispatcher.resolveResumeGoal("s1", "coder");
        assertEquals("Implement feature X", goal);
    }

    @Test
    void testResolveResumeGoal_AllDeniedBatchDecisions() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.hasBatchToolDecisions()).thenReturn(true);
        when(cp.getBatchToolDecisions()).thenReturn("{\"tool1\":\"DENIED\",\"tool2\":\"DENIED\"}");

        String goal = dispatcher.resolveResumeGoal(cp);
        assertEquals(AgentSentinels.GOAL_DENY_APPROVED, goal);
    }

    @Test
    void testResolveResumeGoal_MixedBatchDecisions() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.hasBatchToolDecisions()).thenReturn(true);
        when(cp.getBatchToolDecisions()).thenReturn("{\"tool1\":\"APPROVED\",\"tool2\":\"DENIED\"}");
        when(cp.getOriginalGoal()).thenReturn("Run tools");

        String goal = dispatcher.resolveResumeGoal(cp);
        assertEquals("Run tools", goal);
    }
}
