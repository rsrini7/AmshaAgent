package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link IdentityResourceHelper}, the governance logic behind identity and workspace endpoints.
 *
 * <p>Two of these methods are authorisation checks ({@code validateInvitationRole},
 * {@code validateMemberRemoval}) whose only job is to return a 403/400 for a privilege-escalation attempt and
 * {@code null} otherwise, so each escalation shape is asserted by status <em>and</em> error code.
 */
class IdentityResourceHelperTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static WorkspaceMembershipDto member(String userId, String role, String status) {
        return new WorkspaceMembershipDto("ws-1", userId, role, status, 0, NOW, NOW);
    }

    private static ApiCredentialDto credential(String id, String userId, Instant expiresAt, Instant revokedAt) {
        return new ApiCredentialDto(id, userId, "digest-" + id, "OWNER", 0, expiresAt, revokedAt, NOW, NOW);
    }

    private static ExternalIdentityDto externalIdentity(String claims) {
        return new ExternalIdentityDto("google", "https://issuer.example", "sub-1", "user-1", claims, NOW, NOW);
    }

    private static String errorCode(Response response) {
        return ((IdentityResource.ErrorResponse) response.getEntity()).code();
    }

    // ---------- token generation ----------

    @Test
    @DisplayName("an invitation token is a prefixed, unpadded, URL-safe 32-byte value")
    void invitationTokenHasTheExpectedShape() {
        String token = IdentityResourceHelper.generateInvitationToken();

        assertTrue(token.matches("inv-[A-Za-z0-9_-]{43}"), "unexpected token shape: " + token);
        assertFalse(token.contains("="), "the encoder must not pad");
        assertFalse(token.contains("+"), "the encoder must be URL-safe");
    }

    @Test
    @DisplayName("successive invitation tokens differ")
    void invitationTokensAreUnique() {
        assertFalse(IdentityResourceHelper.generateInvitationToken()
                .equals(IdentityResourceHelper.generateInvitationToken()));
    }

    @Test
    @DisplayName("an invitation id is a prefixed 16-character hex value")
    void invitationIdHasTheExpectedShape() {
        String id = IdentityResourceHelper.generateInvitationId();

        assertTrue(id.matches("inv_[0-9a-f]{16}"), "unexpected id shape: " + id);
    }

    // ---------- role validation ----------

    @Test
    @DisplayName("only the three allowed roles are valid, case-insensitively")
    void onlyAllowedRolesAreValid() {
        assertTrue(IdentityResourceHelper.isValidRole("OWNER"));
        assertTrue(IdentityResourceHelper.isValidRole("owner"));
        assertTrue(IdentityResourceHelper.isValidRole("Admin"));
        assertTrue(IdentityResourceHelper.isValidRole("member"));

        assertFalse(IdentityResourceHelper.isValidRole(null));
        assertFalse(IdentityResourceHelper.isValidRole("SUPERUSER"));
        assertFalse(IdentityResourceHelper.isValidRole(""));
    }

    @Test
    @DisplayName("an unknown requested role is rejected with INVALID_ROLE")
    void unknownRequestedRoleIsRejected() {
        Response response = IdentityResourceHelper.validateInvitationRole("OWNER", "SUPERUSER");

        assertNotNull(response);
        assertEquals(403, response.getStatus());
        assertEquals("INVALID_ROLE", errorCode(response));

        assertNotNull(IdentityResourceHelper.validateInvitationRole("OWNER", null));
    }

    @Test
    @DisplayName("a non-owner cannot invite an owner")
    void nonOwnerCannotInviteAnOwner() {
        Response response = IdentityResourceHelper.validateInvitationRole("ADMIN", "OWNER");

        assertNotNull(response);
        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
    }

    @Test
    @DisplayName("an owner can invite an owner")
    void ownerCanInviteAnOwner() {
        assertNull(IdentityResourceHelper.validateInvitationRole("OWNER", "OWNER"));
        assertNull(IdentityResourceHelper.validateInvitationRole("OWNER", "admin"));
    }

    @Test
    @DisplayName("a member inviting an admin is ALLOWED — the escalation guard is unreachable")
    void memberEscalationGuardIsUnreachable() {
        // FINDING (reported, not "fixed"): the third guard in validateInvitationRole requires
        // !"OWNER".equals(callerRole) AND a normalized role outside {ADMIN, MEMBER}. isValidRole has already
        // narrowed the role to {OWNER, ADMIN, MEMBER}, so "outside {ADMIN, MEMBER}" means OWNER — and any
        // OWNER request from a non-owner already returned at the second guard. The third guard therefore can
        // never fire, and a MEMBER really can invite an ADMIN. This test pins the actual behaviour so a
        // future change to the guard is noticed.
        assertNull(
                IdentityResourceHelper.validateInvitationRole("MEMBER", "ADMIN"),
                "the escalation guard is dead; a MEMBER is currently allowed to invite an ADMIN");
    }

    @Test
    @DisplayName("a member can invite a member")
    void memberCanInviteAMember() {
        assertNull(IdentityResourceHelper.validateInvitationRole("MEMBER", "member"));
    }

    // ---------- member removal ----------

    @Test
    @DisplayName("an admin cannot remove an owner")
    void adminCannotRemoveAnOwner() {
        Response response =
                IdentityResourceHelper.validateMemberRemoval("ADMIN", member("user-2", "OWNER", "ACTIVE"), List.of());

        assertNotNull(response);
        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
    }

    @Test
    @DisplayName("the last active owner cannot be removed")
    void lastActiveOwnerCannotBeRemoved() {
        List<WorkspaceMembershipDto> members = List.of(
                member("user-1", "OWNER", "ACTIVE"),
                member("user-2", "OWNER", "INACTIVE"),
                member("user-3", "MEMBER", "ACTIVE"));

        Response response =
                IdentityResourceHelper.validateMemberRemoval("OWNER", member("user-1", "OWNER", "ACTIVE"), members);

        assertNotNull(response);
        assertEquals(400, response.getStatus());
        assertEquals("LAST_OWNER_REMOVAL_BLOCKED", errorCode(response));
    }

    @Test
    @DisplayName("an owner can be removed while another active owner remains")
    void ownerCanBeRemovedWhenAnotherOwnerRemains() {
        List<WorkspaceMembershipDto> members =
                List.of(member("user-1", "OWNER", "ACTIVE"), member("user-2", "OWNER", "ACTIVE"));

        assertNull(IdentityResourceHelper.validateMemberRemoval("OWNER", member("user-1", "OWNER", "ACTIVE"), members));
    }

    @Test
    @DisplayName("a non-owner can always be removed")
    void nonOwnerCanBeRemoved() {
        assertNull(
                IdentityResourceHelper.validateMemberRemoval("ADMIN", member("user-2", "MEMBER", "ACTIVE"), List.of()));
    }

    // ---------- email constraint ----------

    @Test
    @DisplayName("an absent email constraint matches everything")
    void absentEmailConstraintMatches() {
        assertTrue(IdentityResourceHelper.matchesEmailConstraint(List.of(), null));
        assertTrue(IdentityResourceHelper.matchesEmailConstraint(List.of(), "   "));
    }

    @Test
    @DisplayName("a verified email matches the constraint case-insensitively and ignoring padding")
    void verifiedEmailMatches() {
        List<ExternalIdentityDto> ids = List.of(externalIdentity("{\"email\":\"  A@B.C  \",\"email_verified\":true}"));

        assertTrue(IdentityResourceHelper.matchesEmailConstraint(ids, "a@b.c"));
    }

    @Test
    @DisplayName("an unverified email does not satisfy the constraint")
    void unverifiedEmailDoesNotMatch() {
        List<ExternalIdentityDto> ids = List.of(externalIdentity("{\"email\":\"a@b.c\",\"email_verified\":false}"));

        assertFalse(IdentityResourceHelper.matchesEmailConstraint(ids, "a@b.c"));
    }

    @Test
    @DisplayName("claims without an email key are skipped without parsing")
    void claimsWithoutEmailAreSkipped() {
        List<ExternalIdentityDto> ids = List.of(externalIdentity("{\"sub\":\"x\"}"));

        assertFalse(IdentityResourceHelper.matchesEmailConstraint(ids, "a@b.c"));
    }

    @Test
    @DisplayName("a non-matching email returns false")
    void nonMatchingEmailReturnsFalse() {
        List<ExternalIdentityDto> ids = List.of(externalIdentity("{\"email\":\"a@b.c\",\"email_verified\":true}"));

        assertFalse(IdentityResourceHelper.matchesEmailConstraint(ids, "someone@else.example"));
    }

    // ---------- claim parsing ----------

    @Test
    @DisplayName("claim parsing tolerates null, malformed JSON and a missing email")
    void claimParsingToleratesBadInput() {
        assertNull(IdentityResourceHelper.parseVerifiedEmail(null));
        assertNull(IdentityResourceHelper.parseVerifiedEmail("{not json"));
        assertNull(IdentityResourceHelper.parseVerifiedEmail("{\"sub\":\"x\"}"));
        assertNull(IdentityResourceHelper.parseVerifiedEmail("{\"email\":\"   \"}"));
        assertNull(IdentityResourceHelper.parseVerifiedEmail("{\"email\":123}"));
    }

    @Test
    @DisplayName("claim parsing reads the verified flag, defaulting to false")
    void claimParsingReadsTheVerifiedFlag() {
        assertTrue(IdentityResourceHelper.parseVerifiedEmail("{\"email\":\"a@b.c\",\"email_verified\":true}")
                .verified());
        assertFalse(IdentityResourceHelper.parseVerifiedEmail("{\"email\":\"a@b.c\"}")
                .verified());
        assertEquals(
                "a@b.c",
                IdentityResourceHelper.parseVerifiedEmail("{\"email\":\"a@b.c\"}")
                        .email());
    }

    // ---------- credential rotation ----------

    @Test
    @DisplayName("rotation revokes and unregisters only live credentials of active members")
    void rotationRevokesOnlyLiveCredentialsOfActiveMembers() {
        // Rotation compares against Instant.now(), so these expiries must be relative to the real clock —
        // a fixed past instant would make the "live" credential look expired.
        Instant realNow = Instant.now();
        ApiCredentialStorePort store = mock(ApiCredentialStorePort.class);
        ApiKeyRegistry registry = mock(ApiKeyRegistry.class);
        when(store.listByUserId("user-1"))
                .thenReturn(List.of(
                        credential("live", "user-1", realNow.plusSeconds(3600), null),
                        credential("already-revoked", "user-1", realNow.plusSeconds(3600), realNow),
                        credential("expired", "user-1", realNow.minusSeconds(60), null)));

        int revoked = IdentityResourceHelper.rotateActiveCredentials(
                List.of(member("user-1", "OWNER", "ACTIVE"), member("user-2", "MEMBER", "INACTIVE")), store, registry);

        assertEquals(1, revoked);

        ArgumentCaptor<ApiCredentialDto> saved = ArgumentCaptor.forClass(ApiCredentialDto.class);
        verify(store, times(1)).save(saved.capture());
        assertEquals("live", saved.getValue().credentialId());
        assertNotNull(saved.getValue().revokedAt(), "rotation must stamp revokedAt");
        assertNotNull(saved.getValue().updatedAt());
        assertEquals(realNow.plusSeconds(3600), saved.getValue().expiresAt(), "the original expiry must be preserved");

        verify(registry, times(1)).unregisterByDigest("digest-live");
        verify(store, never()).listByUserId("user-2");
    }

    @Test
    @DisplayName("rotation reports zero when nothing needs revoking")
    void rotationReportsZeroWhenThereIsNothingToDo() {
        ApiCredentialStorePort store = mock(ApiCredentialStorePort.class);
        ApiKeyRegistry registry = mock(ApiKeyRegistry.class);
        when(store.listByUserId("user-1")).thenReturn(List.of());

        int revoked = IdentityResourceHelper.rotateActiveCredentials(
                List.of(member("user-1", "OWNER", "ACTIVE")), store, registry);

        assertEquals(0, revoked);
        verify(store, never()).save(any());
        verify(registry, never()).unregisterByDigest(any());
    }

    @Test
    @DisplayName("rotation touches nothing when there are no members at all")
    void rotationTouchesNothingWithoutMembers() {
        ApiCredentialStorePort store = mock(ApiCredentialStorePort.class);
        ApiKeyRegistry registry = mock(ApiKeyRegistry.class);

        assertEquals(0, IdentityResourceHelper.rotateActiveCredentials(List.of(), store, registry));
        verify(store, never()).listByUserId(any());
    }
}
