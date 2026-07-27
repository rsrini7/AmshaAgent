package com.amsha.app.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.app.api.IdentityResource.*;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class IdentityResourceTest {

    @Inject
    IdentityStorePort identityStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Test
    void testIdentityFlowsAndLastOwnerProtection() {
        String sessionToken = "sess-" + UUID.randomUUID().toString();
        String csrfToken = "csrf-" + UUID.randomUUID().toString();
        String userId = "usr_owner123";
        String workspaceId = "ws_test123";

        // Compute digests
        String tokenDigest = apiKeyRegistry.computeDigest(sessionToken);
        String csrfDigest = apiKeyRegistry.computeDigest(csrfToken);

        Instant now = Instant.now();

        // Seed DB rows
        identityStore.saveUser(new IdentityUserDto(userId, "Workspace Owner", "ACTIVE", now, now));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, "Test Workspace", userId, now, now));
        identityStore.saveMembership(new WorkspaceMembershipDto(workspaceId, userId, "OWNER", "ACTIVE", 1, now, now));

        // Save session in DB
        WebSessionDto session = new WebSessionDto(
                "session-owner-123",
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

        // 1. GET /me -> 200 OK
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .when()
                .get("/api/v1/identity/me")
                .then()
                .statusCode(200)
                .body("user.userId", is(userId))
                .body("memberships[0].role", is("OWNER"));

        // 2. POST /invitations -> 200 OK with token
        var inviteResponse = given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .body(new CreateInvitationRequest("member", null))
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/invitations")
                .then()
                .statusCode(200)
                .body("invitationId", notNullValue())
                .body("rawToken", notNullValue())
                .extract();

        String invitationToken = inviteResponse.path("rawToken");

        // 3. POST /invitations/{token}/accept -> 200 OK
        // Accept invitation with a second user session
        String user2SessionToken = "sess-" + UUID.randomUUID().toString();
        String user2CsrfToken = "csrf-" + UUID.randomUUID().toString();
        String user2Id = "usr_member456";

        identityStore.saveUser(new IdentityUserDto(user2Id, "New Member", "ACTIVE", now, now));
        WorkspaceMembershipDto existingMembership =
                new WorkspaceMembershipDto(workspaceId, user2Id, "ADMIN", "ACTIVE", 0, now, now);
        assertTrue(identityStore.saveMembership(existingMembership));
        assertTrue(identityStore.saveMembership(existingMembership));
        WebSessionDto session2 = new WebSessionDto(
                "session-member-456",
                user2Id,
                apiKeyRegistry.computeDigest(user2SessionToken),
                apiKeyRegistry.computeDigest(user2CsrfToken),
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now);
        webSessionStore.save(session2);

        given().cookie("AMSHA_SESSION_ID", user2SessionToken)
                .header("X-CSRF-Token", user2CsrfToken)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/identity/invitations/" + invitationToken + "/accept")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("workspaceId", is(workspaceId))
                .body("role", is("MEMBER"));
        assertEquals(
                2,
                identityStore.findMembership(workspaceId, user2Id).orElseThrow().revision());

        // 4. DELETE last active owner -> 400 Bad Request
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .when()
                .delete("/api/v1/identity/workspaces/" + workspaceId + "/members/" + userId)
                .then()
                .statusCode(400)
                .body("code", is("LAST_OWNER_REMOVAL_BLOCKED"));

        // 5. POST API credentials -> 200 OK
        var credResponse = given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .body(new CreateApiCredentialRequest("OWNER", null))
                .when()
                .post("/api/v1/identity/api-credentials")
                .then()
                .statusCode(200)
                .body("credentialId", notNullValue())
                .body("rawKey", notNullValue())
                .extract();

        String credentialId = credResponse.path("credentialId");

        // 6. DELETE API credentials -> 200 OK
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .when()
                .delete("/api/v1/identity/api-credentials/" + credentialId)
                .then()
                .statusCode(200)
                .body("success", is(true));

        // [F1 Test] Member usr_member456 (user2SessionToken) cannot mint OWNER api-credentials
        given().cookie("AMSHA_SESSION_ID", user2SessionToken)
                .header("X-CSRF-Token", user2CsrfToken)
                .contentType(ContentType.JSON)
                .body(new CreateApiCredentialRequest("OWNER", null))
                .when()
                .post("/api/v1/identity/api-credentials")
                .then()
                .statusCode(403);

        // [F2 Test] Member usr_member456 (user2SessionToken) cannot create invites at all
        given().cookie("AMSHA_SESSION_ID", user2SessionToken)
                .header("X-CSRF-Token", user2CsrfToken)
                .contentType(ContentType.JSON)
                .body(new CreateInvitationRequest("MEMBER", null))
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/invitations")
                .then()
                .statusCode(403);

        // Seed an ADMIN user to test ADMIN-to-OWNER invite block
        String adminUserSessionToken = "sess-" + UUID.randomUUID().toString();
        String adminUserCsrfToken = "csrf-" + UUID.randomUUID().toString();
        String adminUserId = "usr_admin789";

        identityStore.saveUser(new IdentityUserDto(adminUserId, "Admin User", "ACTIVE", now, now));
        identityStore.saveMembership(
                new WorkspaceMembershipDto(workspaceId, adminUserId, "ADMIN", "ACTIVE", 1, now, now));
        WebSessionDto adminSession = new WebSessionDto(
                "session-admin-789",
                adminUserId,
                apiKeyRegistry.computeDigest(adminUserSessionToken),
                apiKeyRegistry.computeDigest(adminUserCsrfToken),
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now);
        webSessionStore.save(adminSession);

        // [F2 Test] Admin cannot invite OWNER
        given().cookie("AMSHA_SESSION_ID", adminUserSessionToken)
                .header("X-CSRF-Token", adminUserCsrfToken)
                .contentType(ContentType.JSON)
                .body(new CreateInvitationRequest("OWNER", null))
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/invitations")
                .then()
                .statusCode(403);

        // Admin cannot remove an owner, even when another active owner exists.
        String secondOwnerId = "usr_owner_second";
        identityStore.saveUser(new IdentityUserDto(secondOwnerId, "Second Owner", "ACTIVE", now, now));
        identityStore.saveMembership(
                new WorkspaceMembershipDto(workspaceId, secondOwnerId, "OWNER", "ACTIVE", 1, now, now));
        given().cookie("AMSHA_SESSION_ID", adminUserSessionToken)
                .header("X-CSRF-Token", adminUserCsrfToken)
                .when()
                .delete("/api/v1/identity/workspaces/" + workspaceId + "/members/" + userId)
                .then()
                .statusCode(403)
                .body("code", is("FORBIDDEN"));

        // [F0 Test] Member user2SessionToken is blocked from admin endpoints (mutating PUT)
        given().cookie("AMSHA_SESSION_ID", user2SessionToken)
                .header("X-CSRF-Token", user2CsrfToken)
                .contentType(ContentType.JSON)
                .body("{\"content\":\"test\",\"description\":\"test\",\"active\":true,\"version\":1}")
                .when()
                .put("/api/v1/admin/prompts/test-id")
                .then()
                .statusCode(403);

        // [F0 Test] Owner sessionToken can access admin endpoints (returns 404 since prompt test-id doesn't exist, but
        // not 403)
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .body("{\"content\":\"test\",\"description\":\"test\",\"active\":true,\"version\":1}")
                .when()
                .put("/api/v1/admin/prompts/test-id")
                .then()
                .statusCode(404);
    }

    @Test
    void testOwnershipTransferAndCredentialRotation() {
        String sessionToken = "sess-" + UUID.randomUUID().toString();
        String csrfToken = "csrf-" + UUID.randomUUID().toString();
        String ownerId = "usr_xfer_owner";
        String memberId = "usr_xfer_member";
        String workspaceId = "ws_xfer_1";
        Instant now = Instant.now();

        identityStore.saveUser(new IdentityUserDto(ownerId, "Owner", "ACTIVE", now, now));
        identityStore.saveUser(new IdentityUserDto(memberId, "Member", "ACTIVE", now, now));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, "Xfer WS", ownerId, now, now));
        identityStore.saveMembership(new WorkspaceMembershipDto(workspaceId, ownerId, "OWNER", "ACTIVE", 1, now, now));
        identityStore.saveMembership(
                new WorkspaceMembershipDto(workspaceId, memberId, "MEMBER", "ACTIVE", 1, now, now));
        webSessionStore.save(new WebSessionDto(
                "sess-xfer-owner",
                ownerId,
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

        String memberSession = "sess-" + UUID.randomUUID().toString();
        String memberCsrf = "csrf-" + UUID.randomUUID().toString();
        webSessionStore.save(new WebSessionDto(
                "sess-xfer-member",
                memberId,
                apiKeyRegistry.computeDigest(memberSession),
                apiKeyRegistry.computeDigest(memberCsrf),
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now));

        // Member cannot transfer ownership
        given().cookie("AMSHA_SESSION_ID", memberSession)
                .header("X-CSRF-Token", memberCsrf)
                .contentType(ContentType.JSON)
                .body(new TransferOwnerRequest(ownerId))
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/owner/transfer")
                .then()
                .statusCode(403);

        // Member creates an API credential (to be rotated later)
        var cred = given().cookie("AMSHA_SESSION_ID", memberSession)
                .header("X-CSRF-Token", memberCsrf)
                .contentType(ContentType.JSON)
                .body(new CreateApiCredentialRequest("MEMBER", null))
                .when()
                .post("/api/v1/identity/api-credentials")
                .then()
                .statusCode(200)
                .body("credentialId", notNullValue())
                .extract();
        String credentialId = cred.path("credentialId");

        // Owner transfers ownership to the member
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .body(new TransferOwnerRequest(memberId))
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/owner/transfer")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("newOwnerId", is(memberId));

        // Previous owner is now ADMIN and can no longer transfer
        given().cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .contentType(ContentType.JSON)
                .body(new TransferOwnerRequest(memberId))
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/owner/transfer")
                .then()
                .statusCode(403);

        // New owner rotates all workspace credentials
        given().cookie("AMSHA_SESSION_ID", memberSession)
                .header("X-CSRF-Token", memberCsrf)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/identity/workspaces/" + workspaceId + "/credentials/rotate")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("revokedCount", greaterThanOrEqualTo(1));

        // The member's credential is now revoked
        var revoked = apiCredentialStore.findById(credentialId);
        assert revoked.isPresent();
        assert revoked.get().revokedAt() != null;
    }
}
