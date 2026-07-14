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
            return new ExternalUserProfile("mock_user_123", "mock@user.com", "Mock User", "https://avatar.url");
        }
    }
}
