package com.arcclaw.core.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.arcclaw.app.security.ApiKeyRegistry;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
class AgentResourceTest {

        @InjectMock
        ReasoningEngine reasoningEngine;

        @InjectMock
        AgentLoop agentLoop;

        @InjectMock
        StatefulAgentStore agentStore;

        @InjectMock
        ApiKeyRegistry apiKeyRegistry;

        @BeforeEach
        void setupMock() {
                ReasoningStep finishStep = new ReasoningStep("Calculated final answer", ReasoningStep.FINISH,
                                Map.of("response", "42"), null, 0, 0);
                Mockito.when(reasoningEngine.nextStep(anyString(), anyString(), anyString(), any())).thenReturn(finishStep);

                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.Finished("42", List.of(), 10, 5));

                Mockito.when(apiKeyRegistry.isValidKey(anyString())).thenReturn(true);
                Mockito.when(apiKeyRegistry.isAllowed(anyString())).thenReturn(true);

                // Mock store to return a valid (completed) checkpoint for status checks
                AgentCheckpoint checkpoint = new AgentCheckpoint();
                checkpoint.setSessionId("any-session");
                checkpoint.setAgentRole("coordinator");
                checkpoint.setStatus(CheckpointStatus.COMPLETED);
                checkpoint.setStateJson("[{\"description\":\"Calculated final answer\"}]");
                
                Mockito.when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(checkpoint));
                Mockito.when(agentStore.load(Mockito.eq("fake-id-123"), anyString())).thenReturn(Optional.empty());
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
        void testComplianceViolationReturns451() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.Failed(
                                                "Blocked by policy", AgentResult.FailureCategory.COMPLIANCE_VIOLATION,
                                                List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 3))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(451)
                                .body("code", equalTo("COMPLIANCE"));
        }

        @Test
        void testBudgetExceededReturns429() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.Failed(
                                                "Token limit", AgentResult.FailureCategory.BUDGET_EXCEEDED,
                                                List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 3))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(429)
                                .body("code", equalTo("BUDGET"));
        }

        @Test
        void testUserDeniedReturns422() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.Failed(
                                                "User said no", AgentResult.FailureCategory.USER_DENIED,
                                                List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 3))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(422)
                                .body("code", equalTo("USER_DENIED"));
        }

        @Test
        void testSchemaErrorReturns422() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.Failed(
                                                "Bad tool params", AgentResult.FailureCategory.SCHEMA_ERROR,
                                                List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 3))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(422)
                                .body("code", equalTo("SCHEMA"));
        }

        @Test
        void testSystemErrorReturns500() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.Failed(
                                                "Internal crash", AgentResult.FailureCategory.SYSTEM_ERROR,
                                                List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 3))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(500)
                                .body("code", equalTo("SYSTEM"));
        }

        @Test
        void testLoopExceededReturns408() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.LoopExceeded(5, List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 5))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(408)
                                .body("code", equalTo("LOOP_EXCEEDED"));
        }

        @Test
        void testApprovalRequiredReturns202WithShape() {
                Mockito.when(agentLoop.run(anyString(), anyString(), anyString(), any(), Mockito.anyInt()))
                                .thenReturn(new AgentResult.AwaitingApproval(
                                                "gh-tool", "{}", "sess-123", List.of(), 0, 0));

                given()
                                .header("X-ArcClaw-API-Key", "test-key")
                                .contentType(ContentType.JSON)
                                .body(new RunAgentRequest("goal", "COORDINATOR", 3))
                                .when().post("/api/v1/sessions/test-id/run")
                                .then()
                                .statusCode(202)
                                .body("toolName", equalTo("gh-tool"))
                                .body("sessionId", equalTo("sess-123"));
        }
}
