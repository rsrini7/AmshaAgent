package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteIdentityStoreAdapterTest {

    @Inject
    IdentityStorePort identityStore;

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    IdentityInvitationStorePort identityInvitationStore;

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Inject
    Jdbi jdbi;

    @Test
    void reserveFirstOwnerCreatesMissingBootstrapStateAtomically() {
        jdbi.useHandle(handle -> handle.execute("DELETE FROM bootstrap_state"));
        try {
            Instant now = Instant.now();

            Optional<Integer> revision = bootstrapStateStore.reserveFirstOwner("owner-fresh", "workspace-fresh", now);

            assertEquals(Optional.of(1), revision);
            BootstrapStateDto state = bootstrapStateStore.getState().orElseThrow();
            assertEquals(BootstrapStateDto.STATUS_CLAIMING, state.status());
            assertEquals("owner-fresh", state.ownerId());
            assertEquals("workspace-fresh", state.workspaceId());
            assertTrue(bootstrapStateStore
                    .reserveFirstOwner("owner-loser", "workspace-loser", now)
                    .isEmpty());
        } finally {
            jdbi.useHandle(handle -> handle.execute("DELETE FROM bootstrap_state"));
        }
    }

    @Test
    void testIdentityUserOperations() {
        String userId = "user-" + UUID.randomUUID();
        IdentityUserDto user = new IdentityUserDto(userId, "Test User", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);

        Optional<IdentityUserDto> found = identityStore.findUserById(userId);
        assertTrue(found.isPresent());
        assertEquals("Test User", found.get().displayName());
        assertEquals("ACTIVE", found.get().status());
    }

    @Test
    void testExternalIdentityOperations() {
        String userId = "user-" + UUID.randomUUID();
        IdentityUserDto user = new IdentityUserDto(userId, "Test User", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);

        ExternalIdentityDto ext = new ExternalIdentityDto(
                "github", "https://github.com", "sub-123", userId, "{}", Instant.now(), Instant.now());
        identityStore.saveExternalIdentity(ext);

        Optional<ExternalIdentityDto> found =
                identityStore.findExternalIdentity("github", "https://github.com", "sub-123");
        assertTrue(found.isPresent());
        assertEquals(userId, found.get().userId());

        identityStore.deleteExternalIdentity("github", "https://github.com", "sub-123");
        assertTrue(identityStore
                .findExternalIdentity("github", "https://github.com", "sub-123")
                .isEmpty());
    }

    @Test
    void testWorkspaceAndMembershipOperations() {
        String userId = "user-" + UUID.randomUUID();
        IdentityUserDto user = new IdentityUserDto(userId, "Test User", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);

        String workspaceId = "ws-" + UUID.randomUUID();
        IdentityWorkspaceDto ws =
                new IdentityWorkspaceDto(workspaceId, "Workspace name", userId, Instant.now(), Instant.now());
        identityStore.saveWorkspace(ws);

        WorkspaceMembershipDto membership =
                new WorkspaceMembershipDto(workspaceId, userId, "OWNER", "ACTIVE", 0, Instant.now(), Instant.now());
        identityStore.saveMembership(membership);

        Optional<WorkspaceMembershipDto> foundMem = identityStore.findMembership(workspaceId, userId);
        assertTrue(foundMem.isPresent());
        assertEquals("OWNER", foundMem.get().role());

        identityStore.deleteMembership(workspaceId, userId);
        assertTrue(identityStore.findMembership(workspaceId, userId).isEmpty());
    }

    @Test
    void testApiCredentialOperations() {
        String userId = "user-" + UUID.randomUUID();
        IdentityUserDto user = new IdentityUserDto(userId, "Test User", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);

        String credId = "cred-" + UUID.randomUUID();
        ApiCredentialDto cred = new ApiCredentialDto(
                credId, userId, "digest-abc", "OWNER", 0, null, null, Instant.now(), Instant.now());
        apiCredentialStore.save(cred);

        Optional<ApiCredentialDto> found = apiCredentialStore.findById(credId);
        assertTrue(found.isPresent());
        assertEquals("digest-abc", found.get().keyDigest());

        Optional<ApiCredentialDto> foundByDigest = apiCredentialStore.findByKeyDigest("digest-abc");
        assertTrue(foundByDigest.isPresent());

        apiCredentialStore.deleteById(credId);
        assertTrue(apiCredentialStore.findById(credId).isEmpty());
    }

    @Test
    void testWebSessionOperations() {
        String userId = "user-" + UUID.randomUUID();
        IdentityUserDto user = new IdentityUserDto(userId, "Test User", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);

        String sessionId = "sess-" + UUID.randomUUID();
        Instant now = Instant.now();
        WebSessionDto session = new WebSessionDto(
                sessionId,
                userId,
                "token-xyz",
                "csrf-xyz",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                0,
                now,
                now);
        webSessionStore.save(session);

        Optional<WebSessionDto> found = webSessionStore.findById(sessionId);
        assertTrue(found.isPresent());
        assertEquals("token-xyz", found.get().tokenDigest());

        webSessionStore.deleteExpired(now.plusSeconds(10000));
        assertTrue(webSessionStore.findById(sessionId).isEmpty());
    }

    @Test
    void testIdentityInvitationOperations() {
        String userId = "user-" + UUID.randomUUID();
        IdentityUserDto user = new IdentityUserDto(userId, "Test User", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);

        String workspaceId = "ws-" + UUID.randomUUID();
        IdentityWorkspaceDto ws =
                new IdentityWorkspaceDto(workspaceId, "Workspace name", userId, Instant.now(), Instant.now());
        identityStore.saveWorkspace(ws);

        String invitationId = "inv-" + UUID.randomUUID();
        Instant now = Instant.now();
        IdentityInvitationDto invitation = new IdentityInvitationDto(
                invitationId, workspaceId, "MEMBER", "token-inv", null, now.plusSeconds(3600), null, null, 0, now, now);
        identityInvitationStore.save(invitation);

        Optional<IdentityInvitationDto> found = identityInvitationStore.findById(invitationId);
        assertTrue(found.isPresent());
        assertEquals("token-inv", found.get().tokenDigest());

        identityInvitationStore.deleteExpired(now.plusSeconds(10000));
        assertTrue(identityInvitationStore.findById(invitationId).isEmpty());
    }

    @Test
    void testBootstrapStateOperations() {
        BootstrapStateDto state = new BootstrapStateDto(
                "bootstrap", "UNCLAIMED", "user-admin", null, "digest-init", null, null, 0, Instant.now());
        bootstrapStateStore.save(state);

        Optional<BootstrapStateDto> found = bootstrapStateStore.getState();
        assertTrue(found.isPresent());
        assertEquals("UNCLAIMED", found.get().status());
    }
}
