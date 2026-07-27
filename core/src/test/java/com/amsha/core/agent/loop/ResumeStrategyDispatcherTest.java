package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Optional;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeStrategyDispatcherTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    @Mock
    AgentStateMachineRunner stateMachineRunner;

    @Mock
    MessageContext messageContext;

    @Mock
    AgentCheckpoint checkpoint;

    private ResumeStrategyDispatcher dispatcher;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dispatcher = new ResumeStrategyDispatcher(
                agentStore, toolRegistry, objectMapper, reasoningEngine, recoveryService, escalationService);
    }

    @Test
    void testPerformDenialCleanup() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "DEFAULT",
                AgentSentinels.GOAL_DENY_APPROVED,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "CLI",
                "p1",
                null,
                null);

        lenient().when(checkpoint.getPendingToolName()).thenReturn("tool1");

        AgentResult result = dispatcher.handle(ctx, new ArrayList<>(), messageContext, stateMachineRunner, checkpoint);

        assertNotNull(result);
        assertTrue(result instanceof AgentResult);
        verify(agentStore).save(checkpoint);
    }

    @Test
    void testResolveResumeGoalFromCheckpoint() {
        lenient().when(checkpoint.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(checkpoint.getOriginalGoal()).thenReturn("Orig goal");
        lenient().when(checkpoint.getPendingToolName()).thenReturn("tool_test");

        String goal = dispatcher.resolveResumeGoal(checkpoint);
        assertEquals("Orig goal", goal);
    }

    @Test
    void testResolveResumeGoalFromSessionAndRole() {
        when(agentStore.load("s1", "role1")).thenReturn(Optional.of(checkpoint));
        when(checkpoint.getOriginalGoal()).thenReturn("My goal");

        String goal = dispatcher.resolveResumeGoal("s1", "role1");
        assertEquals("My goal", goal);
    }
}
