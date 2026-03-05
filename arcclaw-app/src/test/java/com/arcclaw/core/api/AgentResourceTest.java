package com.arcclaw.core.api;

import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.agent.ReasoningStep;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
class AgentResourceTest {

        @InjectMock
        ReasoningEngine reasoningEngine;

        @InjectMock
        com.arcclaw.app.security.ApiKeyRegistry apiKeyRegistry;

        @BeforeEach
        void setupMock() {
                ReasoningStep finishStep = new ReasoningStep("Calculated final answer", ReasoningStep.FINISH,
                                java.util.Map.of("response", "42"), null, 0, 0);
                Mockito.when(reasoningEngine.nextStep(anyString(), anyString(), anyString(), any())).thenReturn(finishStep);

                Mockito.when(apiKeyRegistry.isValidKey(anyString())).thenReturn(true);
                Mockito.when(apiKeyRegistry.isAllowed(anyString())).thenReturn(true);
        }

        @Test
        void testFullAgentLifecycle() {
                // 1. Create Session
                String sessionId = given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .when().post("/api/v1/sessions")
                                .then()
                                .statusCode(201)
                                .body("sessionId", notNullValue())
                                .extract().path("sessionId");

                // 2. Sync Run (Should use the DefaultReasoningEngine stub returning FINISH)
                RunAgentRequest request = new RunAgentRequest("Hello, World!", "COORDINATOR", 3);

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when().post("/api/v1/sessions/{id}/run", sessionId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("answer", notNullValue());

                // 3. Check Status
                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .when().get("/api/v1/sessions/{id}/status/COORDINATOR", sessionId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("sessionId", equalTo(sessionId))
                                .body("status", equalTo("COMPLETED"))
                                .body("steps.size()", greaterThanOrEqualTo(1));

                // 4. Terminate Session
                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .when().delete("/api/v1/sessions/{id}", sessionId)
                                .then()
                                .statusCode(204);

                // 5. Verify cleanup (WorkspaceManager should have cleared the heartbeats,
                // though the DB checkpoint remains)
                // A GET should still return 200 since the state is persisted in SQLite,
                // but let's test a non-existent session for 404 compliance.
                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .when().get("/api/v1/sessions/fake-id-123/status/COORDINATOR")
                                .then()
                                .statusCode(404);
        }

        @Test
        void testValidationFailure() {
                // Missing goal and role
                RunAgentRequest invalidRequest = new RunAgentRequest(null, "", null);

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(invalidRequest)
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(400); // Bad Request from Validator
        }

        @Test
        void testAsyncRun() {
                String sessionId = given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .when().post("/api/v1/sessions")
                                .then()
                                .statusCode(201)
                                .extract().path("sessionId");

                RunAgentRequest request = new RunAgentRequest("Async task", "CODER", 5);

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when().post("/api/v1/sessions/{id}/run-async", sessionId)
                                .then()
                                .statusCode(202); // Accepted immediately
        }

        @Test
        void testApproveEndpointReturns409ForNoCheckpoint() {
                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .when().post("/api/v1/sessions/no-such-session/approve")
                                .then()
                                .statusCode(409)
                                .body("error", containsString("No pending approval"));
        }

        @Test
        void testDenyEndpointReturns409ForNoCheckpoint() {
                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .when().post("/api/v1/sessions/no-such-session/deny")
                                .then()
                                .statusCode(409)
                                .body("error", containsString("No pending approval"));
        }
}
