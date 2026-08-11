package com.amsha.app.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

@QuarkusTest
class ApiKeyAuthFilterTest {

    @InjectMock
    ApiKeyRegistry apiKeyRegistryMock;

    @BeforeEach
    void setup() {
        when(apiKeyRegistryMock.isValidKey("valid-key")).thenReturn(true);
        when(apiKeyRegistryMock.isAllowed("valid-key")).thenReturn(true);
        when(apiKeyRegistryMock.isMutationAllowed("valid-key")).thenReturn(true);
        when(apiKeyRegistryMock.getKeyName("valid-key")).thenReturn("internal");

        when(apiKeyRegistryMock.isValidKey("rate-limited-key")).thenReturn(true);
        when(apiKeyRegistryMock.isAllowed("rate-limited-key")).thenReturn(false);

        when(apiKeyRegistryMock.isValidKey("invalid-key")).thenReturn(false);
    }

    @Test
    void testMissingApiKeyReturns401() {
        // Any application endpoint, like /api/v1/sessions (even if it 404s later, auth filter runs first)
        given().when()
                .get("/api/v1/sessions/fake-id/status/COORDINATOR")
                .then()
                .statusCode(401)
                .body("error", equalTo("UNAUTHORIZED"));
    }

    @Test
    void testInvalidApiKeyReturns401() {
        given().header("X-Amsha-API-Key", "invalid-key")
                .when()
                .get("/api/v1/sessions/fake-id/status/COORDINATOR")
                .then()
                .statusCode(401)
                .body("error", equalTo("UNAUTHORIZED"));
    }

    @Test
    void testRateLimitedKeyReturns429() {
        given().header("X-Amsha-API-Key", "rate-limited-key")
                .when()
                .get("/api/v1/sessions/fake-id/status/COORDINATOR")
                .then()
                .statusCode(429)
                .header("Retry-After", "60")
                .body("error", equalTo("RATE_LIMITED"));
    }

    @Test
    void webSocketTokenUsesNormalRequestBucket() {
        when(apiKeyRegistryMock.isAllowed("valid-key")).thenReturn(true);
        when(apiKeyRegistryMock.isMutationAllowed("valid-key")).thenReturn(false);

        given().header("X-Amsha-API-Key", "valid-key")
                .when()
                .post("/api/v1/chat/ws-token")
                .then()
                .statusCode(Matchers.not(429));
    }

    @Test
    void onboardingDraftMutationUsesMutationBucket() {
        when(apiKeyRegistryMock.isAllowed("valid-key")).thenReturn(true);
        when(apiKeyRegistryMock.isMutationAllowed("valid-key")).thenReturn(false);

        given().header("X-Amsha-API-Key", "valid-key")
                .contentType("application/json")
                .body("{}")
                .when()
                .put("/api/v1/onboarding/drafts/draft-test/steps/review")
                .then()
                .statusCode(429);
    }

    @Test
    void localBootstrapRecoveryIsNotBlockedByApiRateLimit() {
        given().when()
                .post("/api/v1/onboarding/bootstrap/local-recovery")
                .then()
                .statusCode(Matchers.not(429));
    }

    @Test
    void testValidKeyBypassesAuthFilter() {
        given().header("X-Amsha-API-Key", "valid-key")
                .when()
                .get("/api/v1/sessions/fake-id/status/COORDINATOR")
                .then()
                // It shouldn't be 401 or 429
                .statusCode(Matchers.allOf(Matchers.not(401), Matchers.not(429)));
    }

    @Test
    void testInfoEndpointSkipsAuth() {
        // /api/v1/info should skip the filter even without a key
        given().when().get("/api/v1/info").then().statusCode(200);
    }

    @Test
    void testOnboardingStatusSkipsAuthFilter() {
        Response response = given().when().get("/api/v1/onboarding/status");
        Assertions.assertTrue(
                response.statusCode() == 200
                        || (response.statusCode() == 401
                                && "Unauthorized".equals(response.jsonPath().getString("error"))),
                "Onboarding status must reach its resource instead of being rejected by the API-key filter");
    }

    @Test
    void testOnboardingDraftAuthenticatesPresentedKey() {
        given().header("X-Amsha-API-Key", "valid-key")
                .when()
                .post("/api/v1/onboarding/drafts")
                .then()
                .statusCode(Matchers.not(401));
    }

    @Test
    void testFilterHasNoPathMatchingLogic() throws Exception {
        Path sourceFile = Path.of("src/main/java/com/amsha/app/security/ApiKeyAuthFilter.java");
        if (Files.exists(sourceFile)) {
            String content = Files.readString(sourceFile);
            Assertions.assertFalse(
                    content.contains(".startsWith(\"/q/"), "Filter should not have Quarkus path matching logic");
            Assertions.assertFalse(
                    content.contains(".contains(\"/q/"), "Filter should not have Quarkus path matching logic");
            Assertions.assertTrue(content.contains(".startsWith(\"/info\")"), "Filter should allow /info");
        }
    }
}
