package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.persistence.api.CheckpointDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StartupCheckpointOperationMatcherTest {

    private CheckpointSerializer checkpointSerializer;
    private StartupCheckpointOperationMatcher matcher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        checkpointSerializer = new CheckpointSerializer(objectMapper);
        matcher = new StartupCheckpointOperationMatcher(checkpointSerializer, objectMapper);
    }

    @Test
    void matchesSequentialCallByExactPersistedOperationId() {
        String operationId = UUID.randomUUID().toString();
        Map<String, Object> parameters = Map.of("command", "echo safe");
        String stateJson = checkpointSerializer.serializeSteps(List.of(
                ReasoningStep.pending("thought", "shell_command", parameters).withOperationId(operationId)));

        assertTrue(matcher.matches(
                checkpoint("shell_command", "{\"command\":\"echo safe\"}", stateJson), operationId, "shell_command"));
    }

    @Test
    void rejectsSameToolAndParametersWhenOperationIdDiffersEvenIfIdAppearsInJson() {
        String attemptOperationId = UUID.randomUUID().toString();
        String checkpointOperationId = UUID.randomUUID().toString();
        Map<String, Object> parameters = Map.of("command", "echo " + attemptOperationId);
        String stateJson = checkpointSerializer.serializeSteps(List.of(
                ReasoningStep.pending("thought", "shell_command", parameters).withOperationId(checkpointOperationId)));

        assertFalse(matcher.matches(
                checkpoint("shell_command", "{\"command\":\"echo " + attemptOperationId + "\"}", stateJson),
                attemptOperationId,
                "shell_command"));
    }

    @Test
    void matchesParallelCallOnlyByUniqueOperationIdAndToolName() throws Exception {
        String operationId = UUID.randomUUID().toString();
        List<ParallelToolCall> calls = List.of(
                new ParallelToolCall("call-1", "shell_command", Map.of(), "{}", null, false, operationId),
                new ParallelToolCall(
                        "call-2",
                        "shell_command",
                        Map.of(),
                        "{}",
                        null,
                        false,
                        UUID.randomUUID().toString()));

        assertTrue(matcher.matches(
                checkpoint("parallel|shell_command|shell_command", new ObjectMapper().writeValueAsString(calls)),
                operationId,
                "shell_command"));
    }

    @Test
    void rejectsDuplicateParallelOperationIdentity() throws Exception {
        String operationId = UUID.randomUUID().toString();
        List<ParallelToolCall> calls = List.of(
                new ParallelToolCall("call-1", "shell_command", Map.of(), "{}", null, false, operationId),
                new ParallelToolCall("call-2", "other_tool", Map.of(), "{}", null, false, operationId));

        assertFalse(matcher.matches(
                checkpoint("parallel|shell_command|other_tool", new ObjectMapper().writeValueAsString(calls)),
                operationId,
                "shell_command"));
    }

    @Test
    void malformedPayloadAndToolNameOnlyDecisionsDoNotEstablishIdentity() {
        CheckpointDto sequential = checkpoint("shell_command", "{malformed");
        assertFalse(matcher.matches(sequential, UUID.randomUUID().toString(), "shell_command"));
        assertFalse(matcher.matches(
                checkpoint("other_tool", "{}"), UUID.randomUUID().toString(), "shell_command"));

        CheckpointDto parallel = checkpoint("parallel|shell_command", "{malformed");
        assertFalse(matcher.matches(parallel, UUID.randomUUID().toString(), "shell_command"));
    }

    private CheckpointDto checkpoint(String pendingToolName, String pendingToolParametersJson) {
        return checkpoint(pendingToolName, pendingToolParametersJson, "[]");
    }

    private CheckpointDto checkpoint(String pendingToolName, String pendingToolParametersJson, String stateJson) {
        Instant now = Instant.EPOCH;
        return new CheckpointDto(
                1L,
                "session-1",
                "DEVELOPER",
                "COMPLETED",
                "SEMI_AUTONOMOUS",
                stateJson,
                "cli",
                "INTERNAL",
                "goal",
                null,
                null,
                null,
                pendingToolName,
                "{\"shell_command\":\"APPROVED\"}",
                pendingToolParametersJson,
                null,
                10,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now,
                1);
    }
}
