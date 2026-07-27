package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HallucinationDetectorTest {

    private HallucinationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new HallucinationDetector(new ObjectMapper());
    }

    @Test
    void testNullAnswerReturnsNull() {
        assertNull(detector.checkHallucination(
                "sess-1", List.of(), new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false), null));
    }

    @Test
    void testHallucinatedScheduleDetected() {
        ReasoningStep step = new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false);
        String answer = "I have scheduled the task to run every 5 minutes";

        var outcome = detector.checkHallucination("sess-1", List.of(), step, answer);
        assertNotNull(outcome);
        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }

    @Test
    void testHallucinatedScheduleIgnoredWhenNegative() {
        ReasoningStep step = new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false);
        String answer = "The task was not scheduled due to an error";

        var outcome = detector.checkHallucination("sess-1", List.of(), step, answer);
        assertNull(outcome);
    }

    @Test
    void testHallucinatedScheduleIgnoredWhenScheduleToolSucceeded() {
        ReasoningStep step = new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false);
        ReasoningStep scheduleStep = new ReasoningStep.ToolCallStep(
                "t", "schedule_task", Map.of("cron", "0 * * * *"), "ok", 0, 0, true, null, null, null, false, false);
        String answer = "I have scheduled the task for you";

        var outcome = detector.checkHallucination("sess-1", List.of(scheduleStep), step, answer);
        assertNull(outcome);
    }

    @Test
    void testHallucinatedStorageDetected() {
        ReasoningStep step = new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false);
        String answer = "I have stored your preference in memory";

        var outcome = detector.checkHallucination("sess-1", List.of(), step, answer);
        assertNotNull(outcome);
        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }

    @Test
    void testHallucinatedStorageIgnoredWhenIngestDocumentSucceeded() {
        ReasoningStep step = new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false);
        ReasoningStep ingestStep = new ReasoningStep.ToolCallStep(
                "t",
                "ingest_document",
                Map.of("content", "user preference"),
                "ok",
                0,
                0,
                true,
                null,
                null,
                null,
                false,
                false);
        String answer = "I have stored your preference in memory";

        var outcome = detector.checkHallucination("sess-1", List.of(ingestStep), step, answer);
        assertNull(outcome);
    }

    @Test
    void testKnowledgeDisclaimerDetectedWhenMemorySearchFoundResults() {
        ReasoningStep step = new ReasoningStep.FinishStep("t", Map.of(), 0, 0, false);
        ReasoningStep recallStep = new ReasoningStep.ToolCallStep(
                "t",
                "recall_memory_search",
                Map.of("query", "user name"),
                "Name: Alice",
                0,
                0,
                true,
                null,
                null,
                null,
                false,
                false);
        String answer = "I do not have any information about your name";

        var outcome = detector.checkHallucination("sess-1", List.of(recallStep), step, answer);
        assertNotNull(outcome);
        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }
}
