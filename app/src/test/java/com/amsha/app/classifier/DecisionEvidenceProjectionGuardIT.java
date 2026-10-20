package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.util.HttpConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Verifies that a missing operation projection refuses evidence before provider egress. */
@QuarkusTest
@QuarkusTestResource(DecisionProviderMissingOperationFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationEnabledWithoutRequiredOperation.class)
class DecisionEvidenceProjectionGuardIT {
    private static final String INTERNAL = "key-internal-changeme";

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void resetFixture() {
        apiKeyRegistry.register("internal", INTERNAL);
        DecisionProviderFixture.reset();
    }

    @Test
    void refusesBeforeEgressWhenProjectionOmitsRequiredOperation() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(409)
                .body("error", equalTo("CORPUS_NOT_MEASURABLE"));

        assertEquals(0, DecisionProviderFixture.requestCount(), "a refused evidence run must not egress");
    }
}
