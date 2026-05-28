package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ParallelToolCallTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testSerializationConsistency() throws Exception {
        // P81-B: Verify that ParallelToolCall serialization matches CDI ObjectMapper
        Instant now = Instant.now();
        Map<String, Object> params = Map.of("time", now);

        String expectedJson = objectMapper.writeValueAsString(params);

        ParallelToolCall call = ParallelToolCall.pending("call_1", "test_tool", params, expectedJson);

        assertEquals(
                expectedJson,
                call.paramsJson(),
                "ParallelToolCall.paramsJson() must match the CDI-managed ObjectMapper output");
    }
}
