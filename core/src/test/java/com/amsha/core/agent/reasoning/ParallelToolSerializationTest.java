package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ParallelToolSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testParallelToolCallSerialization() throws Exception {
        // 1. Create a pending tool call
        ParallelToolCall pending = ParallelToolCall.pending(
                "call_0", "github:read_file", Map.of("path", "pom.xml"), "{\"path\":\"pom.xml\"}");

        // 2. Wrap it in a reasoning step batch
        ReasoningStep step = ReasoningStep.parallelBatch(
                "Thinking...", Map.of("batch", mapper.writeValueAsString(List.of(pending))), 100, 50);

        // 3. Serialize to JSON
        String json = mapper.writeValueAsString(step);
        assertTrue(json.contains("pom.xml"), "JSON should contain parameters");

        // 4. Update with result (post-execution)
        ParallelToolCall completed = pending.withResult("File contents here", true);

        // 5. Wrap again
        ReasoningStep stepCompleted = ReasoningStep.parallelBatch(
                "Thinking...", Map.of("batch", mapper.writeValueAsString(List.of(completed))), 100, 50);

        // 6. Serialize and verify post-execution state
        String jsonCompleted = mapper.writeValueAsString(stepCompleted);
        assertTrue(jsonCompleted.contains("File contents here"), "JSON should contain observation");

        // Use a less fragile check for the escaped boolean field inside the batch string
        assertTrue(
                jsonCompleted.contains("\\\"success\\\":true") || jsonCompleted.contains("\\\"success\\\": true"),
                "JSON should contain success flag. Total JSON: " + jsonCompleted);

        // 7. Deserialize back to ensure round-trip
        ReasoningStep deserializedStep = mapper.readValue(jsonCompleted, ReasoningStep.class);
        String batchJson = (String) deserializedStep.actionInput().get("batch");
        List<ParallelToolCall> batch = mapper.readValue(batchJson, new TypeReference<>() {});

        assertEquals(1, batch.size());
        ParallelToolCall res = batch.get(0);
        assertEquals("call_0", res.callId());
        assertEquals("File contents here", res.observation());
        assertTrue(res.success());
    }

    @Test
    void operationIdentitySurvivesCheckpointAndParallelCallSerialization() throws Exception {
        String operationId = "123e4567-e89b-12d3-a456-426614174000";
        ReasoningStep pending = ReasoningStep.pending("execute", "file_read", Map.of("path", "a.txt"))
                .withOperationId(operationId);
        ReasoningStep restored = mapper.readValue(mapper.writeValueAsBytes(pending), ReasoningStep.class);

        assertEquals(operationId, restored.operationId());
        assertEquals(operationId, restored.withObservation("read", true).operationId());

        ParallelToolCall call = ParallelToolCall.pending("call_0", "file_read", Map.of("path", "a.txt"), "{}")
                .withOperationId(operationId);
        List<ParallelToolCall> restoredCalls = mapper.readValue(
                mapper.writeValueAsBytes(List.of(call)), new TypeReference<List<ParallelToolCall>>() {});

        assertEquals(operationId, restoredCalls.getFirst().operationId());
        assertEquals(
                operationId, restoredCalls.getFirst().withResult("read", true).operationId());

        ReasoningStep legacy = mapper.readValue(
                mapper.writeValueAsBytes(ReasoningStep.pending("execute", "file_read", Map.of())), ReasoningStep.class);
        assertNull(legacy.operationId());
    }
}
