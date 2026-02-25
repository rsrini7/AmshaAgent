package com.arcclaw.app.security;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiKeyAuthFilterTest {

    @InjectMock
    ApiKeyRegistry apiKeyRegistryMock;

    @BeforeEach
    void setup() {
        when(apiKeyRegistryMock.isValidKey("valid-key")).thenReturn(true);
        when(apiKeyRegistryMock.isRateLimited("valid-key")).thenReturn(false);

        when(apiKeyRegistryMock.isValidKey("rate-limited-key")).thenReturn(true);
        when(apiKeyRegistryMock.isRateLimited("rate-limited-key")).thenReturn(true);

        when(apiKeyRegistryMock.isValidKey("invalid-key")).thenReturn(false);
    }

    @Test
    void testMissingApiKeyReturns401() {
        // Any application endpoint, like /api/v1/sessions (even if it 404s later, auth filter runs first)
        given()
          .when().get("/api/v1/sessions/fake-id/status/COORDINATOR")
          .then()
             .statusCode(401)
             .body("error", equalTo("UNAUTHORIZED"));
    }

    @Test
    void testInvalidApiKeyReturns401() {
        given()
          .header("X-ArcClaw-API-Key", "invalid-key")
          .when().get("/api/v1/sessions/fake-id/status/COORDINATOR")
          .then()
             .statusCode(401)
             .body("error", equalTo("UNAUTHORIZED"));
    }

    @Test
    void testRateLimitedKeyReturns429() {
        given()
          .header("X-ArcClaw-API-Key", "rate-limited-key")
          .when().get("/api/v1/sessions/fake-id/status/COORDINATOR")
          .then()
             .statusCode(429)
             .header("Retry-After", "60")
             .body("error", equalTo("RATE_LIMITED"));
    }

    @Test
    void testValidKeyBypassesAuthFilter() {
        given()
          .header("X-ArcClaw-API-Key", "valid-key")
          .when().get("/api/v1/sessions/fake-id/status/COORDINATOR")
          .then()
             // It shouldn't be 401 or 429
             .statusCode(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.not(401), org.hamcrest.Matchers.not(429)));
    }

    @Test
    void testHealthCheckBypassesAuth() {
        given()
          .when().get("/q/health")
          .then()
             .statusCode(org.hamcrest.Matchers.not(401));
    }
}
