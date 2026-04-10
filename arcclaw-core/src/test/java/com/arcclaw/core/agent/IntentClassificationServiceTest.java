package com.arcclaw.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class IntentClassificationServiceTest {

    @Inject
    IntentClassificationService intentClassifier;

    @Test
    void testWriteIntentDetection() {
        // Core "memory" intents
        assertTrue(intentClassifier.isWriteIntent("save this for later"));
        assertTrue(intentClassifier.isWriteIntent("store the following result"));
        assertTrue(intentClassifier.isWriteIntent("ingest this document"));
        assertTrue(intentClassifier.isWriteIntent("remember my name is Srini"));
        assertTrue(intentClassifier.isWriteIntent("add to memory: the user likes cats"));
        assertTrue(intentClassifier.isWriteIntent("note that the project is finished"));
        assertTrue(intentClassifier.isWriteIntent("call me the Galactic Emperor"));

        // Case insensitivity
        assertTrue(intentClassifier.isWriteIntent("REMEMBER this"));
        
        // Word boundaries
        assertFalse(intentClassifier.isWriteIntent("passenger")); // contains 'save' but not as word
        
        // Negative cases (read intents)
        assertFalse(intentClassifier.isWriteIntent("read the file"));
        assertFalse(intentClassifier.isWriteIntent("list all tasks"));
        assertFalse(intentClassifier.isWriteIntent("what was the result?"));
        assertFalse(intentClassifier.isWriteIntent(null));
        assertFalse(intentClassifier.isWriteIntent(""));
    }

    @Test
    void testFollowUpHeuristic() {
        assertTrue(intentClassifier.isFollowUpHeuristic("retry same command"));
        assertTrue(intentClassifier.isFollowUpHeuristic("run it again"));
        assertTrue(intentClassifier.isFollowUpHeuristic("in java please"));
        assertTrue(intentClassifier.isFollowUpHeuristic("use sandbox for this"));
        
        // Too long
        assertFalse(intentClassifier.isFollowUpHeuristic("a".repeat(151)));
        
        // No signals
        assertFalse(intentClassifier.isFollowUpHeuristic("hello world"));
    }

    @Test
    void testContinuityCommand() {
        assertTrue(intentClassifier.isContinuityCommand("retry"));
        assertTrue(intentClassifier.isContinuityCommand("  proceed  "));
        assertTrue(intentClassifier.isContinuityCommand("try again"));
        assertTrue(intentClassifier.isContinuityCommand("redo"));
        assertTrue(intentClassifier.isContinuityCommand("continue"));
        
        // Pattern uses matches() with optional suffix so it matches full phrases starting with signal
        assertTrue(intentClassifier.isContinuityCommand("retry the task"));
        assertTrue(intentClassifier.isContinuityCommand("retry carefully"));
    }
}
