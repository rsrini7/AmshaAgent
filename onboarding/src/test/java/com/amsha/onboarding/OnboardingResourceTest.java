package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import java.util.Map;

import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class OnboardingResourceTest {

    private static final String INTERNAL_KEY = "key-internal-changeme";

    @Test
    void testUnauthenticatedDenials() {
        given().when().get("/onboarding/status").then().statusCode(401);
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
