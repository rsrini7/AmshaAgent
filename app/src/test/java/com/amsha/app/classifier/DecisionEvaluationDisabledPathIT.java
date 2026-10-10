package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

/** Verifies the disabled evaluation path refuses work before contacting its provider. */
@QuarkusTest
@QuarkusTestResource(DecisionProviderFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationDisabled.class)
class DecisionEvaluationDisabledPathIT {

    private static final String INTERNAL = "key-internal-changeme";

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void resetFixture() {
        apiKeyRegistry.register("internal", INTERNAL);
        DecisionProviderFixture.reset();
    }

    @Test
    void evaluationIsRefusedWithoutCallingProviderWhenSwitchIsOff() {
        Map<String, Object> body = Map.of(
                "decisionId", "amsha.routing.intent.v1",
                "stateJson", "{\"user_message\":\"please fix bug\"}",
                "choiceOptions", Map.of("intent_role_choice", List.of("coder", "researcher")));

        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .contentType("application/json")
                .body(body)
                .post("/api/v1/admin/decision/evaluate")
                .then()
                .statusCode(409)
                .body("error", equalTo("DISABLED"));

        assertEquals(0, DecisionProviderFixture.requestCount(), "disabled evaluation must not egress");
    }
}
