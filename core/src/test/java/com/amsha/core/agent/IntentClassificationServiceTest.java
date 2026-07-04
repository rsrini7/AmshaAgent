package com.amsha.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class IntentClassificationServiceTest {

    @Inject
    IntentClassificationService intentDetector;

    @Test
    void testWriteIntentDetection() {
        // Core "memory" intents
        assertTrue(intentDetector.isWriteIntent("save this for later"));
        assertTrue(intentDetector.isWriteIntent("store the following result"));
        assertTrue(intentDetector.isWriteIntent("ingest this document"));
        assertTrue(intentDetector.isWriteIntent("remember my name is Srini"));
        assertTrue(intentDetector.isWriteIntent("add to memory: the user likes cats"));
        assertTrue(intentDetector.isWriteIntent("note that the project is finished"));
        assertTrue(intentDetector.isWriteIntent("call me the Galactic Emperor"));
        assertTrue(intentDetector.isWriteIntent("my name is Srini"));
        assertTrue(intentDetector.isWriteIntent("refer to me as Commander"));
        assertTrue(intentDetector.isWriteIntent("address me as Sir"));

        // Case insensitivity
        assertTrue(intentDetector.isWriteIntent("REMEMBER this"));

        // Word boundaries
        assertFalse(intentDetector.isWriteIntent("passenger")); // contains 'save' but not as word

        // Negative cases (read intents)
        assertFalse(intentDetector.isWriteIntent("read the file"));
        assertFalse(intentDetector.isWriteIntent("list all tasks"));
        assertFalse(intentDetector.isWriteIntent("what was the result?"));
        assertFalse(intentDetector.isWriteIntent(null));
        assertFalse(intentDetector.isWriteIntent(""));
    }

    @Test
    void testFollowUpHeuristic() {
        assertTrue(intentDetector.isFollowUpHeuristic("retry same command"));
        assertTrue(intentDetector.isFollowUpHeuristic("run it again"));
        assertTrue(intentDetector.isFollowUpHeuristic("in java please"));
        assertTrue(intentDetector.isFollowUpHeuristic("use sandbox for this"));

        // Too long
        assertFalse(intentDetector.isFollowUpHeuristic("a".repeat(151)));

        // No signals
        assertFalse(intentDetector.isFollowUpHeuristic("hello world"));
    }

    @Test
    void testContinuityCommand() {
        assertTrue(intentDetector.isContinuityCommand("retry"));
        assertTrue(intentDetector.isContinuityCommand("  proceed  "));
        assertTrue(intentDetector.isContinuityCommand("try again"));
        assertTrue(intentDetector.isContinuityCommand("redo"));
        assertTrue(intentDetector.isContinuityCommand("continue"));
        assertTrue(intentDetector.isContinuityCommand("yes."));
        assertTrue(intentDetector.isContinuityCommand("proceed!"));

        // Pattern uses matches() with optional suffix so it matches full phrases starting with signal
        assertTrue(intentDetector.isContinuityCommand("retry the task"));
        assertTrue(intentDetector.isContinuityCommand("retry carefully"));

        // Execution follow-ups — NOT continuity (use NEW goal, not restored old goal)
        assertFalse(intentDetector.isContinuityCommand("execute"));
        assertFalse(intentDetector.isContinuityCommand("run it"));
        assertFalse(intentDetector.isContinuityCommand("do it"));

        // Multi-word execution commands ARE continuity (explicit "execute it", "run that")
        assertTrue(intentDetector.isContinuityCommand("now execute it"));
        assertTrue(intentDetector.isContinuityCommand("execute it"));
        assertTrue(intentDetector.isContinuityCommand("run that"));
        assertFalse(intentDetector.isContinuityCommand("Execute the code"));
        assertFalse(intentDetector.isContinuityCommand("RUN IT"));
    }
}
