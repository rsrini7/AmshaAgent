package com.arcclaw.core.agent;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.scope.WorkspaceContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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

    @Test
    void testHallucinatedToolHandledGracefully() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
            var callCount = new AtomicInteger(0);

            ReasoningEngine engine = (sessionId, role, goal, history) -> {
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
        });
    }

    /**
     * Test 2: LLM gets stuck in an infinite loop — never emits FINISH.
     * The maxIterations guard should terminate the loop.
     */
    @Test
    void testMaxIterationsTerminatesRunawayLoop() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
            // Engine that always requests the same tool (never finishes)
            ReasoningEngine engine = (sessionId, role, goal, history) -> ReasoningStep.pending(
                    "I'll keep trying the github tool.",
                    "github",
                    Map.of("repo", "test/repo"), 0, 0);

            int maxIterations = 3;
            AgentResult result = agentLoop.run("infinite loop test", engine, maxIterations);

            assertFalse(result.success(), "Loop should NOT succeed");
            assertEquals(maxIterations, result.steps().size(), "Should have exactly maxIterations steps");
            assertTrue(result.finalAnswer().contains("exceeded 3 iterations"),
                    "Final answer should mention iteration limit");
        });
    }

    /**
     * Test 3: LLM emits FINISH — normal termination.
     */
    @Test
    void testFinishActionReturnsCorrectResult() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
            ReasoningEngine engine = (sessionId, role, goal, history) -> ReasoningStep.pending(
                    "I'm done.",
                    ReasoningStep.FINISH,
                    Map.of("answer", "Task complete."), 0, 0);

            AgentResult result = agentLoop.run("finish test", engine);

            assertTrue(result.success());
            assertEquals("Task complete.", result.finalAnswer());
        });
    }

    /**
     * Test 4: Engine returns null step — handled gracefully.
     */
    @Test
    void testNullStepTerminatesGracefully() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
            ReasoningEngine engine = (sessionId, role, goal, history) -> null;

            AgentResult result = agentLoop.run("null step test", engine);

            assertFalse(result.success());
            assertTrue(result.terminationReason().contains("null step"));
        });
    }

    /**
     * Test 5: Engine throws exception — loop should catch and return error result.
     */
    @Test
    void testEngineExceptionHandled() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
            ReasoningEngine engine = (sessionId, role, goal, history) -> {
                throw new RuntimeException("LLM API Timeout");
            };

            AgentResult result = agentLoop.run("exception test", engine);

            assertFalse(result.success());
            assertTrue(result.terminationReason().contains("ReasoningEngine error"));
            assertTrue(result.terminationReason().contains("LLM API Timeout"));
        });
    }
}
