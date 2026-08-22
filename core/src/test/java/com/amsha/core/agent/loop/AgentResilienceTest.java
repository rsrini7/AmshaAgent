package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

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

                ReasoningEngine engine = new ReasoningEngine() {
                    @Override
                    public ReasoningStep nextStep(
                            AgentExecutionContext context, String role, String goal, List<ReasoningStep> history) {
                        int call = callCount.incrementAndGet();
                        if (call == 1) {
                            // First call: LLM hallucinates a tool that doesn't exist
                            return ReasoningStep.pending(
                                    "I need to search the web for this.",
                                    "web_search", // <-- does NOT exist
                                    Map.of("query", "test query"),
                                    100,
                                    100);
                        }
                        // Second call: after receiving the error observation, LLM self-corrects
                        return ReasoningStep.pending(
                                "The web_search tool doesn't exist. I'll finish with what I know.",
                                ReasoningStep.FINISH,
                                Map.of("answer", "Self-corrected after tool not found."),
                                100,
                                100);
                    }

                    @Override
                    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
                        throw new UnsupportedOperationException();
                    }
                };

                AgentResult result = agentLoop.run("test hallucination", engine);

                assertInstanceOf(AgentResult.Finished.class, result, "Loop should succeed after self-correction");
                assertEquals(2, result.steps().size(), "Should have 2 steps (hallucination + finish)");

                // First step should have the error observation
                var firstStep = result.steps().get(0);
                assertEquals("web_search", firstStep.action());
                assertNotNull(firstStep.observation());
                assertTrue(
                        firstStep.observation().contains("does not exist"),
                        "Observation should explain the tool doesn't exist");
                assertTrue(
                        firstStep.observation().contains("Available tools"), "Observation should list available tools");
            });
        });
    }

    /**
     * Test 2: LLM gets stuck in an infinite loop - never emits FINISH.
     * The maxIterations guard should terminate the loop.
     */
    @Test
    void testMaxIterationsTerminatesRunawayLoop() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
                // Engine that always requests the same tool (never finishes)
                ReasoningEngine engine = new ReasoningEngine() {
                    @Override
                    public ReasoningStep nextStep(
                            AgentExecutionContext context, String role, String goal, List<ReasoningStep> history) {
                        return ReasoningStep.pending(
                                "I'll keep trying the github tool.",
                                ToolCapability.GITHUB.toolName(),
                                Map.of("repo", "test/repo"),
                                100,
                                100);
                    }

                    @Override
                    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
                        throw new UnsupportedOperationException();
                    }
                };

                int maxIterations = 3;
                AgentResult result = agentLoop.run("infinite loop test", engine, maxIterations);

                assertInstanceOf(AgentResult.LoopExceeded.class, result, "Loop should NOT succeed");
                // At 3 iterations, the loop terminates. The history contains the 3 steps
                // plus any final state transitions or markers.
                // In the current runner, it hits iteration 4, break, and returns LoopExceeded.
                assertTrue(result.steps().size() >= maxIterations, "Should have at least maxIterations steps");
                assertEquals(maxIterations, ((AgentResult.LoopExceeded) result).maxIterations());
            });
        });
    }

    /**
     * Test 3: LLM emits FINISH - normal termination.
     */
    @Test
    void testFinishActionReturnsCorrectResult() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
                ReasoningEngine engine = new ReasoningEngine() {
                    @Override
                    public ReasoningStep nextStep(
                            AgentExecutionContext context, String role, String goal, List<ReasoningStep> history) {
                        return ReasoningStep.pending(
                                "I'm done.", ReasoningStep.FINISH, Map.of("answer", "Task complete."), 100, 100);
                    }

                    @Override
                    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
                        throw new UnsupportedOperationException();
                    }
                };

                AgentResult result = agentLoop.run("finish test", engine);

                assertInstanceOf(AgentResult.Finished.class, result);
                assertEquals("Task complete.", ((AgentResult.Finished) result).answer());
            });
        });
    }

    /**
     * Test 4: Engine returns null step - handled gracefully.
     */
    @Test
    void testNullStepTerminatesGracefully() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
                ReasoningEngine engine = new ReasoningEngine() {
                    @Override
                    public ReasoningStep nextStep(
                            AgentExecutionContext context, String role, String goal, List<ReasoningStep> history) {
                        return null;
                    }

                    @Override
                    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
                        throw new UnsupportedOperationException();
                    }
                };

                AgentResult result = agentLoop.run("null step test", engine);

                assertInstanceOf(AgentResult.Failed.class, result);
                assertTrue(((AgentResult.Failed) result).reason().contains("null step"));
            });
        });
    }

    /**
     * Test 5: Engine throws exception - loop should catch and return error result.
     */
    @Test
    void testEngineExceptionHandled() {
        WorkspaceScopeHolder.run(TEST_SESSION, () -> {
            WorkspaceContext.runWithFullAutonomy(TEST_SESSION, "test", "test", "coordinator", () -> {
                ReasoningEngine engine = new ReasoningEngine() {
                    @Override
                    public ReasoningStep nextStep(
                            AgentExecutionContext context, String role, String goal, List<ReasoningStep> history) {
                        throw new RuntimeException("LLM API Timeout");
                    }

                    @Override
                    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
                        throw new UnsupportedOperationException();
                    }
                };

                AgentResult result = agentLoop.run("exception test", engine);

                assertInstanceOf(AgentResult.Failed.class, result);
                assertTrue(((AgentResult.Failed) result).reason().contains("LLM API Timeout"));
            });
        });
    }
}
