package com.amsha.selfevolve.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.internal.ExtractionDeduplicator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryExtractorRuleTest {

    private MemoryExtractorLite extractor;

    @BeforeEach
    void setUp() {
        extractor = new MemoryExtractorLite(new ExtractionDeduplicator());
    }

    @Test
    void emptyEvents_returnsEmpty() {
        assertTrue(extractor.extract(List.of(), "user1").isEmpty());
    }

    @Test
    void nullEvents_returnsEmpty() {
        assertTrue(extractor.extract(null, "user1").isEmpty());
    }

    @Test
    void failurePattern_sameToolFailsTwice_extractsFailure() {
        Instant now = Instant.now();
        List<EvolutionEvent> events = List.of(
                event("e1", EvolutionEventType.TOOL_ERROR, "user1", "Session: s1, tool err", "sandbox_exec", 1, now),
                event(
                        "e2",
                        EvolutionEventType.TOOL_ERROR,
                        "user1",
                        "Session: s1, tool err again",
                        "sandbox_exec",
                        2,
                        now));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.FAILURE_PATTERN));
    }

    @Test
    void failurePattern_singleError_doesNotExtract() {
        List<EvolutionEvent> events =
                List.of(event("e1", EvolutionEventType.TOOL_ERROR, "user1", "error", "sandbox_exec", 1, Instant.now()));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertTrue(results.stream().noneMatch(m -> m.recordType() == MemoryRecordType.FAILURE_PATTERN));
    }

    @Test
    void resolutionPattern_errorThenSuccess_extractsResolution() {
        Instant now = Instant.now();
        List<EvolutionEvent> events = List.of(
                event("e1", EvolutionEventType.TOOL_ERROR, "user1", "error msg", "sandbox_exec", 1, now),
                event("e2", EvolutionEventType.TOOL_RESULT, "user1", "success", "sandbox_exec", 2, now.plusSeconds(1)));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.RESOLUTION_PATTERN));
    }

    @Test
    void toolHint_longToolResult_extractsToolHint() {
        String longResult = "A".repeat(30);
        List<EvolutionEvent> events = List.of(event(
                "e1", EvolutionEventType.TOOL_RESULT, "user1", longResult, "serper_web_search", 1, Instant.now()));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.TOOL_HINT));
    }

    @Test
    void errorContext_longError_extractsErrorContext() {
        String longError = "Error: " + "A".repeat(30);
        List<EvolutionEvent> events = List.of(
                event("e1", EvolutionEventType.TOOL_ERROR, "user1", longError, "sandbox_exec", 1, Instant.now()));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.ERROR_CONTEXT));
    }

    @Test
    void selfCorrectionEvent_extractsWorkaround() {
        List<EvolutionEvent> events = List.of(event(
                "e1", EvolutionEventType.SELF_CORRECTION, "user1", "Retried after timeout", null, 1, Instant.now()));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.WORKAROUND));
    }

    @Test
    void escalationEvent_extractsActiveTask() {
        List<EvolutionEvent> events = List.of(event(
                "e1",
                EvolutionEventType.ESCALATION,
                "user1",
                "Delegated to debugger",
                "coordinator",
                1,
                Instant.now()));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.ACTIVE_TASK));
    }

    @Test
    void userPreferenceMessage_extractsUserPreference() {
        List<EvolutionEvent> events = List.of(event(
                "e1",
                EvolutionEventType.USER_MESSAGE,
                "user1",
                "Please use Java records over Lombok for immutable DTOs.",
                null,
                1,
                Instant.now()));

        List<ExtractedMemory> results = extractor.extract(events, "user1");

        assertTrue(results.stream().anyMatch(m -> m.recordType() == MemoryRecordType.USER_PREFERENCE));
        assertTrue(results.stream()
                .filter(m -> m.recordType() == MemoryRecordType.USER_PREFERENCE)
                .anyMatch(m -> m.content().contains("Java records over Lombok")));
    }

    @Test
    void duplicateContent_deduplicated() {
        Instant now = Instant.now();
        // Two identical error contexts should deduplicate
        List<EvolutionEvent> events = List.of(
                event("e1", EvolutionEventType.TOOL_ERROR, "user1", "Error: " + "A".repeat(30), "sandbox_exec", 1, now),
                event(
                        "e2",
                        EvolutionEventType.TOOL_ERROR,
                        "user1",
                        "Error: " + "A".repeat(30),
                        "sandbox_exec",
                        2,
                        now));

        List<ExtractedMemory> results = extractor.extract(events, "user1");
        // Error context + failure pattern should both be detected
        // But error context content is the same -> deduplicated
        long errorContextCount = results.stream()
                .filter(m -> m.recordType() == MemoryRecordType.ERROR_CONTEXT)
                .count();
        assertTrue(errorContextCount <= 1, "Duplicate error contexts should be deduplicated");
    }

    private static EvolutionEvent event(
            String id,
            EvolutionEventType type,
            String userId,
            String content,
            String toolName,
            int turn,
            Instant createdAt) {
        return new EvolutionEvent(id, "s1", userId, type, "role", content, toolName, turn, null, createdAt);
    }
}
