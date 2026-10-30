package com.amsha.app.evaluation;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
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

/** Exercises real REST, agent-loop, guard, delegation, and checkpoint boundaries hermetically. */
@QuarkusTest
@TestProfile(AgentRunEvaluationCorpusIT.CorpusApplicationProfile.class)
class AgentRunEvaluationCorpusIT {
    private static final String API_KEY_HEADER = "X-Amsha-API-Key";
    static final String FAILURE_SENTINEL = AgentRunEvaluationScriptedModel.FAILURE_SENTINEL;

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
    ObjectMapper objectMapper;

    @ConfigProperty(name = "amsha.api.keys.internal")
    String internalApiKey;

    private final AgentRunEvaluationScriptedModel scriptedModel = new AgentRunEvaluationScriptedModel();

    @Test
    void recordsVersionedWholeRunEvidenceWithoutProviderEgress() throws IOException {
        apiKeyRegistry.register("internal", internalApiKey);
        AgentRunEvaluationCorpusSupport.Corpus corpus =
                AgentRunEvaluationCorpusSupport.readCorpus(getClass(), objectMapper);
        List<Map<String, Object>> caseResults = new ArrayList<>();
        for (AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase : corpus.cases()) {
            caseResults.add(runCase(evidenceCase));
        }
        List<Map<String, Object>> validationResults =
                AgentRunEvaluationCorpusSupport.runCorpusValidationProbes(objectMapper);
        AgentRunEvaluationCorpusSupport.writeReport(objectMapper, corpus.version(), caseResults, validationResults);
        assertFalse(
                Files.readString(Path.of("target", "agent-run-evaluation", "corpus-v2-report.json"))
                        .contains(FAILURE_SENTINEL),
                "Synthetic tool output leaked into the bounded report");

        long outcomeMismatches = caseResults.stream()
                .filter(result -> !((AgentRunEvaluationCorpusSupport.DURABLE_PAUSE_CASE_ID.equals(result.get("caseId"))
                                || AgentRunEvaluationCorpusSupport.ELIGIBLE_RETRY_CASE_ID.equals(result.get("caseId")))
                        && "skipped".equals(result.get("outcome"))))
                .filter(result -> !Objects.equals(result.get("expectedOutcome"), result.get("outcome"))
                        || !Objects.equals(result.get("expectedFailureCode"), result.get("failureKind")))
                .count();
        long validationFailures = validationResults.stream()
                .filter(result -> "failed".equals(result.get("outcome")))
                .count();
        assertFalse(caseResults.isEmpty(), "The versioned corpus must contain cases");
        String outcomeMismatchSummary = caseResults.stream()
                .filter(result -> !((AgentRunEvaluationCorpusSupport.DURABLE_PAUSE_CASE_ID.equals(result.get("caseId"))
                                || AgentRunEvaluationCorpusSupport.ELIGIBLE_RETRY_CASE_ID.equals(result.get("caseId")))
                        && "skipped".equals(result.get("outcome"))))
                .filter(result -> !Objects.equals(result.get("expectedOutcome"), result.get("outcome"))
                        || !Objects.equals(result.get("expectedFailureCode"), result.get("failureKind")))
                .map(result -> result.get("caseId") + ":" + result.get("outcome") + "/" + result.get("failureKind"))
                .toList()
                .toString();
        assertEquals(
                0L,
                outcomeMismatches,
                "Observed whole-run outcomes differed from the corpus contract: " + outcomeMismatchSummary);
        assertEquals(0L, validationFailures, "Corpus validation accepted malformed input");
    }

    private Map<String, Object> runCase(AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase) {
        AgentRunEvaluationCorpusSupport.CaseEvidence evidence = new AgentRunEvaluationCorpusSupport.CaseEvidence(
                evidenceCase.id(), evidenceCase.expectedOutcome(), evidenceCase.expectedFailureCode());
        scriptedModel.resetCounts();
        long started = System.nanoTime();
        try {
            if (!"valid".equals(evidenceCase.inputRef())) {
                runMalformedRequestCase(evidenceCase, evidence);
            } else {
                switch (evidenceCase.scriptRef()) {
                    case "finish" -> runCompletionCase(evidenceCase, evidence);
                    case "unknown-tool" -> runUnknownToolCase(evidenceCase, evidence);
                    case "delegate" -> runDelegationCase(evidenceCase, evidence);
                    case "deny-approval" -> runApprovalCase(evidenceCase, evidence, false);
                    case "tool-failure" -> runApprovalCase(evidenceCase, evidence, true);
                    case "durable-pause" -> runDurablePauseCase(evidenceCase, evidence);
                    case "eligible-retry" -> runEligibleRetryCase(evidenceCase, evidence);
                    default -> throw new IllegalStateException("Validated script reference was not handled");
                }
            }
            evidence.completeIfPending();
        } catch (Exception | AssertionError failure) {
            evidence.failed(safeFailureKind(failure));
        }
        evidence.measuredLatency(Math.max(1L, (System.nanoTime() - started) / 1_000_000L));
        return evidence.toMap(scriptedModel.modelCalls(), scriptedModel.toolExecutions());
    }

