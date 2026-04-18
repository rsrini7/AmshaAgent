package com.arcclaw.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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
        
        // Pattern uses matches() with optional suffix so it matches full phrases starting with signal
        assertTrue(intentDetector.isContinuityCommand("retry the task"));
        assertTrue(intentDetector.isContinuityCommand("retry carefully"));
    }
}
