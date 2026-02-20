package com.arcclaw.core.agent;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.scope.WorkspaceContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resilience tests for the Agent reasoning loop.
 * <p>
 * Uses mock {@link ReasoningEngine} implementations with scripted responses
 * to verify loop guardrails without requiring a live LLM.
 */
@QuarkusTest
class AgentResilienceTest {

    private static final String TEST_SESSION = "test-agent-session";

    @Inject
    AgentLoop agentLoop;

    @Inject
    WorkspaceContext workspaceContext;

    @BeforeEach
    void activate() {
        WorkspaceScopeHolder.set(TEST_SESSION);
        workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
    }

    @AfterEach
    void deactivate() {
        WorkspaceScopeHolder.clear();
    }

    /**
     * Test 1: LLM requests a non-existent tool.
     * The loop should NOT crash — it should inject an error observation
     * and allow the engine to self-correct on the next iteration.
     */
    @Test
    void testHallucinatedToolHandledGracefully() {
        var callCount = new AtomicInteger(0);

        ReasoningEngine engine = (goal, history) -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                // First call: LLM hallucinates a tool that doesn't exist
                return ReasoningStep.pending(
                        "I need to search the web for this.",
                        "web_search", // <-- does NOT exist
                        Map.of("query", "test query"), 0, 0);
            }
            // Second call: after receiving the error observation, LLM self-corrects
            return ReasoningStep.pending(
                    "The web_search tool doesn't exist. I'll finish with what I know.",
                    ReasoningStep.FINISH,
                    Map.of("answer", "Self-corrected after tool not found."), 0, 0);
        };

        AgentResult result = agentLoop.run("test hallucination", engine);

        assertTrue(result.success(), "Loop should succeed after self-correction");
        assertEquals(2, result.steps().size(), "Should have 2 steps (hallucination + finish)");

        // First step should have the error observation
        var firstStep = result.steps().get(0);
        assertEquals("web_search", firstStep.action());
        assertNotNull(firstStep.observation());
        assertTrue(firstStep.observation().contains("does not exist"),
                "Observation should explain the tool doesn't exist");
        assertTrue(firstStep.observation().contains("Available tools"),
                "Observation should list available tools");
    }

    /**
     * Test 2: LLM gets stuck in an infinite loop — never emits FINISH.
     * The maxIterations guard should terminate the loop.
     */
    @Test
    void testMaxIterationsTerminatesRunawayLoop() {
        // Engine that always requests the same tool (never finishes)
        ReasoningEngine engine = (goal, history) -> ReasoningStep.pending(
                "I'll keep trying the github tool.",
                "github",
                Map.of("repo", "test/repo"), 0, 0);

        int maxIterations = 3;
        AgentResult result = agentLoop.run("infinite loop test", engine, maxIterations);

        assertFalse(result.success(), "Loop should NOT succeed");
        assertEquals("MAX_ITERATIONS_EXCEEDED", result.terminationReason());
        assertEquals(maxIterations, result.steps().size(),
                "Should have exactly " + maxIterations + " steps");
        assertTrue(result.finalAnswer().contains("exceeded"),
                "Final answer should mention iteration limit");
    }

    /**
     * Test 3: LLM immediately emits FINISH — happy path.
     */
    @Test
    void testFinishActionReturnsCorrectResult() {
        ReasoningEngine engine = (goal, history) -> ReasoningStep.pending(
                "I already know the answer.",
                ReasoningStep.FINISH,
                Map.of("answer", "42 is the answer."), 0, 0);

        AgentResult result = agentLoop.run("what is the answer?", engine);

        assertTrue(result.success());
        assertEquals("FINISH", result.terminationReason());
        assertEquals("42 is the answer.", result.finalAnswer());
        assertEquals(1, result.steps().size());
    }

    /**
     * Test 4: Engine returns null step — loop should terminate gracefully.
     */
    @Test
    void testNullStepTerminatesGracefully() {
        ReasoningEngine engine = (goal, history) -> null;

        AgentResult result = agentLoop.run("null test", engine);

        assertFalse(result.success());
        assertTrue(result.terminationReason().contains("null"),
                "Should explain null step termination");
    }

    /**
     * Test 5: Engine throws exception — loop should return error result.
     */
    @Test
    void testEngineExceptionHandled() {
        ReasoningEngine engine = (goal, history) -> {
            throw new RuntimeException("LLM API timeout");
        };

        AgentResult result = agentLoop.run("exception test", engine);

        assertFalse(result.success());
        assertTrue(result.terminationReason().contains("LLM API timeout"),
                "Should contain the error message");
    }
}
