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
        assertTrue(intentDetector.isFollowUpHeuristic("execute and share result"));
        assertTrue(intentDetector.isFollowUpHeuristic("run it again"));
        assertTrue(intentDetector.isFollowUpHeuristic("in java please"));
        assertTrue(intentDetector.isFollowUpHeuristic("use sandbox for this"));

        // Too long
        assertFalse(intentDetector.isFollowUpHeuristic("a".repeat(151)));

        // No signals
        assertFalse(intentDetector.isFollowUpHeuristic("hello world"));
    }

    @Test
    void testExecutionFollowUpsAreNotSimpleConversation() {
        assertFalse(intentDetector.isSimpleConversational("execute and share result"));
        assertFalse(intentDetector.isSimpleConversational("run it again"));
        assertFalse(intentDetector.isSimpleConversational("do it"));
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

    @Test
    void testSelfEvolveIntentDetection() {
        assertTrue(
                intentDetector.isSelfEvolveIntent("trigger self_evolve_reflect to synthesize behavioral directives"));
        assertTrue(intentDetector.isSelfEvolveIntent("what is the current directive status"));
        assertTrue(intentDetector.isSelfEvolveIntent("synthesize behavioral directives"));

        assertFalse(intentDetector.isSelfEvolveIntent("show me current status"));
        assertFalse(intentDetector.isSelfEvolveIntent("reflect on the code"));
        assertFalse(intentDetector.isSelfEvolveIntent(null));
        assertFalse(intentDetector.isSelfEvolveIntent(""));
    }

    @Test
    void testGreetingDetectionRejectsToolVerbs() {
        assertTrue(intentDetector.isGreeting("Hi"));
        assertTrue(intentDetector.isGreeting("Hello"));
        assertTrue(intentDetector.isGreeting("Hey there"));
        assertTrue(intentDetector.isGreeting("Good morning"));
        assertTrue(intentDetector.isGreeting("Hello everyone"));
        assertTrue(intentDetector.isGreeting("Namaste"));
        assertTrue(intentDetector.isGreeting("Hi team"));
        assertTrue(intentDetector.isGreeting("Hello!"));

        // Action verbs / tool keywords must NOT be classified as greetings
        assertFalse(intentDetector.isGreeting("Hi execute"));
        assertFalse(intentDetector.isGreeting("Hello debug"));
        assertFalse(intentDetector.isGreeting("Hey write"));
        assertFalse(intentDetector.isGreeting("Hi run"));
        assertFalse(intentDetector.isGreeting("Hello search"));
    }

    @Test
    void testSimpleConversationalDoesNotFalseFlagConversationalPhrases() {
        // Humor & empathy
        assertTrue(intentDetector.isSimpleConversational("make me laugh"));
        assertTrue(intentDetector.isSimpleConversational("show me empathy"));
        assertTrue(intentDetector.isSimpleConversational("tell me a joke"));
        assertTrue(intentDetector.isSimpleConversational("what do you think?"));

        // Politeness, acknowledgments, small talk & closings
        assertTrue(intentDetector.isSimpleConversational("thanks"));
        assertTrue(intentDetector.isSimpleConversational("thank you so much"));
        assertTrue(intentDetector.isSimpleConversational("thanks for the help"));
        assertTrue(intentDetector.isSimpleConversational("how are you"));
        assertTrue(intentDetector.isSimpleConversational("how are you doing"));
        assertTrue(intentDetector.isSimpleConversational("goodbye"));
        assertTrue(intentDetector.isSimpleConversational("have a nice day"));
        assertTrue(intentDetector.isSimpleConversational("got it"));
        assertTrue(intentDetector.isSimpleConversational("sounds good"));
        assertTrue(intentDetector.isSimpleConversational("nice job!"));

        // Greetings
        assertTrue(intentDetector.isSimpleConversational("Hi"));
        assertTrue(intentDetector.isSimpleConversational("Hello there"));
        assertTrue(intentDetector.isSimpleConversational("Good morning"));

        // Non-conversational instructions, tool calls, and short questions
        assertFalse(intentDetector.isSimpleConversational("search for recipes on web"));
        assertFalse(intentDetector.isSimpleConversational("execute prime number finder"));
        assertFalse(intentDetector.isSimpleConversational("run the script in sandbox"));
        assertFalse(intentDetector.isSimpleConversational("explain quicksort"));
        assertFalse(intentDetector.isSimpleConversational("who r u"));
        assertFalse(intentDetector.isSimpleConversational("who are you"));
    }

    @Test
    void testIdentityQueryDetection() {
        // User identity queries (memory lookup)
        assertTrue(intentDetector.isIdentityQuery("who am i"));
        assertTrue(intentDetector.isIdentityQuery("do you know my name?"));
        assertTrue(intentDetector.isIdentityQuery("what is my email?"));

        // Agent identity & capability queries (persona lookup)
        assertTrue(intentDetector.isIdentityQuery("who r u"));
        assertTrue(intentDetector.isIdentityQuery("who are you"));
        assertTrue(intentDetector.isIdentityQuery("who is this"));
        assertTrue(intentDetector.isIdentityQuery("what is your name"));
        assertTrue(intentDetector.isIdentityQuery("what model are you"));
        assertTrue(intentDetector.isIdentityQuery("tell me about yourself"));
        assertTrue(intentDetector.isIdentityQuery("what can you do"));
        assertTrue(intentDetector.isIdentityQuery("what is amsha"));
        assertTrue(intentDetector.isIdentityQuery("who is amsha"));

        assertFalse(intentDetector.isIdentityQuery("what is 2+2"));
        assertFalse(intentDetector.isIdentityQuery("how does recursion work"));
    }
}
