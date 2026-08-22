package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingToolResumeExecutorTest {

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
    AgentCheckpoint checkpoint;

    private PendingToolResumeExecutor executor;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new PendingToolResumeExecutor(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
                null);
    }

    @Test
    void testExecute_nullPendingToolName_completesCheckpointAndRunsFinishPassWithoutNpe() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coder",
                "Hi",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "WEBSOCKET",
                "p1",
                null,
                Map.of());

        when(checkpoint.getPendingToolName()).thenReturn(null);
        when(stateMachineRunner.runFinishPass(any(), any(), any()))
                .thenReturn(AgentResult.finished("Hello! How can I help you?", List.of()));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "WEBSOCKET", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("Hello"));
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
        verify(agentStore).save(checkpoint);
    }

    @Test
    void testExecute_blankPendingToolName_completesCheckpointAndRunsFinishPassWithoutNpe() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "Hi",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "WEBSOCKET",
                "p1",
                null,
                Map.of());

        when(checkpoint.getPendingToolName()).thenReturn("   ");
        when(stateMachineRunner.runFinishPass(any(), any(), any()))
                .thenReturn(AgentResult.finished("Hello! How can I help you?", List.of()));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "WEBSOCKET", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("Hello"));
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
        verify(agentStore).save(checkpoint);
    }
}
