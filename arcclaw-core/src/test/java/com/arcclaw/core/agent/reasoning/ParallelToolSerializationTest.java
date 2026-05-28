package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
