package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.security.BootstrapCredentialIssuer;
import com.amsha.security.IssuedCredential;
import com.amsha.security.PreparedBootstrapCredential;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class BootstrapResourceTest {

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @BeforeEach
    void setUp() {
        RestAssured.config = RestAssured.config()
                .httpClient(HttpClientConfig.httpClientConfig().dontReuseHttpClientInstance());
        bootstrapStateStore.save(
                new BootstrapStateDto("bootstrap", "UNCLAIMED", null, null, null, null, null, 0, Instant.now()));
    }

    @Test
    void testBootstrapReservationClaimConfirmFlow() {
        // Status should be UNCLAIMED initially
        given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("UNCLAIMED"));

        // Claim: prepares + activates a random key; never in JSON body, only in cookie
        String firstOwnerId = given().contentType(ContentType.JSON)
                .header("Connection", "close")
                .when()
                .post("/onboarding/bootstrap/claim")
                .then()
                .statusCode(200)
                .body("ownerId", startsWith("usr_"))
                .body("workspaceId", startsWith("ws_"))
                .body("apiKey", startsWith("sk-"))
                .cookie("AMSHA_API_KEY", startsWith("sk-"))
                .extract()
                .path("ownerId");

        given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("CREDENTIAL_ISSUED"));

        // A second claim while CREDENTIAL_ISSUED is the recovery path: same owner/workspace, new key
        Response recoveryResp;
        try {
            recoveryResp = given().contentType(ContentType.JSON)
                    .header("Connection", "close")
                    .when()
                    .post("/onboarding/bootstrap/claim");
        } catch (Exception e) {
            Assertions.fail("2nd claim threw: " + e + " / " + e.getMessage());
            return;
        }
        recoveryResp
                .then()
                .statusCode(200)
                .body("ownerId", is(firstOwnerId))
                .body("workspaceId", startsWith("ws_"))
                .cookie("AMSHA_API_KEY", startsWith("sk-"));

        given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("CREDENTIAL_ISSUED"));

        // Confirm requires the authenticated issued principal (the key from the cookie)
        String apiKey = given().contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/claim")
                .then()
                .statusCode(200)
                .cookie("AMSHA_API_KEY")
                .extract()
                .cookie("AMSHA_API_KEY");

        given().header("X-Amsha-API-Key", apiKey)
                .contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/confirm")
                .then()
                .statusCode(200)
                .body("success", is(true));

        given().when()
                .get("/onboarding/bootstrap/status")
                .then()
                .statusCode(200)
                .body("status", is("CLAIMED"));

        given().when().get("/onboarding/status").then().statusCode(401);

        // Claim after CLAIMED conflicts
        given().contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/claim")
                .then()
                .statusCode(409)
                .body("code", is("ALREADY_CLAIMED"));

        given().contentType(ContentType.JSON)
                .when()
                .post("/onboarding/bootstrap/local-recovery")
                .then()
                .statusCode(200)
                .body("ownerId", not(emptyOrNullString()))
                .body("apiKey", not(emptyOrNullString()))
                .cookie("AMSHA_API_KEY", not(emptyOrNullString()));
    }

    @ApplicationScoped
    @Mock
    public static class MockBootstrapCredentialIssuer implements BootstrapCredentialIssuer {
        @Inject
        BootstrapStateStorePort bootstrapStateStore;

        @Override
        public PreparedBootstrapCredential prepare(String ownerId) {
            return new PreparedBootstrapCredential(
                    ownerId, "sk-testrawkey123", "sk-testrawkey123".getBytes(), "testdigest123");
        }

        @Override
        public IssuedCredential activate(PreparedBootstrapCredential prepared, int expectedRevision) {
            BootstrapStateDto state = bootstrapStateStore.getState().orElseThrow();
            if ("CLAIMING".equals(state.status())) {
                bootstrapStateStore.recordCredentialIssued(expectedRevision, prepared.keyDigest(), Instant.now());
            } else {
                bootstrapStateStore.replaceCredential(
                        expectedRevision, prepared.keyDigest(), Instant.now(), Instant.now());
            }
            return new IssuedCredential(prepared.rawKey(), prepared.keyDigest());
        }
    }
}
