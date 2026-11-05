package com.amsha.app.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Assertions for durable checkpoint evidence shared by evaluation integration tests. */
final class AgentRunEvaluationCheckpointAssertions {

    private AgentRunEvaluationCheckpointAssertions() {}

    static void assertPersistedApproval(
            CheckpointCrudPort checkpointCrudPort,
            StatefulAgentStore agentStore,
            ObjectMapper objectMapper,
            String sessionId)
            throws IOException {
        CheckpointDto checkpoint = checkpointCrudPort
                .findBySessionAndRole(sessionId, "coordinator")
                .orElseThrow();
        assertEquals("AWAITING_APPROVAL", checkpoint.status());
        assertEquals(ToolCapability.GITHUB.toolName(), checkpoint.pendingToolName());
        assertNotNull(checkpoint.stateJson(), "Checkpoint JSON was not persisted");

        List<ReasoningStep> restoredSteps = agentStore.deserializeSteps(
                agentStore.load(sessionId, "coordinator").orElseThrow());
        ReasoningStep pendingStep = restoredSteps.getLast();
        assertEquals(ToolCapability.GITHUB.toolName(), pendingStep.action());
        assertNotNull(pendingStep.operationId(), "Checkpoint JSON lost the pending tool identity");
        assertEquals(
                objectMapper.readTree(checkpoint.pendingToolParametersJson()),
                objectMapper.valueToTree(pendingStep.actionInput()),
                "Pending checkpoint parameters differ from the identified tool call");
    }
}
