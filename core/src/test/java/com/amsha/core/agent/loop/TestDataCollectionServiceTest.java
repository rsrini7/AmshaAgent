package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.security.InputSanitizer;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
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
                "coder",
                AutonomyLevel.FULL,
                "sandbox_exec",
                ToolOperation.EXECUTE_SHELL,
                Map.of("code", "echo safe"),
                "run echo");

        service.recordToolRiskCandidate("LOW_RISK", context);

        Path file = tempDir.resolve("classifier-training/review-working/tool-risk.autotest.jsonl");
        assertTrue(Files.exists(file));
        JsonNode record = new ObjectMapper().readTree(Files.readString(file).strip());
        assertEquals("tool_risk", record.get("task").asText());
        assertEquals("LOW_RISK", record.get("label").asText());
        assertEquals("sandbox_exec", record.get("input").get("tool_name").asText());
        assertEquals(
                "EXECUTE_SHELL", record.get("input").get("required_operation").asText());
        assertEquals(
                "echo safe", record.get("input").get("parameters").get("code").asText());
    }

    @Test
    void recordIntentCandidateWritesMessageDataset() throws Exception {
        TestDataCollectionService service = newService();

        service.recordIntentCandidate("coder", "Write a parser for the input format");

        Path file = tempDir.resolve("classifier-training/review-working/intent.autotest.jsonl");
        JsonNode record = new ObjectMapper().readTree(Files.readString(file).strip());
        assertEquals("intent", record.get("task").asText());
        assertEquals("coder", record.get("label").asText());
        assertEquals(
                "Write a parser for the input format",
                record.get("input").get("message").asText());
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
