package com.amsha.app.security;

import static io.restassured.RestAssured.given;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class WebSessionAuthFilterTest {

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Test
    void testSessionAuthenticationAndCsrf() {
        String sessionToken = "sess-" + UUID.randomUUID().toString();
        String csrfToken = "csrf-" + UUID.randomUUID().toString();
        String userId = "usr_test123";

        // Compute digests
        String tokenDigest = apiKeyRegistry.computeDigest(sessionToken);
        String csrfDigest = apiKeyRegistry.computeDigest(csrfToken);

        // Save session in DB
        Instant now = Instant.now();
        WebSessionDto session = new WebSessionDto(
                "test-session-id",
                userId,
                tokenDigest,
                csrfDigest,
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now);
        webSessionStore.save(session);

        // 1. GET request (no CSRF required) with Session ID cookie
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .when()
                .get("/api/v1/test/ping")
                .then()
                .statusCode(200);

        // 2. POST request (mutating) without CSRF token -> 403 Forbidden
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/v1/test/ping")
                .then()
                .statusCode(403);

        // 3. POST request (mutating) with invalid CSRF token -> 403 Forbidden
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", "invalid-csrf")
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/v1/test/ping")
                .then()
                .statusCode(403);

        // 4. POST request (mutating) with valid CSRF token -> 201 Created
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/v1/test/ping")
                .then()
                .statusCode(201);
    }
}
