package com.amsha.selfevolve.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.internal.ExtractionDeduplicator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryExtractorLiteTest {

    private MemoryExtractorLite extractor;

    @BeforeEach
    void setUp() {
        extractor = new MemoryExtractorLite(new ExtractionDeduplicator());
    }

    @Test
    void testExtractEmptyEvents() {
        assertTrue(extractor.extract(null, "usr-1").isEmpty());
        assertTrue(extractor.extract(List.of(), "usr-1").isEmpty());
    }

    @Test
    void testExtractUserPreference() {
        EvolutionEvent event = EvolutionEvent.create(
                "sess-1",
                "usr-1",
                EvolutionEventType.USER_MESSAGE,
                "coder",
                "Please use Java 25 features for this project",
                null,
                1,
                null);

        List<ExtractedMemory> memories = extractor.extract(List.of(event), "usr-1");
        assertFalse(memories.isEmpty());
        assertEquals(MemoryRecordType.USER_PREFERENCE, memories.get(0).recordType());
    }

    @Test
    void testExtractFailureAndResolutionPatterns() {
        EvolutionEvent err1 = EvolutionEvent.create(
                "sess-1",
                "usr-1",
                EvolutionEventType.TOOL_ERROR,
                "coder",
                "command not found: docker",
                "shell_exec",
                1,
                null);
        EvolutionEvent err2 = EvolutionEvent.create(
                "sess-1",
                "usr-1",
                EvolutionEventType.TOOL_ERROR,
                "coder",
                "command not found: docker",
                "shell_exec",
                2,
                null);
        EvolutionEvent res = EvolutionEvent.create(
                "sess-1",
                "usr-1",
                EvolutionEventType.TOOL_RESULT,
                "coder",
                "docker version 24.0.0 installed successfully",
                "shell_exec",
                3,
                null);

        List<ExtractedMemory> memories = extractor.extract(List.of(err1, err2, res), "usr-1");
        assertFalse(memories.isEmpty());
        assertTrue(memories.stream().anyMatch(m -> m.recordType() == MemoryRecordType.FAILURE_PATTERN));
        assertTrue(memories.stream().anyMatch(m -> m.recordType() == MemoryRecordType.RESOLUTION_PATTERN));
    }

    @Test
    void testExtractSelfCorrectionAndEscalation() {
        EvolutionEvent sc = EvolutionEvent.create(
                "sess-1",
                "usr-1",
                EvolutionEventType.SELF_CORRECTION,
                "coder",
                "Retrying with proper parameter types",
                null,
                1,
                null);
        EvolutionEvent esc = EvolutionEvent.create(
                "sess-1",
                "usr-1",
                EvolutionEventType.ESCALATION,
                "architect",
                "Escalating to architect role",
                null,
                2,
                null);

        List<ExtractedMemory> memories = extractor.extract(List.of(sc, esc), "usr-1");
        assertEquals(2, memories.size());
        assertTrue(memories.stream().anyMatch(m -> m.recordType() == MemoryRecordType.WORKAROUND));
        assertTrue(memories.stream().anyMatch(m -> m.recordType() == MemoryRecordType.ACTIVE_TASK));
    }
}
