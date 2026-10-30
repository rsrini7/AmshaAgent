package com.amsha.app.ui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CockpitRunEvidenceIT {
    private static final String API_KEY_HEADER = "X-Amsha-Api-Key";

    @Inject
    ExecutionTraceStorePort traceStore;

    @ConfigProperty(name = "amsha.api.keys.internal")
    String internalKey;

    @ConfigProperty(name = "amsha.api.keys.ci")
    String otherWorkspaceKey;

    private String sessionId;
    private String runId;

    @BeforeEach
    void seedStoredRun() {
        sessionId = given().header(API_KEY_HEADER, internalKey)
                .contentType("application/json")
                .body("{\"autonomyLevel\":\"SUPERVISED\"}")
                .when()
                .post("/api/v1/sessions")
                .then()
                .statusCode(201)
                .extract()
                .path("sessionId");
        runId = sessionId + ":turn-3";
        Instant start = Instant.parse("2026-10-29T00:00:00Z");
        traceStore.saveBatch(List.of(
                event(
                        sessionId + "-evidence-start",
                        runId,
                        sessionId,
                        0,
                        "turn_start",
                        "START",
                        null,
                        null,
                        null,
                        null,
                        start),
                event(
                        sessionId + "-evidence-guard",
                        runId,
                        sessionId,
                        1,
                        "guard_check",
                        "ALLOW",
                        "HIGH",
                        null,
                        null,
                        "DO_NOT_RENDER_PAYLOAD_SENTINEL",
                        start.plusMillis(10)),
                event(
                        sessionId + "-evidence-hitl-request",
                        runId,
                        sessionId,
                        2,
                        "hitl_requested",
                        "HITL_REQUIRED",
                        "MEDIUM",
                        null,
                        null,
                        "tool parameters must not be projected",
                        start.plusMillis(20)),
                event(
                        sessionId + "-evidence-hitl-resolution",
                        runId,
                        sessionId,
                        3,
                        "hitl_resolved",
                        "DENIED",
                        "MEDIUM",
                        null,
                        null,
                        "authorization=DO_NOT_RENDER_SECRET_SENTINEL",
                        start.plusMillis(30)),
                event(
                        sessionId + "-evidence-usage",
                        runId,
                        sessionId,
                        4,
                        "llm_reasoning",
                        "REASONING",
                        null,
                        12,
                        7,
                        null,
                        start.plusMillis(40)),
                event(
                        sessionId + "-evidence-end",
                        runId,
                        sessionId,
                        5,
                        "turn_end",
                        "END",
                        null,
                        null,
                        null,
                        null,
                        start.plusMillis(250)),
                event(
                        sessionId + "-other-run-guard",
                        sessionId + ":turn-4",
                        sessionId,
                        0,
                        "guard_check",
                        "DENIED",
                        "CRITICAL",
                        null,
                        null,
                        "different run",
                        start.plusMillis(300))));
    }

    @Test
    void correlatesStoredEvidenceToTheRequestedRunAndRedactsPayloadFields() {
        given().header(API_KEY_HEADER, internalKey)
                .when()
                .get("/api/v1/cockpit/runs/{runId}/evidence", runId)
                .then()
                .statusCode(200)
                .body("runId", equalTo(runId))
                .body("outcomeStatus", equalTo("END"))
                .body("outcomeAvailability", equalTo("OBSERVED"))
                .body("latencyMs", equalTo(250))
                .body("latencyAvailability", equalTo("MEASURED"))
                .body("observedEventCount", equalTo(6))
                .body("guardDecisions", hasSize(1))
                .body("guardDecisions[0].decision", equalTo("ALLOW"))
                .body("approvalDecisions", hasSize(2))
                .body("approvalDecisions[0].decision", equalTo("HITL_REQUIRED"))
                .body("approvalDecisions[1].decision", equalTo("DENIED"))
                .body("promptTokens", equalTo(12))
                .body("completionTokens", equalTo(7))
                .body("usageAvailability", equalTo("OBSERVED"))
                .body("costStatus", equalTo("UNPRICED"))
                .body("estimatedCost", equalTo(null))
                .body(not(containsString("DO_NOT_RENDER_PAYLOAD_SENTINEL")))
                .body(not(containsString("DO_NOT_RENDER_SECRET_SENTINEL")));
    }

    @Test
    void deniesCrossWorkspaceRunEvidenceAtTheRestBoundary() {
        given().header(API_KEY_HEADER, otherWorkspaceKey)
                .when()
                .get("/api/v1/cockpit/runs/{runId}/evidence", runId)
                .then()
                .statusCode(404)
                .body(not(containsString(runId)));
    }

    @Test
    void allowsInternalOperatorToReadAnotherWorkspacesRunEvidence() {
        String otherSessionId = given().header(API_KEY_HEADER, otherWorkspaceKey)
                .contentType("application/json")
                .body("{\"autonomyLevel\":\"SUPERVISED\"}")
                .when()
                .post("/api/v1/sessions")
                .then()
                .statusCode(201)
                .extract()
                .path("sessionId");
        String otherRunId = otherSessionId + ":turn-1";
        Instant start = Instant.parse("2026-10-29T00:01:00Z");
        traceStore.saveBatch(List.of(
                event(
                        otherSessionId + "-start",
                        otherRunId,
                        otherSessionId,
                        0,
                        "turn_start",
                        "START",
                        null,
                        null,
                        null,
                        null,
                        start),
                event(
                        otherSessionId + "-end",
                        otherRunId,
                        otherSessionId,
                        1,
                        "turn_end",
                        "END",
                        null,
                        null,
                        null,
                        null,
                        start.plusMillis(30))));

        given().header(API_KEY_HEADER, internalKey)
                .when()
                .get("/api/v1/cockpit/runs/{runId}/evidence", otherRunId)
                .then()
                .statusCode(200)
                .body("runId", equalTo(otherRunId))
                .body("outcomeStatus", equalTo("END"));
    }

    @Test
    void reportsMissingRunMeasurementsAsUnavailable() {
        String missingRunId = sessionId + ":turn-99";

        given().header(API_KEY_HEADER, internalKey)
                .when()
                .get("/api/v1/cockpit/runs/{runId}/evidence", missingRunId)
                .then()
                .statusCode(200)
                .body("outcomeStatus", equalTo("UNAVAILABLE"))
                .body("latencyAvailability", equalTo("UNAVAILABLE"))
                .body("usageAvailability", equalTo("UNAVAILABLE"))
                .body("costStatus", equalTo("UNAVAILABLE"));
    }

    private static ExecutionTraceDto event(
            String eventId,
            String runId,
            String sessionId,
            long sequence,
            String stage,
            String status,
            String riskLevel,
            Integer promptTokens,
            Integer completionTokens,
            String detail,
            Instant timestamp) {
        return new ExecutionTraceDto(
                eventId,
                runId,
                sessionId,
                3,
                sequence,
                stage,
                null,
                status,
                riskLevel,
                detail,
                promptTokens,
                completionTokens,
                null,
                null,
                null,
                timestamp,
                "REACT",
                1,
                timestamp);
    }
}
