package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingToolResumeExecutorHistoryRestoreTest {

    private static final String OPERATION_ID = "123e4567-e89b-12d3-a456-426614174099";

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

    @Mock
    ToolInvocationGuard guard;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PendingToolResumeExecutor executor;

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
                guard);
        when(guard.evaluate(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
    }

    @Test
    void alreadyRestoredCheckpointHistoryKeepsUniqueOperationIdentity() throws Exception {
        String toolName = ToolCapability.GITHUB.toolName();
        Map<String, Object> parameters = Map.of("owner", "amsha", "repo", "agent");
        String parametersJson = objectMapper.writeValueAsString(parameters);
        ReasoningStep pendingStep = ReasoningStep.pending("pending GitHub tool", toolName, parameters)
                .withOperationId(OPERATION_ID);
        List<ReasoningStep> history = new ArrayList<>(List.of(pendingStep));
        when(agentStore.deserializeSteps(checkpoint)).thenReturn(List.of(pendingStep));
        when(checkpoint.getPendingToolName()).thenReturn(toolName);
        when(checkpoint.getPendingToolParametersJson()).thenReturn(parametersJson);
        when(checkpoint.getPendingToolRole()).thenReturn("coordinator");
        when(toolRegistry.invokeToolWithOperationId(toolName, parametersJson, "resume-session", OPERATION_ID))
                .thenReturn(ToolResponse.success("synthetic result"));
        when(stateMachineRunner.runFinishPass(any(), any(), any())).thenReturn(AgentResult.finished("done", List.of()));

        AgentResult result =
                executor.execute(contextFor("resume-session"), history, checkpoint, "CLI", stateMachineRunner);

        assertInstanceOf(AgentResult.Finished.class, result);
        verify(toolRegistry).invokeToolWithOperationId(toolName, parametersJson, "resume-session", OPERATION_ID);
    }

    private static AgentExecutionContext contextFor(String sessionId) {
        return new AgentExecutionContext(
                sessionId,
                "user",
                "chat",
                "coordinator",
                "resume goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "CLI",
                "user",
                null,
                Map.of());
    }
}
