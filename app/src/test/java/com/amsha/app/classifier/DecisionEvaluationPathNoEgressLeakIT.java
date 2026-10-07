package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.util.HttpConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Proves what actually crosses the process boundary.
 *
 * <p>It asserts against the request body the loopback fixture received, not against the code's intent:
 * an allow-listed field is sent, and every excluded field, debug key, and credential is absent. A
 * projection bug that widened the allow-list would show up here as a failure rather than as a silently
 * larger payload.
 */
@QuarkusTest
@QuarkusTestResource(DecisionProviderFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationEnabled.class)
class DecisionEvaluationPathNoEgressLeakIT {

    private static final String INTERNAL = "key-internal-changeme";
    // Deliberately not key-shaped: a synthetic marker proving a credential never crosses the boundary. It must
    // never match a secret-scan pattern (no sk-... prefix), or it would trip the staged-diff review.
    private static final String SECRET = "SYNTHETIC_CREDENTIAL_MARKER_NO_LEAK_PLEASE";

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void resetFixture() {
        apiKeyRegistry.register("internal", INTERNAL);
        DecisionProviderFixture.reset();
    }

    @Test
    void excludedFieldsDebugKeysAndSecretsNeverReachTheProviderRequestBody() {
        String state = "{\"user_message\":\"please fix the bug\","
                + "\"debug\":\"internal-trace-marker\","
                + "\"api_key\":\"" + SECRET + "\","
                + "\"nested\":{\"secret\":\"hunter2-marker\"}}";

        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body(Map.of(
                        "decisionId",
                        "amsha.routing.intent.v1",
                        "stateJson",
                        state,
                        "choiceOptions",
                        Map.of("intent_role_choice", List.of("coder", "researcher"))))
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(200);

        String body = DecisionProviderFixture.lastBody();
        assertNotNull(body, "the fixture must have received a request");
        assertTrue(body.contains("user_message"), "the allow-listed field must be sent");
        assertFalse(body.contains("internal-trace-marker"), "a debug field must be dropped");
        assertFalse(body.contains(SECRET), "a credential must never cross the boundary");
        assertFalse(body.contains("api_key"), "an excluded field name must be dropped");
        assertFalse(body.contains("hunter2-marker"), "a nested excluded field must be dropped");
    }
}
