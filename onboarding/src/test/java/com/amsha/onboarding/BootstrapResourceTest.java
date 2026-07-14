package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.security.BootstrapCredentialIssuer;
import com.amsha.core.security.IssuedCredential;
import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;
import com.amsha.persistence.api.BootstrapStateStorePort;

import org.junit.jupiter.api.Test;

import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class BootstrapResourceTest {

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Test
    void testBootstrapClaimConfirmAndRecoveryFlow() {
        // Status should be UNCLAIMED initially
        given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("UNCLAIMED"));

        // Post claim: rawKey is not in JSON body anymore, but in cookie
        String firstOwnerId = given().contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/claim")
                .then()
                .statusCode(200)
                .body("rawKey", nullValue())
                .body("ownerId", startsWith("usr_"))
                .body("workspaceId", startsWith("ws_"))
                .cookie("AMSHA_API_KEY", is("sk-testrawkey123"))
                .extract()
                .path("ownerId");

        int revision = given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("CREDENTIAL_ISSUED"))
                .extract()
                .path("revision");

        // Concurrent claim should trigger recovery if state is CREDENTIAL_ISSUED
        // In recovery, we replace the digest and issue a new raw key.
        given().contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/claim")
                .then()
                .statusCode(200)
                .body("rawKey", nullValue())
                .body("ownerId", is(firstOwnerId))
                .body("workspaceId", startsWith("ws_"))
                .cookie("AMSHA_API_KEY", is("sk-testrawkey123"));

        // Get updated revision
        revision = given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("CREDENTIAL_ISSUED"))
                .extract()
                .path("revision");

        // Confirm claim
        given().contentType(ContentType.JSON)
                .body(new BootstrapResource.ConfirmRequest(revision))
                .when()
                .post("/onboarding/bootstrap/confirm")
                .then()
                .statusCode(200)
                .body("success", is(true));

        // Status should now be CLAIMED
        given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("CLAIMED"));

        // Attempting to claim after CLAIMED should conflict
        given().contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/claim")
                .then()
                .statusCode(409)
                .body("code", is("ALREADY_CLAIMED"));
    }

    @ApplicationScoped
    @Mock
    public static class MockBootstrapCredentialIssuer implements BootstrapCredentialIssuer {
        @Override
        public IssuedCredential issueCredential(String ownerId) {
            return new IssuedCredential("sk-testrawkey123", "testdigest123");
        }

        @Override
        public void rollbackCredential(String ownerId, String keyDigest, String rawKey) {}
    }
}
