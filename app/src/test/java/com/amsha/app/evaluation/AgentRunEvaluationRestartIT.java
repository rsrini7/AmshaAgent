package com.amsha.app.evaluation;

import static io.restassured.RestAssured.given;
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
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/** Completes the durable pause case in a fresh Quarkus application runtime. */
@QuarkusTest
@TestProfile(AgentRunEvaluationRestartIT.RestartedApplicationProfile.class)
class AgentRunEvaluationRestartIT {
    private static final String API_KEY_HEADER = "X-Amsha-API-Key";
    private static final String FAILURE_SENTINEL = "synthetic-tool-output-must-not-enter-report";

    @InjectMock
    ReasoningEngine reasoningEngine;

    @InjectSpy
    SandboxExecutionPort sandboxExecutionPort;

    @InjectSpy
    ToolRegistry toolRegistry;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    CheckpointCrudPort checkpointCrudPort;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "amsha.api.keys.internal")
    String internalApiKey;

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolExecutions = new AtomicInteger();
    private final AtomicInteger sandboxExecutions = new AtomicInteger();

    @Test
    void recordsStaleApprovalDenialAfterApplicationRestart() throws Exception {
        apiKeyRegistry.register("internal", internalApiKey);
        AgentRunEvaluationCorpusSupport.Corpus corpus =
                AgentRunEvaluationCorpusSupport.readCorpus(getClass(), objectMapper);
        AgentRunEvaluationCorpusSupport.CorpusCase durableCase = corpus.cases().stream()
                .filter(evidenceCase -> "durable-pause-resume-after-restart".equals(evidenceCase.id()))
                .findFirst()
                .orElseThrow();
        String sessionId = AgentRunEvaluationCorpusSupport.durablePauseSessionId(objectMapper);
        stubResume(durableCase.expectedAnswer());
        doAnswer(invocation -> {
                    toolExecutions.incrementAndGet();
                    return invocation.callRealMethod();
                })
                .when(toolRegistry)
                .invokeTool(anyString(), anyString(), anyString());

        long started = System.nanoTime();
        Response decision = given().header(API_KEY_HEADER, internalApiKey)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/sessions/{id}/approve?agentRole=coordinator", sessionId);
        long resumeLatencyMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        int decisionStatus = decision.statusCode();
        String decisionErrorCode = safeErrorCode(decision);

        Response status = given().header(API_KEY_HEADER, internalApiKey)
                .get("/api/v1/sessions/{id}/status/{role}", sessionId, "coordinator");
        JsonNode steps = status.statusCode() == 200
                ? objectMapper.readTree(status.asString()).path("steps")
                : objectMapper.createArrayNode();
        boolean finished = hasFinishAnswer(steps, durableCase.expectedAnswer());
        boolean noLeak = !decision.asString().contains(FAILURE_SENTINEL);
        String checkpointStatus = checkpointCrudPort
                .findBySessionAndRole(sessionId, "coordinator")
                .map(checkpoint -> checkpoint.status())
                .orElse("MISSING");
        var persistedCheckpoint = agentStore.load(sessionId, "coordinator").orElseThrow();
        List<ReasoningStep> persistedSteps = agentStore.deserializeSteps(persistedCheckpoint);
        String lastPersistedAction = persistedSteps.isEmpty()
                ? "unavailable"
                : persistedSteps.getLast().action();
        boolean passed = decisionStatus == 200
                && durableCase.expectedAnswer().equals(decision.jsonPath().getString("answer"))
                && toolExecutions.get() == 1
                && sandboxExecutions.get() == 1
                && finished
                && "COMPLETED".equals(checkpointStatus)
                && noLeak;
        boolean expectedFailureObserved = "failed".equals(durableCase.expectedOutcome())
                && decisionStatus == 404
                && durableCase.expectedFailureCode().equals(decisionErrorCode)
                && "DENIED".equals(checkpointStatus)
                && toolExecutions.get() == 0
                && sandboxExecutions.get() == 0
                && modelCalls.get() == 0
                && noLeak;
        boolean outcomeMatches = passed || expectedFailureObserved;
        String outcome = passed ? "passed" : "failed";
        String failureKind = passed ? "none" : expectedFailureObserved ? decisionErrorCode : "AssertionFailedError";
        AgentRunEvaluationCorpusSupport.recordDurableResume(
                objectMapper,
                outcome,
                failureKind,
                expectedFailureObserved
                        ? "stale-approval-denied-without-tool-execution-after-restart"
                        : "resumed-tool-executed-after-application-restart",
                expectedFailureObserved
                        ? "stale-approval-denied-after-application-restart"
                        : "approved-after-application-restart",
                checkpointStatus,
                decisionStatus,
                decisionErrorCode,
                resumeLatencyMs,
                modelCalls.get(),
                toolExecutions.get(),
                persistedSteps.size(),
                lastPersistedAction);

        assertTrue(outcomeMatches, "Restart outcome differed from the corpus contract");
        assertEquals(durableCase.expectedOutcome(), outcome, "Restart outcome differed from corpus expectation");
        assertEquals(
                durableCase.expectedFailureCode(),
                failureKind,
                "Restart failure code differed from corpus expectation");
        if (expectedFailureObserved) {
            assertEquals(0, toolExecutions.get(), "A missing session unexpectedly executed its pending tool");
            assertEquals(0, sandboxExecutions.get(), "A missing session unexpectedly reached the sandbox");
        } else {
            assertEquals(200, decisionStatus, "Approval did not resume the persisted session");
            assertEquals(durableCase.expectedAnswer(), decision.jsonPath().getString("answer"));
            assertEquals(1, toolExecutions.get(), "Approved pending tool did not execute exactly once after restart");
            assertEquals(1, sandboxExecutions.get(), "Approved pending tool did not reach the sandbox exactly once");
            assertTrue(finished, "Resumed status did not contain the expected FINISH step");
            assertEquals("COMPLETED", checkpointStatus);
        }
        assertTrue(noLeak, "Synthetic sandbox output leaked into the REST response");
        assertFalse(Files.readString(Path.of("target", "agent-run-evaluation", "corpus-v2-report.json"))
                .contains(FAILURE_SENTINEL));
    }

    private void stubResume(String expectedAnswer) {
        when(reasoningEngine.nextStep(any(AgentExecutionContext.class), anyString(), anyString(), anyList()))
                .thenAnswer(_ -> {
                    modelCalls.incrementAndGet();
                    return ReasoningStep.pending(
                            "Complete the synthetic evaluation case.",
                            ReasoningStep.FINISH,
                            Map.of("answer", expectedAnswer),
                            1,
                            2);
                });
        doAnswer(invocation -> {
                    sandboxExecutions.incrementAndGet();
                    return SandboxResult.success("Synthetic repository metadata.");
                })
                .when(sandboxExecutionPort)
                .executeSandboxed(anyString(), any(Path.class), anyList(), anyMap());
    }

    private boolean hasFinishAnswer(JsonNode steps, String expectedAnswer) {
        for (JsonNode step : steps) {
            if (ReasoningStep.FINISH.equals(step.path("action").asText())
                    && expectedAnswer.equals(
                            step.path("actionInput").path("answer").asText())) {
                return true;
            }
        }
        return false;
    }

    private String safeErrorCode(Response response) {
        if (response.statusCode() == 200) {
            return "none";
        }
        try {
            JsonNode body = objectMapper.readTree(response.asString());
            for (String field : List.of("code", "errorCode", "detail")) {
                String code = body.path(field).asText();
                if (code.matches("[A-Z0-9_]{1,64}")) {
                    return code;
                }
            }
        } catch (Exception _) {
            return "unknown";
        }
        if (response.statusCode() == 404) {
            // AgentResource maps the session validation failure to HTTP 404 without a code field.
            return "SESSION_NOT_FOUND";
        }
        return "unknown";
    }

    public static class RestartedApplicationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.agent.evaluation.restart-phase", "true",
                    "quarkus.datasource.jdbc.url", AgentRunEvaluationCorpusSupport.testDatabaseUrl(),
                    "quarkus.flyway.clean-at-start", "false",
                    "quarkus.scheduler.enabled", "false");
        }
    }
}
