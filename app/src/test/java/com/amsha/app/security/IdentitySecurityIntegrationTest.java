package com.amsha.app.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.app.api.IdentityResource.*;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.Test;

import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class IdentitySecurityIntegrationTest {

    @Inject
    IdentityStorePort identityStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    IdentityInvitationStorePort invitationStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    private String sessionCookie(String userId, String workspaceId) {
        String sessionToken = "sess-" + UUID.randomUUID();
        String csrfToken = "csrf-" + UUID.randomUUID();
        Instant now = Instant.now();
        webSessionStore.save(new WebSessionDto(
                "sess-" + UUID.randomUUID(),
                userId,
                apiKeyRegistry.computeDigest(sessionToken),
                apiKeyRegistry.computeDigest(csrfToken),
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now));
        return sessionToken + ":" + csrfToken;
    }

    private void seedOwner(String userId, String workspaceId) {
        Instant now = Instant.now();
        identityStore.saveUser(new IdentityUserDto(userId, userId, "ACTIVE", now, now));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, workspaceId, userId, now, now));
        identityStore.saveMembership(new WorkspaceMembershipDto(workspaceId, userId, "OWNER", "ACTIVE", 1, now, now));
    }

    private LoginFlow login(String invitationToken, String workspaceId) {
        Instant now = Instant.now();
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, workspaceId, "usr_seed", now, now));
        String token = invitationToken;
        invitationStore.save(new IdentityInvitationDto(
                "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                workspaceId,
                "MEMBER",
                apiKeyRegistry.computeDigest(token),
                null,
                now.plusSeconds(3600),
                null,
                null,
                1,
                now,
                now));
        String location = given().when()
                .redirects()
                .follow(false)
                .get("/api/v1/auth/login/mockidp?invitationToken=" + token)
                .then()
                .statusCode(303)
                .extract()
                .header("Location");
        String state = location.substring(location.indexOf("state=") + 6);
        if (state.contains("&")) {
            state = state.substring(0, state.indexOf("&"));
        }
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
        return new LoginFlow(state, cb.cookie("AMSHA_SESSION_ID"), cb.cookie("X-CSRF-Token"));
    }

    private record LoginFlow(String state, String sessionToken, String csrfToken) {}

    @Test
    void replayOfAuthStateIsRejected() {
        LoginFlow flow = login("inv-replay", "ws-replay");

        // Replay the same state (already consumed) -> rejected
        given().when()
                .redirects()
                .follow(false)
                .queryParam("code", "mock-auth-code")
                .queryParam("state", flow.state)
                .get("/api/v1/auth/callback/mockidp")
                .then()
                .statusCode(400)
                .body("code", is("INVALID_STATE"));
    }

    @Test
    void issuerConfusionAcrossProvidersIsRejected() {
        LoginFlow flow = login("inv-confuse", "ws-confuse");

        // Use a state issued for mockidp against a different provider callback -> provider mismatch
        given().when()
                .redirects()
                .follow(false)
                .queryParam("code", "mock-auth-code")
                .queryParam("state", flow.state)
                .get("/api/v1/auth/callback/github")
                .then()
                .statusCode(400)
                .body("code", is("INVALID_STATE"));
    }

    @Test
    void sessionFixationIsPrevented() {
        String forged = "sess-forged-" + UUID.randomUUID();

        // A client-supplied (fixated) session id is not trusted
        given().cookie("AMSHA_SESSION_ID", forged)
                .get("/api/v1/identity/me")
                .then()
                .statusCode(401);

        assert webSessionStore
                .findByTokenDigest(apiKeyRegistry.computeDigest(forged))
                .isEmpty();

        // Real login issues a fresh, distinct token
        LoginFlow flow = login("inv-fix", "ws-fix");
        assert !flow.sessionToken.equals(forged);
    }

    @Test
    void crossWorkspaceAccessIsDenied() {
        seedOwner("usr_wsA_owner", "ws_A");
        seedOwner("usr_wsB_owner", "ws_B");
        String[] a = sessionCookie("usr_wsA_owner", "ws_A").split(":");
        String aSession = a[0];
        String aCsrf = a[1];

        // wsA owner cannot enumerate or invite into wsB
        given().cookie("AMSHA_SESSION_ID", aSession)
                .get("/api/v1/identity/workspaces/ws_B/members")
                .then()
                .statusCode(403);

        given().cookie("AMSHA_SESSION_ID", aSession)
                .header("X-CSRF-Token", aCsrf)
                .contentType(ContentType.JSON)
                .body(new CreateInvitationRequest("MEMBER", null))
                .post("/api/v1/identity/workspaces/ws_B/invitations")
                .then()
                .statusCode(403);
    }

    @Test
    void invitationIsSingleUse() {
        seedOwner("usr_inv_owner", "ws_inv");
        String[] owner = sessionCookie("usr_inv_owner", "ws_inv").split(":");
        String ownerSession = owner[0];
        String ownerCsrf = owner[1];

        var invite = given().cookie("AMSHA_SESSION_ID", ownerSession)
                .header("X-CSRF-Token", ownerCsrf)
                .contentType(ContentType.JSON)
                .body(new CreateInvitationRequest("MEMBER", null))
                .post("/api/v1/identity/workspaces/ws_inv/invitations")
                .then()
                .statusCode(200)
                .extract();
        String token = invite.path("rawToken");

        // First accepter
        String user2 = "usr_inv_member2";
        identityStore.saveUser(new IdentityUserDto(user2, user2, "ACTIVE", Instant.now(), Instant.now()));
        String[] u2 = sessionCookie(user2, "ws_inv").split(":");
        given().cookie("AMSHA_SESSION_ID", u2[0])
                .header("X-CSRF-Token", u2[1])
                .contentType(ContentType.JSON)
                .post("/api/v1/identity/invitations/" + token + "/accept")
                .then()
                .statusCode(200);

        // Second accepter (different user) is blocked: invitation already accepted
        String user3 = "usr_inv_member3";
        identityStore.saveUser(new IdentityUserDto(user3, user3, "ACTIVE", Instant.now(), Instant.now()));
        String[] u3 = sessionCookie(user3, "ws_inv").split(":");
        given().cookie("AMSHA_SESSION_ID", u3[0])
                .header("X-CSRF-Token", u3[1])
                .contentType(ContentType.JSON)
                .post("/api/v1/identity/invitations/" + token + "/accept")
                .then()
                .statusCode(410)
                .body("code", is("INVITATION_EXPIRED"));
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
