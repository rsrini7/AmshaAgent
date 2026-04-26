package com.arcclaw.app.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * AUTH-15: Per-key session isolation.
 *
 * Uses the two keys already declared under arcclaw.api.keys in application.yaml.
 * No new keys required.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionOwnershipIT {

    private static final String API_KEY_HEADER = "X-ArcClaw-Api-Key";

    // Expected owner key NAMES (the map keys, not the secret values)
    // ApiKeyAuthFilter puts the key-name into MDC.USERID - that is what bindOwner() stores.
    private static final String OWNER_INTERNAL = "internal";

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @ConfigProperty(name = "arcclaw.api.keys.internal")
    String keyInternal;

    @ConfigProperty(name = "arcclaw.api.keys.ci")
    String keyCi;

    private String sessionOwnedByInternal;

    @BeforeEach
    void createSession() {
        sessionOwnedByInternal = given()
                .header(API_KEY_HEADER, keyInternal)
                .contentType("application/json")
                .body("{\"autonomyLevel\":\"SUPERVISED\"}")
                .when()
                .post("/api/v1/sessions")
                .then()
                .statusCode(201)
                .extract()
                .path("sessionId");
    }

    // ── AUTH-15-POS: Owner can always reach their own session ─────────────────

    @Test
    void owningKey_canGetStatusOfOwnSession() {
        given()
                .header(API_KEY_HEADER, keyInternal)
                .when()
                .get("/api/v1/sessions/{sessionId}/status", sessionOwnedByInternal)
                .then()
                .statusCode(not(equalTo(401)));
    }

    // ── AUTH-15-NEG: Cross-key access must return 401 ─────────────────────────

    @Test
    void foreignKey_cannotRunAgainstAnotherKeysSession() {
        // AUTH-15: ci key must NOT be able to POST /run on internal's session
        given()
                .header(API_KEY_HEADER, keyCi)
                .contentType("application/json")
                .body("{\"agentRole\":\"coordinator\",\"goal\":\"steal\"}")
                .when()
                .post("/api/v1/sessions/{sessionId}/run", sessionOwnedByInternal)
                .then()
                .statusCode(401)
                .body("code", equalTo("SESSION_ACCESS_DENIED"));
    }

    @Test
    void foreignKey_cannotApproveAnotherKeysSession() {
        given()
                .header(API_KEY_HEADER, keyCi)
                .contentType("application/json")
                .body("{\"agentRole\":\"coordinator\"}")
                .when()
                .post("/api/v1/sessions/{sessionId}/approve", sessionOwnedByInternal)
                .then()
                .statusCode(401)
                .body("code", equalTo("SESSION_ACCESS_DENIED"));
    }

    @Test
    void foreignKey_cannotTerminateAnotherKeysSession() {
        given()
                .header(API_KEY_HEADER, keyCi)
                .when()
                .delete("/api/v1/sessions/{sessionId}", sessionOwnedByInternal)
                .then()
                .statusCode(401)
                .body("code", equalTo("SESSION_ACCESS_DENIED"));
    }

    // ── Ownership metadata ────────────────────────────────────────────────────

    @Test
    void heartbeatTracker_storesOwnerKeyName() {
        // bindOwner() is called with MDC.USERID which ApiKeyAuthFilter sets to
        // the key NAME ("internal"), not the secret value ("key-internal-changeme")
        heartbeatTracker.getOwner(sessionOwnedByInternal)
                .ifPresentOrElse(
                        owner -> assertEquals(OWNER_INTERNAL, owner,
                                "Owner stored in tracker must be the key name, not the secret"),
                        () -> fail("No owner bound for session: " + sessionOwnedByInternal)
                );
    }
}
