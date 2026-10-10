package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.core.util.HttpConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end proof that the evaluation path uses its provider fixture while the plane stays Deferred.
 *
 * <p>The assertion that matters is the pair: the evaluation returns an opinion, and
 * {@code Instance<DecisionEngine>} is still unsatisfiable. If the evaluation path had registered an
 * engine bean to get here, the second assertion would fail — which is exactly the ADR change this
 * workstream must not make.
 */
@QuarkusTest
@QuarkusTestResource(DecisionProviderFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationEnabled.class)
class DecisionEvaluationPathIT {

    private static final String INTERNAL = "key-internal-changeme";

    @Inject
    Instance<DecisionEngine> decisionEngines;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void resetFixture() {
        apiKeyRegistry.register("internal", INTERNAL);
        DecisionProviderFixture.reset();
    }

    @Test
    void evaluationSucceedsWhileNoDecisionEngineBeanIsResolvable() {
        assertFalse(decisionEngines.isResolvable(), "the evaluation path must not register a DecisionEngine bean");

        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body(evaluationBody())
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(200)
                .body("opinion", equalTo(true))
                .body("providerId", equalTo("laya"))
                .body("lane", equalTo("SELF_HOSTED"))
                .body("ruleId", equalTo("intent-rule-01"))
                .body("answers.intent_role_choice", equalTo("coder (0.91)"));
    }

    @Test
    void thePlaneStaysStructurallyInert() {
        assertFalse(
                decisionEngines.isResolvable(), "no DecisionEngine bean may be registered while the plane is Deferred");
    }

    @Test
    void unregisteredDecisionIdIsRejectedBeforeProviderCall() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body(
                        "{\"decisionId\":\"amsha.routing.unknown.v9\",\"stateJson\":\"{\\\"user_message\\\":\\\"hi\\\"}\"}")
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(400)
                .body("error", equalTo("UNREGISTERED_DECISION"));

        assertEquals(0, DecisionProviderFixture.requestCount(), "unknown decision ids must not egress");
    }

    @Test
    void missingDecisionIdIsRejectedWithAClientError() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body("{\"stateJson\":\"{\\\"user_message\\\":\\\"hi\\\"}\"}")
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(400);
    }

    @Test
    void nullDecisionIdIsRejectedWithAClientError() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body("{\"decisionId\":null,\"stateJson\":\"{\\\"user_message\\\":\\\"hi\\\"}\"}")
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(400);
    }

    @Test
    void unknownChoiceHandleIsRejectedBeforeProviderCall() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body("{\"decisionId\":\"amsha.routing.intent.v1\","
                        + "\"stateJson\":\"{\\\"user_message\\\":\\\"hi\\\"}\","
                        + "\"choiceOptions\":{\"unknown_handle\":[\"coder\",\"researcher\"]}}")
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(400);
        assertEquals(0, DecisionProviderFixture.requestCount(), "unknown option handles must not egress");
    }

    private static Map<String, Object> evaluationBody() {
        return Map.of(
                "decisionId",
                "amsha.routing.intent.v1",
                "stateJson",
                "{\"user_message\":\"please fix the bug\"}",
                "choiceOptions",
                Map.of("intent_role_choice", List.of("coder", "researcher")));
    }
}
