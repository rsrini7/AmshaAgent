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
    void testRetryCommand() {
        assertTrue(intentDetector.isRetryCommand("retry"));
        assertTrue(intentDetector.isRetryCommand("try again"));
        assertTrue(intentDetector.isRetryCommand("redo"));
        assertTrue(intentDetector.isRetryCommand("same"));
        assertTrue(intentDetector.isRetryCommand("again"));
        assertTrue(intentDetector.isRetryCommand("repeat"));
        assertTrue(intentDetector.isRetryCommand("retry the task"));
        assertTrue(intentDetector.isRetryCommand("retry carefully"));

        assertFalse(intentDetector.isRetryCommand("proceed"));
        assertFalse(intentDetector.isRetryCommand("continue"));
        assertFalse(intentDetector.isRetryCommand("go ahead"));
    }

    @Test
    void testContinuationCommand() {
        assertTrue(intentDetector.isContinuationCommand("  proceed  "));
        assertTrue(intentDetector.isContinuationCommand("continue"));
        assertTrue(intentDetector.isContinuationCommand("go ahead"));
        assertTrue(intentDetector.isContinuationCommand("carry on"));
        assertTrue(intentDetector.isContinuationCommand("keep going"));
        assertTrue(intentDetector.isContinuationCommand("resume"));
        assertTrue(intentDetector.isContinuationCommand("yes."));
        assertTrue(intentDetector.isContinuationCommand("okay!"));

        assertFalse(intentDetector.isContinuationCommand("retry"));
        assertFalse(intentDetector.isContinuationCommand("try again"));
        assertFalse(intentDetector.isContinuationCommand("redo"));
    }

    @Test
    void testResumeCommandCombinesRetryAndContinuation() {
        assertTrue(intentDetector.isResumeCommand("retry"));
        assertTrue(intentDetector.isResumeCommand("proceed"));
        assertTrue(intentDetector.isResumeCommand("continue"));
        assertFalse(intentDetector.isResumeCommand("execute"));
        assertFalse(intentDetector.isResumeCommand("run it"));
        assertFalse(intentDetector.isResumeCommand("do it"));
    }
}
