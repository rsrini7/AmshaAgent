package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchDecisionUtilTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testToDisplayMap() {
        ParallelToolCall call1 = ParallelToolCall.pending(
                "c1", "browser_navigate", Map.of("url", "https://spring.io"), "{\"url\":\"https://spring.io\"}");
        ParallelToolCall call2 = ParallelToolCall.pending(
                "c2", "browser_navigate", Map.of("url", "https://quarkus.io"), "{\"url\":\"https://quarkus.io\"}");

        Map<String, Object> display = BatchDecisionUtil.toDisplayMap(List.of(call1, call2));
        assertEquals(2, display.size());
        assertEquals(Map.of("url", "https://spring.io"), display.get("1. browser_navigate"));
        assertEquals(Map.of("url", "https://quarkus.io"), display.get("2. browser_navigate"));
    }

    @Test
    void testParseBatchDisplayParams_fromSingleMapJson() {
        String json = "{\"url\":\"https://spring.io\"}";
        Map<String, Object> result = BatchDecisionUtil.parseBatchDisplayParams(json, objectMapper);
        assertEquals(1, result.size());
        assertEquals("https://spring.io", result.get("url"));
    }

    @Test
    void testParseBatchDisplayParams_fromArrayJson() {
        String json = """
                [
                    {"callId":"c1","toolName":"browser_navigate","params":{"url":"https://spring.io"}},
                    {"callId":"c2","tool":"web_fetch","params":{"url":"https://quarkus.io"}}
                ]
                """;
        Map<String, Object> result = BatchDecisionUtil.parseBatchDisplayParams(json, objectMapper);
        assertEquals(2, result.size());
        assertEquals(Map.of("url", "https://spring.io"), result.get("1. browser_navigate"));
        assertEquals(Map.of("url", "https://quarkus.io"), result.get("2. web_fetch"));
    }

    @Test
    void testParseBatchDisplayParams_fromBatchEnvelopeJson() {
        String json = """
                {
                    "batch": [
                        {"callId":"c1","tool":"browser_navigate","params":{"url":"https://spring.io"}}
                    ]
                }
                """;
        Map<String, Object> result = BatchDecisionUtil.parseBatchDisplayParams(json, objectMapper);
        assertEquals(1, result.size());
        assertEquals(Map.of("url", "https://spring.io"), result.get("1. browser_navigate"));
    }

    @Test
    void testParseBatchDisplayParams_emptyOrNull() {
        assertTrue(BatchDecisionUtil.parseBatchDisplayParams(null, objectMapper).isEmpty());
        assertTrue(BatchDecisionUtil.parseBatchDisplayParams("", objectMapper).isEmpty());
        assertTrue(
                BatchDecisionUtil.parseBatchDisplayParams("   ", objectMapper).isEmpty());
        assertTrue(BatchDecisionUtil.parseBatchDisplayParams("{}", objectMapper).isEmpty());
        assertTrue(BatchDecisionUtil.parseBatchDisplayParams("[]", objectMapper).isEmpty());
    }

    @Test
    void testNormalizeToolName() {
        assertNull(BatchDecisionUtil.normalizeToolName(null));
        assertEquals("", BatchDecisionUtil.normalizeToolName(""));
        assertEquals("browser_navigate", BatchDecisionUtil.normalizeToolName("browser_navigate"));
        assertEquals("browser_navigate", BatchDecisionUtil.normalizeToolName("parallel|browser_navigate"));
        assertEquals("all", BatchDecisionUtil.normalizeToolName("all"));
        assertEquals("all", BatchDecisionUtil.normalizeToolName("ALL"));
        assertEquals("all", BatchDecisionUtil.normalizeToolName("parallel|all"));
        assertEquals("all", BatchDecisionUtil.normalizeToolName("parallel|toolA|toolB"));
        assertEquals("all", BatchDecisionUtil.normalizeToolName("parallel|toolA,toolB"));
        assertEquals("all", BatchDecisionUtil.normalizeToolName("toolA,toolB"));
    }
}
