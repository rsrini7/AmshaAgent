package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class OnboardingResourceTest {

    private static final String INTERNAL_KEY = "key-internal-changeme";

    @Test
    void testUnauthenticatedDenials() {
        given().when().get("/onboarding/drafts").then().statusCode(401);
        given().when().post("/onboarding/drafts").then().statusCode(401);
        given().when().get("/onboarding/drafts/invalid-draft").then().statusCode(401);
        given().when().get("/onboarding/health/routing").then().statusCode(401);
        given().when().get("/onboarding/export").then().statusCode(401);
        given().when()
                .contentType(ContentType.JSON)
                .body(Map.of())
                .post("/onboarding/import")
                .then()
                .statusCode(401);
        given().when().post("/onboarding/drafts/discard").then().statusCode(401);
    }

    @Test
    void testUnauthenticatedStatusBeforeAndAfterBootstrapClaim() {
        OnboardingResource resource = new OnboardingResource();
        resource.securityContext = mock(SecurityContext.class);
        resource.bootstrapStateStore = mock(BootstrapStateStorePort.class);
        resource.configurationProvenance = mock(LlmConfigurationProvenance.class);
        resource.request = mock(HttpServerRequest.class);
        resource.skipOnboarding = false;
        when(resource.request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(8080, "127.0.0.1"));
        when(resource.configurationProvenance.resolve())
                .thenReturn(new LlmConfigurationProvenance.Resolution(
                        "",
                        "",
                        "",
                        LlmConfigurationProvenance.SOURCE_DEFAULT,
                        LlmConfigurationProvenance.CREDENTIAL_MISSING,
                        LlmConfigurationProvenance.SOURCE_NONE,
                        false,
                        false));

        when(resource.bootstrapStateStore.getState())
                .thenReturn(Optional.of(bootstrapState(BootstrapStateDto.STATUS_UNCLAIMED)));
        try (Response response = resource.status()) {
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertEquals("no-store", response.getHeaderString(HttpHeaders.CACHE_CONTROL));
            OnboardingStatusDto status = (OnboardingStatusDto) response.getEntity();
            assertEquals("welcome", status.stepName());
            assertFalse(status.credentialsOk());
            assertNull(status.provider());
            assertNull(status.effectiveSource());
            assertNull(status.credentialState());
            assertTrue(response.getCookies().isEmpty());
        }

        when(resource.request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(8080, "192.0.2.10"));
        when(resource.bootstrapStateStore.getState())
                .thenReturn(Optional.of(bootstrapState(BootstrapStateDto.STATUS_UNCLAIMED)));
        try (Response response = resource.status()) {
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        }

        when(resource.bootstrapStateStore.getState())
                .thenReturn(Optional.of(bootstrapState(BootstrapStateDto.STATUS_CLAIMED)));
        try (Response response = resource.status()) {
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
            assertEquals("no-store", response.getHeaderString(HttpHeaders.CACHE_CONTROL));
        }
    }

    private static BootstrapStateDto bootstrapState(String status) {
        return new BootstrapStateDto("bootstrap", status, null, null, null, null, null, 0, Instant.now());
    }

    @Test
    void testDraftLifecycle() {
        // Create draft with internal key (resolved as owner "internal")
        String draftId = given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .post("/onboarding/drafts")
                .then()
                .statusCode(200)
                .body("currentStepId", is("welcome"))
                .body("ownerId", is("internal"))
                .extract()
                .path("draftId");

        // List drafts
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .get("/onboarding/drafts")
                .then()
                .statusCode(200)
                .body(is(draftId));

        // Get draft by ID
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .get("/onboarding/drafts/" + draftId)
                .then()
                .statusCode(200)
                .body("draftId", is(draftId));

        // Get non-existent draft
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .get("/onboarding/drafts/non-existent-draft-id")
                .then()
                .statusCode(404);

        // Status reflects the draft
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .get("/onboarding/status")
                .then()
                .statusCode(200)
                .body("stepName", is("welcome"));

        // Routing health & Export/Import
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .get("/onboarding/health/routing")
                .then()
                .statusCode(200)
                .body("status", is("ok"));

        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .get("/onboarding/export")
                .then()
                .statusCode(200);

        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when()
                .post("/onboarding/import")
                .then()
                .statusCode(200)
                .body("imported", is(true));

        // Advance to connections step (index-based plan guard)
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .contentType(ContentType.JSON)
                .body("{\"selectedNonSecretIds\": []}")
                .when()
                .put("/onboarding/drafts/" + draftId + "/steps/connections")
                .then()
                .statusCode(200)
                .body("currentStepId", is("connections"));

        // Advance to routes step (connections is now completed)
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .contentType(ContentType.JSON)
                .body("{\"selectedNonSecretIds\": []}")
                .when()
                .put("/onboarding/drafts/" + draftId + "/steps/routes")
                .then()
                .statusCode(200)
                .body("currentStepId", is("routes"));

        // Advance to review step (routes is now completed)
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .contentType(ContentType.JSON)
                .body("{\"selectedNonSecretIds\": []}")
                .when()
                .put("/onboarding/drafts/" + draftId + "/steps/review")
                .then()
                .statusCode(200)
                .body("currentStepId", is("review"));

        // Commit the draft
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .post("/onboarding/drafts/" + draftId + "/commit")
                .then()
                .statusCode(200)
                .body("state", is("COMPLETED"));

        // Discard is idempotent after commit (no active draft)
        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .post("/onboarding/drafts/discard")
                .then()
                .statusCode(200)
                .body("discarded", is(true));
    }

    @Test
    void rejectsMalformedDraftSelectionPayload() {
        String draftId = given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .when()
                .post("/onboarding/drafts")
                .then()
                .statusCode(200)
                .extract()
                .path("draftId");

        given().header("X-Amsha-API-Key", INTERNAL_KEY)
                .contentType(ContentType.JSON)
                .body("{\"selectedNonSecretIds\":[\"not-json-secret-value\"]}")
                .when()
                .put("/onboarding/drafts/" + draftId + "/steps/connections")
                .then()
                .statusCode(400);
    }
}
