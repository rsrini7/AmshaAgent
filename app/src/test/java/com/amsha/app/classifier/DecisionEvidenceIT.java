package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.core.util.HttpConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end proof that {@code POST /evidence/run} exercises the registered corpus through the single
 * audited egress path and returns a payload-free summary, while the plane stays Deferred (no
 * {@code DecisionEngine} bean).
 */
@QuarkusTest
@QuarkusTestResource(DecisionProviderFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationEnabled.class)
class DecisionEvidenceIT {

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
    void thePlaneStaysStructurallyInert() {
        assertFalse(
                decisionEngines.isResolvable(), "no DecisionEngine bean may be registered while the plane is Deferred");
    }

    @Test
    void runsTheRegisteredCorpusAndReturnsAPayloadFreeSummary() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .body("corpusVersion", equalTo(1))
                .body("caseCount", equalTo(30));
    }

    @Test
    void anUnregisteredCorpusVersionIsRefused() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .queryParam("version", 999)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(400);
    }
}
