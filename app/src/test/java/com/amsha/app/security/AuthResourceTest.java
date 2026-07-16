package com.amsha.app.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;

import org.junit.jupiter.api.Test;

import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class AuthResourceTest {

    @Inject
    IdentityStorePort identityStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    IdentityInvitationStorePort invitationStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Test
    void testAuthFlows() {
        // 1. GET /providers lists mockidp
        given().when().get("/api/v1/auth/providers").then().statusCode(200).body("", hasItem("mockidp"));

        // 2. GET /login/mockidp redirects
        String location = given().when()
                .redirects()
                .follow(false)
                .get("/api/v1/auth/login/mockidp?invitationToken=test-inv-token")
                .then()
                .statusCode(303)
                .extract()
                .header("Location");
        assert location.contains("state=");

        // Extract state from redirect URL
        String state = location.substring(location.indexOf("state=") + 6);
        if (state.contains("&")) {
            state = state.substring(0, state.indexOf("&"));
        }

        // Seed invitation in DB for registration
        Instant now = Instant.now();
        String invitationToken = "test-inv-token";
        String tokenDigest = apiKeyRegistry.computeDigest(invitationToken);
        String workspaceId = "ws_invite_123";

        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, "Invite Workspace", "usr_system", now, now));
        invitationStore.save(new IdentityInvitationDto(
                "inv_test_123",
                workspaceId,
                "MEMBER",
                tokenDigest,
                null,
                now.plusSeconds(3600),
                null,
                null,
                1,
                now,
                now));

        // 3. GET /callback/mockidp accepts state, registers user from invitation, sets session + CSRF cookie
        var callbackResponse = given().when()
                .redirects()
                .follow(false)
                .queryParam("code", "mock-auth-code")
                .queryParam("state", state)
                .get("/api/v1/auth/callback/mockidp")
                .then()
                .statusCode(303)
                .cookie("AMSHA_SESSION_ID", notNullValue())
                .cookie("X-CSRF-Token", notNullValue())
                .extract();

        String sessionToken = callbackResponse.cookie("AMSHA_SESSION_ID");
        String csrfToken = callbackResponse.cookie("X-CSRF-Token");

        // 4. POST /logout revokes session
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/auth/logout")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .cookie("AMSHA_SESSION_ID", is(""))
                .cookie("X-CSRF-Token", is(""));

        // Verify session is marked revoked in DB
        String sessionDigest = apiKeyRegistry.computeDigest(sessionToken);
        Optional<WebSessionDto> sessionOpt = webSessionStore.findByTokenDigest(sessionDigest);
        assert sessionOpt.isPresent();
        assert sessionOpt.get().revokedAt() != null;
    }

    @Test
    void testSessionRotationOverlap() {
        // 1. Login flow to obtain a session
        String location = given().when()
                .redirects()
                .follow(false)
                .get("/api/v1/auth/login/mockidp?invitationToken=test-inv-token-rot")
                .then()
                .statusCode(303)
                .extract()
                .header("Location");
        String state = location.substring(location.indexOf("state=") + 6);
        if (state.contains("&")) {
            state = state.substring(0, state.indexOf("&"));
        }

        Instant now = Instant.now();
        String invitationToken = "test-inv-token-rot";
        String tokenDigest = apiKeyRegistry.computeDigest(invitationToken);
        String workspaceId = "ws_invite_rot";
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, "Rot Workspace", "usr_system", now, now));
        invitationStore.save(new IdentityInvitationDto(
                "inv_rot_123",
                workspaceId,
                "MEMBER",
                tokenDigest,
                null,
                now.plusSeconds(3600),
                null,
                null,
                1,
                now,
                now));

        var cb = given().when()
                .redirects()
                .follow(false)
                .queryParam("code", "mock-auth-code")
                .queryParam("state", state)
                .get("/api/v1/auth/callback/mockidp")
                .then()
                .statusCode(303)
                .cookie("AMSHA_SESSION_ID", notNullValue())
                .cookie("X-CSRF-Token", notNullValue())
                .extract();
        String sessionToken = cb.cookie("AMSHA_SESSION_ID");
        String csrfToken = cb.cookie("X-CSRF-Token");

        // Old token authenticates
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .get("/api/v1/identity/me")
                .then()
                .statusCode(200);

        // 2. Rotate the session
        var rot = given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/auth/session/rotate")
                .then()
                .statusCode(204)
                .cookie("AMSHA_SESSION_ID", notNullValue())
                .cookie("X-CSRF-Token", notNullValue())
                .extract();
        String newToken = rot.cookie("AMSHA_SESSION_ID");
        String newCsrf = rot.cookie("X-CSRF-Token");
        assert !newToken.equals(sessionToken) : "rotated token must differ";

        // 3. Overlap: the previous token still resolves within the grace window
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .get("/api/v1/identity/me")
                .then()
                .statusCode(200);

        // New token also authenticates
        given().cookie("AMSHA_SESSION_ID", newToken)
                .header("X-CSRF-Token", newCsrf)
                .get("/api/v1/identity/me")
                .then()
                .statusCode(200);

        // Old digest is gone from the store; new present; rotatedAt recorded
        assert webSessionStore
                .findByTokenDigest(apiKeyRegistry.computeDigest(sessionToken))
                .isEmpty();
        Optional<WebSessionDto> newOpt = webSessionStore.findByTokenDigest(apiKeyRegistry.computeDigest(newToken));
        assert newOpt.isPresent() : "new session digest must be persisted";
        assert newOpt.get().rotatedAt() != null : "rotatedAt must be set";
    }

    @ApplicationScoped
    @Mock
    public static class MockExternalIdentityProvider implements ExternalIdentityProvider {

        @Override
        public String getProviderId() {
            return "mockidp";
        }

        @Override
        public String getAuthorizationUrl(String state, String redirectUri) {
            return "https://mockidp.com/auth?state=" + state;
        }

        @Override
        public ExternalUserProfile authenticate(String code, String redirectUri) {
            return new ExternalUserProfile("mock_user_123", "mock@user.com", true, "Mock User", "https://avatar.url");
        }
    }
}
