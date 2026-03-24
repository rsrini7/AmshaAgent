package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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


    @Test
    void testHallucinatedToolHandledGracefully() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
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

            assertInstanceOf(AgentResult.Finished.class, result, "Loop should succeed after self-correction");
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
        });
    }

    /**
     * Test 2: LLM gets stuck in an infinite loop — never emits FINISH.
     * The maxIterations guard should terminate the loop.
     */
    @Test
    void testMaxIterationsTerminatesRunawayLoop() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
            // Engine that always requests the same tool (never finishes)
            ReasoningEngine engine = (sessionId, role, goal, history) -> ReasoningStep.pending(
                    "I'll keep trying the github tool.",
                    "github",
                    Map.of("repo", "test/repo"), 0, 0);

            int maxIterations = 3;
            AgentResult result = agentLoop.run("infinite loop test", engine, maxIterations);

            assertInstanceOf(AgentResult.LoopExceeded.class, result, "Loop should NOT succeed");
            assertEquals(maxIterations, result.steps().size(), "Should have exactly maxIterations steps");
            assertEquals(maxIterations, ((AgentResult.LoopExceeded) result).maxIterations());
            });
        });
    }

    /**
     * Test 3: LLM emits FINISH — normal termination.
     */
    @Test
    void testFinishActionReturnsCorrectResult() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
            ReasoningEngine engine = (sessionId, role, goal, history) -> ReasoningStep.pending(
                    "I'm done.",
                    ReasoningStep.FINISH,
                    Map.of("answer", "Task complete."), 0, 0);

            AgentResult result = agentLoop.run("finish test", engine);

            assertInstanceOf(AgentResult.Finished.class, result);
            assertEquals("Task complete.", ((AgentResult.Finished) result).answer());
            });
        });
    }

    /**
     * Test 4: Engine returns null step — handled gracefully.
     */
    @Test
    void testNullStepTerminatesGracefully() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
            ReasoningEngine engine = (sessionId, role, goal, history) -> null;

            AgentResult result = agentLoop.run("null step test", engine);

            assertInstanceOf(AgentResult.Failed.class, result);
            assertTrue(((AgentResult.Failed) result).reason().contains("null step"));
            });
        });
    }

    /**
     * Test 5: Engine throws exception — loop should catch and return error result.
     */
    @Test
    void testEngineExceptionHandled() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
            ReasoningEngine engine = (sessionId, role, goal, history) -> {
                throw new RuntimeException("LLM API Timeout");
            };

            AgentResult result = agentLoop.run("exception test", engine);

            assertInstanceOf(AgentResult.Failed.class, result);
            assertTrue(((AgentResult.Failed) result).reason().contains("ReasoningEngine error"));
            assertTrue(((AgentResult.Failed) result).reason().contains("LLM API Timeout"));
            });
        });
    }
}
