package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.InputSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDataCollectionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordToolRiskCandidateWritesSanitizedJsonl() throws Exception {
        TestDataCollectionService service = newService();
        ToolRiskShadowContext context = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "sandbox_exec", Map.of("code", "echo safe"), "run echo");

        service.recordToolRiskCandidate("LOW_RISK", context);

        Path file = tempDir.resolve("classifier-training/review-working/tool-risk.autotest.jsonl");
        assertTrue(Files.exists(file));
        JsonNode record = new ObjectMapper().readTree(Files.readString(file).strip());
        assertEquals("tool_risk", record.get("task").asText());
        assertEquals("LOW_RISK", record.get("label").asText());
        assertEquals("sandbox_exec", record.get("input").get("tool_name").asText());
        assertEquals(
                "echo safe", record.get("input").get("parameters").get("code").asText());
    }

    private TestDataCollectionService newService() {
        TestDataCollectionService service = new TestDataCollectionService();
        service.dataDir = tempDir.toString();
        service.mapper = new ObjectMapper();
        InputSanitizer sanitizer = mock(InputSanitizer.class);
        when(sanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        service.piiSanitizer = sanitizer;
        return service;
    }
}
