package com.amsha.app.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.app.classifier.DecisionEvaluationProfiles;
import com.amsha.app.classifier.DecisionProviderFixture;
import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.util.HttpConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Authorization and refusal contract for the decision evaluation surface.
 *
 * <p>Runs with the evaluation switch off — the shipped posture — so the strongest assertion here is
 * that a refused evaluation makes <em>no</em> provider call, observed on the fixture rather than
 * inferred from the status code.
 */
@QuarkusTest
@QuarkusTestResource(DecisionProviderFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationDisabled.class)
class DecisionAdminResourceTest {

    private static final String INTERNAL = "key-internal-changeme";
    private static final String CI = "key-ci-changeme";
    private static final String PROVIDER = "/api/v1/admin/decision/provider";
    private static final String EVALUATE = "/api/v1/admin/decision/evaluate";
    private static final String EVIDENCE = "/api/v1/admin/decision/evidence/run";

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void resetFixture() {
        // Register the internal and ci keys explicitly, mirroring SkillPersonaRoutingTest: the
        // "internal" role is what the resource's isInternal() guard requires, and the "ci" key must
        // resolve to a non-internal role so the 403 assertions exercise the guard rather than auth.
        apiKeyRegistry.register("internal", INTERNAL);
        apiKeyRegistry.register("ci", CI);
        DecisionProviderFixture.reset();
    }

    @Test
    void providerStatusRejectsUnauthenticatedCallers() {
        given().get(PROVIDER).then().statusCode(401);
    }

    @Test
    void providerStatusRejectsNonInternalCallers() {
        given().header(HttpConstants.X_AMSHA_API_KEY, CI).get(PROVIDER).then().statusCode(403);
    }

    @Test
    void providerStatusReportsTheResolvedSelectionForInternalCallers() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .get(PROVIDER)
                .then()
                .statusCode(200)
                .body("providerId", equalTo("laya"))
                .body("lane", equalTo("SELF_HOSTED"))
                .body("model", equalTo("laya-multilingual"))
                .body("governanceDeclared", equalTo(true))
                .body("credentialDeclared", equalTo(false))
                .body("evaluationEnabled", equalTo(false));
    }

    @Test
    void evaluationRejectsUnauthenticatedAndNonInternalCallers() {
        given().contentType("application/json")
                .body(evaluationBody("{\"user_message\":\"please fix the bug\"}"))
                .post(EVALUATE)
                .then()
                .statusCode(401);

        given().header(HttpConstants.X_AMSHA_API_KEY, CI)
                .contentType("application/json")
                .body(evaluationBody("{\"user_message\":\"please fix the bug\"}"))
                .post(EVALUATE)
                .then()
                .statusCode(403);
    }

    @Test
    void evaluationIsRefusedWhileDisabledAndMakesNoProviderCall() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body(evaluationBody("{\"user_message\":\"please fix the bug\"}"))
                .post(EVALUATE)
                .then()
                .statusCode(409)
                .body("error", equalTo("DISABLED"));

        assertEquals(0, DecisionProviderFixture.requestCount(), "a disabled evaluation must not egress");
    }

    @Test
    void evidenceRunRequiresTheEvaluationSwitchAndInternalAuthorization() {
        given().post(EVIDENCE).then().statusCode(401);
        given().header(HttpConstants.X_AMSHA_API_KEY, CI).post(EVIDENCE).then().statusCode(403);
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post(EVIDENCE)
                .then()
                .statusCode(409)
                .body("error", equalTo("DISABLED"));
        assertEquals(0, DecisionProviderFixture.requestCount(), "a disabled evidence run must not egress");
    }

    @Test
    void malformedOrAmbiguousEvaluationJsonIsRejectedBeforeEgress() {
        assertBadEvaluationBody("{");
        assertBadEvaluationBody("{\"decisionId\":7,\"stateJson\":\"{}\"}");
        assertBadEvaluationBody(
                "{\"decisionId\":\"amsha.routing.intent.v1\",\"stateJson\":\"{}\",\"legacyPolicy\":{}}");
        assertBadEvaluationBody(
                "{\"decisionId\":\"amsha.routing.intent.v1\",\"decisionId\":\"amsha.routing.intent.v1\","
                        + "\"stateJson\":\"{}\"}");
        assertBadEvaluationBody(
                "{\"decisionId\":\"amsha.routing.intent.v1\",\"stateJson\":\"{}\",\"choiceOptions\":[]}");
        assertBadEvaluationBody("{\"decisionId\":\"amsha.routing.intent.v1\",\"stateJson\":\"{}\","
                + "\"choiceOptions\":{\"intent_role_choice\":[1,2]}}");
        assertBadEvaluationBody(
                "{\"decisionId\":\"amsha.routing.intent.v1\",\"stateJson\":\"{}\",\"timeoutMs\":\"500\"}");
        assertBadEvaluationBody("{\"decisionId\":\"amsha.routing.intent.v1\",\"stateJson\":\"{}\","
                + "\"choiceOptions\":{\"intent_role_choice\":[\"coder\",\"researcher\"],"
                + "\"intent_role_choice\":[\"coder\",\"architect\"]}}");
        assertBadEvaluationBody("{\"decisionId\":\"amsha.routing.intent.v1\"}");
        assertEquals(0, DecisionProviderFixture.requestCount(), "malformed requests must not egress");
    }

    @Test
    void duplicateChoiceOptionsAreRejectedBeforeEgress() {
        assertBadEvaluationBody("{\"decisionId\":\"amsha.routing.intent.v1\",\"stateJson\":\"{}\","
                + "\"choiceOptions\":{\"intent_role_choice\":[\"coder\",\"coder\"]}}");
        assertEquals(0, DecisionProviderFixture.requestCount(), "duplicate options must not egress");
    }

    private static void assertBadEvaluationBody(String json) {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body(json)
                .post(EVALUATE)
                .then()
                .statusCode(400);
    }

    private static Map<String, Object> evaluationBody(String stateJson) {
        return Map.of(
                "decisionId",
                "amsha.routing.intent.v1",
                "stateJson",
                stateJson,
                "choiceOptions",
                Map.of("intent_role_choice", List.of("coder", "researcher")));
    }
}