    private void runCompletionCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        String sessionId = startScriptedRun(evidenceCase, "finish");
        evidence.sessionId(sessionId);
        Response response = runSession(sessionId, evidenceCase.goal());
        evidence.runStatus(response.statusCode(), safeRunErrorCode(response), safeResponseKind(response));
        recordStepSummary(sessionId, "coordinator", evidence);
        assertCompletedAnswer(response, evidenceCase.expectedAnswer());
        evidence.safetyViolations(0);
        evidence.toolBehavior("none");
    }

    private void runUnknownToolCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        String sessionId = startScriptedRun(evidenceCase, "unknown-tool");
        evidence.sessionId(sessionId);
        Response response = runSession(sessionId, evidenceCase.goal());
        evidence.runStatus(response.statusCode(), safeRunErrorCode(response), safeResponseKind(response));
        recordStepSummary(sessionId, "coordinator", evidence);
        assertCompletedAnswer(response, evidenceCase.expectedAnswer());
        boolean rejected = containsUnknownToolRejection(sessionId);
        evidence.safetyViolations(rejected ? 0 : 1);
        assertTrue(rejected, "Unknown tool reference was not rejected by the guard");
        assertEquals(0, scriptedModel.toolExecutions(), "Unknown tool unexpectedly reached tool execution");
        evidence.toolBehavior("unregistered-tool-blocked");
    }

    private void runDelegationCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        String sessionId = startScriptedRun(evidenceCase, "delegate");
        evidence.sessionId(sessionId);
        Response response = runSession(sessionId, evidenceCase.goal());
        evidence.runStatus(response.statusCode(), safeRunErrorCode(response), safeResponseKind(response));
        recordStepSummary(sessionId, "coordinator", evidence);
        assertCompletedAnswer(response, evidenceCase.expectedAnswer());
        assertTrue(scriptedModel.researcherCalls() > 0, "The researcher agent boundary was not reached");
        assertEquals(1, scriptedModel.toolExecutions(), "The delegation tool was not executed exactly once");
        evidence.safetyViolations(0);
        evidence.toolBehavior("delegated-agent-executed");
    }

    private void runApprovalCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence,
            boolean approve)
            throws IOException {
        String script = approve ? "tool-failure" : "deny-approval";
        String sessionId = startScriptedRun(evidenceCase, script, AutonomyLevel.SUPERVISED);
        evidence.sessionId(sessionId);
        Response pauseResponse = runSession(sessionId, evidenceCase.goal());
        evidence.runStatus(
                pauseResponse.statusCode(), safeRunErrorCode(pauseResponse), safeResponseKind(pauseResponse));
        recordStepSummary(sessionId, "coordinator", evidence);
        assertEquals(202, pauseResponse.statusCode(), "Network tool must pause for human approval");
        assertPersistedApproval(sessionId);
        evidence.approvalBehavior("requested");

        Response decision = resolveApproval(sessionId, approve);
        evidence.decisionStatus(decision.statusCode(), safeRunErrorCode(decision));
        recordStepSummary(sessionId, "coordinator", evidence);
        if (approve) {
            assertEquals(500, decision.statusCode(), "The synthetic tool failure was not reported as a run failure");
            assertEquals("SYSTEM", safeRunErrorCode(decision), "Tool failure code was not represented safely");
            assertEquals(1, scriptedModel.githubExecutions(), "Approved tool call did not execute exactly once");
            assertEquals(1, scriptedModel.toolExecutions(), "The approved tool invocation was not counted");
            assertFalse(decision.asString().contains(FAILURE_SENTINEL), "Raw sandbox output leaked to REST response");
            evidence.approvalBehavior("approved");
            evidence.toolBehavior("executed-failure-reported-safely");
            evidence.safetyViolations(0);
            evidence.failed("SYSTEM");
        } else {
            assertEquals(200, decision.statusCode(), "Approval denial was not accepted");
            evidence.safetyViolations(scriptedModel.githubExecutions() == 0 ? 0 : 1);
            assertEquals(0, scriptedModel.githubExecutions(), "Denied tool call executed");
            assertEquals(0, scriptedModel.toolExecutions(), "Denied tool reached the tool registry");
            evidence.approvalBehavior("denied");
            evidence.toolBehavior("approval-denied-before-execution");
        }
    }

    private void runDurablePauseCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        String sessionId = startScriptedRun(evidenceCase, "durable-pause", AutonomyLevel.SUPERVISED);
        evidence.sessionId(sessionId);
        Response pauseResponse = runSession(sessionId, evidenceCase.goal());
        evidence.runStatus(
                pauseResponse.statusCode(), safeRunErrorCode(pauseResponse), safeResponseKind(pauseResponse));
        recordStepSummary(sessionId, "coordinator", evidence);
        assertEquals(202, pauseResponse.statusCode(), "Durable pause was not created");
        assertPersistedApproval(sessionId);
        evidence.safetyViolations(0);
        evidence.toolBehavior("approval-checkpoint-persisted");
        evidence.approvalBehavior("awaiting-application-restart-phase");
        evidence.skipped("application-restart-phase-not-yet-run");
    }

    private void runEligibleRetryCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        String sessionId = startScriptedRun(evidenceCase, "eligible-retry", AutonomyLevel.SUPERVISED);
        evidence.sessionId(sessionId);
        Response response = runSession(sessionId, evidenceCase.goal());
        evidence.runStatus(response.statusCode(), safeRunErrorCode(response), safeResponseKind(response));
        recordStepSummary(sessionId, "coordinator", evidence);
        assertEquals(202, response.statusCode(), "Eligible retry did not persist its retry wait");
        assertFalse(response.asString().contains(FAILURE_SENTINEL), "Synthetic failure output leaked into response");
        assertEquals(1, scriptedModel.toolExecutions(), "Read-only tool did not cross the tool boundary once");
        CheckpointDto checkpoint = checkpointCrudPort
                .findBySessionAndRole(sessionId, "coordinator")
                .orElseThrow();
        assertEquals("AWAITING_RETRY", checkpoint.status(), "Rate-limit checkpoint was not durable");

        evidence.safetyViolations(0);
        evidence.toolBehavior("read-only-tool-executed-once-before-restart");
        evidence.approvalBehavior("not-required-for-read-only-tool;automatic-retry-pending");
        evidence.skipped("eligible-rate-limit-retry-awaiting-restart-phase");
    }

    private void runMalformedRequestCase(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase,
            AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        prepareModel("finish", evidenceCase.expectedAnswer());
        String sessionId = createSession();
        evidence.sessionId(sessionId);
        long started = System.nanoTime();
        Response response = "malformed-json".equals(evidenceCase.inputRef())
                ? runRawSessionRequest(sessionId, "{\"goal\":")
                : runRawSessionRequest(
                        sessionId,
                        "{\"goal\":{\"unexpected\":\"object\"},\"agentRole\":\"coordinator\",\"maxIterations\":\"four\"}");
        evidence.runStatus(response.statusCode(), safeRunErrorCode(response), safeResponseKind(response));
        recordStepSummary(sessionId, "coordinator", evidence);
        evidence.measuredLatency(Math.max(1L, (System.nanoTime() - started) / 1_000_000L));
        evidence.safetyViolations(response.statusCode() == 400 ? 0 : 1);
        assertEquals(400, response.statusCode(), "Malformed external request was not rejected");
        assertEquals(0, scriptedModel.modelCalls(), "Malformed input reached the reasoning boundary");
        evidence.toolBehavior("request-rejected-before-agent-entry");
        evidence.approvalBehavior("not-requested");
    }

    private String startScriptedRun(AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase, String script) {
        prepareModel(script, evidenceCase.expectedAnswer());
        return createSession();
    }

    private String startScriptedRun(
            AgentRunEvaluationCorpusSupport.CorpusCase evidenceCase, String script, AutonomyLevel autonomyLevel) {
        prepareModel(script, evidenceCase.expectedAnswer());
        return createSession(autonomyLevel);
    }

    private void prepareModel(String script, String expectedAnswer) {
        scriptedModel.prepare(reasoningEngine, sandboxExecutionPort, toolRegistry, script, expectedAnswer);
    }

    private Response resolveApproval(String sessionId, boolean approve) {
        String action = approve ? "approve" : "deny";
        return given().header(API_KEY_HEADER, internalApiKey)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/sessions/{id}/" + action + "?agentRole=coordinator", sessionId);
    }

    private void assertPersistedApproval(String sessionId) {
        CheckpointDto checkpoint = checkpointCrudPort
                .findBySessionAndRole(sessionId, "coordinator")
                .orElseThrow();
        assertEquals("AWAITING_APPROVAL", checkpoint.status());
        assertEquals(ToolCapability.GITHUB.toolName(), checkpoint.pendingToolName());
    }

    private boolean containsFinishAnswer(String sessionId, String role, String expectedAnswer) throws IOException {
        JsonNode steps = readStatusSteps(sessionId, role);
        for (JsonNode step : steps) {
            if (ReasoningStep.FINISH.equals(step.path("action").asText())
                    && expectedAnswer.equals(
                            step.path("actionInput").path("answer").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUnknownToolRejection(String sessionId) throws IOException {
        JsonNode steps = readStatusSteps(sessionId, "coordinator");
        for (JsonNode step : steps) {
            if (step.path("observation").asText().contains("does not exist")) {
                return true;
            }
        }
        return false;
    }

    private JsonNode readStatusSteps(String sessionId, String role) throws IOException {
        String statusJson = given().header(API_KEY_HEADER, internalApiKey)
                .get("/api/v1/sessions/{id}/status/{role}", sessionId, role)
                .then()
                .statusCode(200)
                .extract()
                .asString();
        return objectMapper.readTree(statusJson).path("steps");
    }

    private void recordStepSummary(String sessionId, String role, AgentRunEvaluationCorpusSupport.CaseEvidence evidence)
            throws IOException {
        JsonNode steps = readStatusSteps(sessionId, role);
        evidence.stepCount(steps.size());
        if (!steps.isEmpty()) {
            JsonNode last = steps.get(steps.size() - 1);
            evidence.lastStepAction(safeEnumValue(last.path("action").asText()));
            evidence.lastStepErrorCode(safeEnumValue(last.path("errorCode").asText()));
            evidence.lastStepKind(observationKind(last.path("observation").asText()));
        }
    }

    private String observationKind(String observation) {
        String normalized = observation.toLowerCase(Locale.ROOT);
        if (normalized.contains("does not exist")) {
            return "unknown-tool-rejected";
        }
        if (normalized.contains("approval")) {
            return "approval-related";
        }
        if (normalized.contains("finish") || normalized.contains("schema")) {
            return "finish-or-schema-correction";
        }
        return observation.isBlank() ? "none" : "other";
    }

    private String safeEnumValue(String value) {
        return value.matches("[A-Z0-9_]{1,64}") ? value : "unknown";
    }

    private void assertCompletedAnswer(Response response, String expectedAnswer) {
        assertEquals(200, response.statusCode(), "Agent run did not complete successfully");
        assertEquals(expectedAnswer, response.jsonPath().getString("answer"));
    }

    private String createSession() {
        return createSession(AutonomyLevel.FULL);
    }

    private String createSession(AutonomyLevel autonomyLevel) {
        Response response = given().header(API_KEY_HEADER, internalApiKey)
                .contentType(ContentType.JSON)
                .body(Map.of("autonomyLevel", autonomyLevel.name()))
                .post("/api/v1/sessions");
        assertEquals(201, response.statusCode());
        String sessionId = response.jsonPath().getString("sessionId");
        assertNotNull(sessionId);
        return sessionId;
    }

    private String safeRunErrorCode(Response response) {
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
            return "unknown";
        } catch (RuntimeException _) {
            return "unknown";
        } catch (IOException _) {
            return "unknown";
        }
    }

    private String safeResponseKind(Response response) {
        try {
            JsonNode body = objectMapper.readTree(response.asString());
            if (body.has("answer")) {
                return "answer";
            }
            if (body.has("toolName")) {
                return "approval-required";
            }
            if (body.has("code") || body.has("detail") || body.has("error")) {
                return "error";
            }
            return "other-json";
        } catch (IOException _) {
            return "non-json";
        }
    }

    private Response runSession(String sessionId, String goal) {
        return given().header(API_KEY_HEADER, internalApiKey)
                .contentType(ContentType.JSON)
                .body(Map.of("goal", goal, "agentRole", "coordinator", "maxIterations", 4))
                .post("/api/v1/sessions/{id}/run", sessionId);
    }

    private Response runRawSessionRequest(String sessionId, String body) {
        return given().header(API_KEY_HEADER, internalApiKey)
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/v1/sessions/{id}/run", sessionId);
    }

    private String safeFailureKind(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.matches("[A-Za-z][A-Za-z0-9]{0,63}") ? simpleName : "harness_error";
    }

    public static class CorpusApplicationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    AgentRunEvaluationCorpusSupport.testDatabaseUrl(),
                    "quarkus.flyway.clean-at-start",
                    "false",
                    "quarkus.scheduler.enabled",
                    "false",
                    "amsha.agent.rate-limit.max-auto-retry-seconds",
                    "0");
        }
    }
}
