package com.amsha.app.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.RateLimitRetryScheduler;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;

/** Recovers an eligible rate-limit checkpoint through the production retry scheduler after restart. */
@QuarkusTest
@TestProfile(AgentRunEvaluationRestartIT.RestartedApplicationProfile.class)
class AgentRunEvaluationRetryRestartIT {
    private static final String COORDINATOR_ROLE = "coordinator";
    private static final String FAILURE_SENTINEL = AgentRunEvaluationScriptedModel.FAILURE_SENTINEL;
    private static final String TOOL_OUTPUT_SENTINEL = "Synthetic repository metadata.";

    @InjectMock
    ReasoningEngine reasoningEngine;

    @InjectSpy
    ToolRegistry toolRegistry;

    @InjectSpy
    SandboxExecutionPort sandboxExecutionPort;

    @Inject
    RateLimitRetryScheduler rateLimitRetryScheduler;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ObjectMapper objectMapper;

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolExecutions = new AtomicInteger();
    private final AtomicInteger sandboxExecutions = new AtomicInteger();

    @Test
    void recoversEligibleRetryAfterRestartWithoutReplayingReadOnlyTool() throws Exception {
        AgentRunEvaluationCorpusSupport.Corpus corpus =
                AgentRunEvaluationCorpusSupport.readCorpus(getClass(), objectMapper);
        AgentRunEvaluationCorpusSupport.CorpusCase recoveryCase = corpus.cases().stream()
                .filter(evidenceCase ->
                        AgentRunEvaluationCorpusSupport.ELIGIBLE_RETRY_CASE_ID.equals(evidenceCase.id()))
                .findFirst()
                .orElseThrow();
        String sessionId = AgentRunEvaluationCorpusSupport.eligibleRetrySessionId(objectMapper);
        AgentCheckpoint pendingRetry =
                agentStore.load(sessionId, COORDINATOR_ROLE).orElseThrow();
        assertEquals(CheckpointStatus.AWAITING_RETRY, pendingRetry.getStatus());
        List<ReasoningStep> savedSteps = agentStore.deserializeSteps(pendingRetry);
        assertTrue(savedSteps.stream()
                .anyMatch(step -> ToolCapability.CORE_MEMORY_READ.toolName().equals(step.action())
                        && Boolean.TRUE.equals(step.success())));

        stubFinish(recoveryCase.expectedAnswer());
        doAnswer(invocation -> {
                    toolExecutions.incrementAndGet();
                    return invocation.callRealMethod();
                })
                .when(toolRegistry)
                .invokeTool(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
                    sandboxExecutions.incrementAndGet();
                    return SandboxResult.success(TOOL_OUTPUT_SENTINEL);
                })
                .when(sandboxExecutionPort)
                .executeSandboxed(anyString(), any(Path.class), anyList(), anyMap());

        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(8);
        AgentCheckpoint recovered = pendingRetry;
        while (System.nanoTime() < deadline && recovered.getStatus() != CheckpointStatus.COMPLETED) {
            rateLimitRetryScheduler.processPendingRetries();
            recovered = agentStore.load(sessionId, COORDINATOR_ROLE).orElseThrow();
            if (recovered.getStatus() != CheckpointStatus.COMPLETED) {
                Thread.sleep(25);
            }
        }
        long recoveryLatencyMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        List<ReasoningStep> recoveredSteps = agentStore.deserializeSteps(recovered);
        boolean finished = recoveredSteps.stream()
                .anyMatch(step -> ReasoningStep.FINISH.equals(step.action())
                        && recoveryCase
                                .expectedAnswer()
                                .equals(step.actionInput().get("answer")));
        String lastStepAction = recoveredSteps.isEmpty()
                ? "unavailable"
                : recoveredSteps.getLast().action();

        assertEquals(CheckpointStatus.COMPLETED, recovered.getStatus(), "Retry scheduler did not finish the run");
        assertTrue(finished, "Recovered checkpoint did not persist the expected FINISH step");
        assertEquals(0, toolExecutions.get(), "Retry recovery duplicated the already approved tool execution");
        assertEquals(0, sandboxExecutions.get(), "Retry recovery duplicated sandbox execution");
        assertTrue(modelCalls.get() > 0, "Retry scheduler did not cross the reasoning boundary");

        AgentRunEvaluationCorpusSupport.recordEligibleRetryResume(
                objectMapper,
                "passed",
                "none",
                recovered.getStatus().name(),
                recoveryLatencyMs,
                modelCalls.get(),
                toolExecutions.get(),
                recoveredSteps.size(),
                lastStepAction);
        String report = Files.readString(Path.of("target", "agent-run-evaluation", "corpus-v2-report.json"));
        assertFalse(report.contains(FAILURE_SENTINEL), "Synthetic failure output leaked into the report");
        assertFalse(report.contains(TOOL_OUTPUT_SENTINEL), "Tool output leaked into the report");
    }

    private void stubFinish(String expectedAnswer) {
        when(reasoningEngine.nextStep(any(AgentExecutionContext.class), anyString(), anyString(), anyList()))
                .thenAnswer(_ -> {
                    modelCalls.incrementAndGet();
                    return ReasoningStep.pending(
                            "Complete synthetic evaluation case.",
                            ReasoningStep.FINISH,
                            Map.of("answer", expectedAnswer),
                            1,
                            2);
                });
    }
}
