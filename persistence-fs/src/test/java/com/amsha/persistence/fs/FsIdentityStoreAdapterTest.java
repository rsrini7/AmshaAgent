package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsIdentityStoreAdapterTest {

    private Path tempDir;
    private IdentityStorePort identityStore;
    private ApiCredentialStorePort apiCredentialStore;
    private WebSessionStorePort webSessionStore;
    private IdentityInvitationStorePort identityInvitationStore;
    private BootstrapStateStorePort bootstrapStateStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-identity-test-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();

        FsIdentityStoreAdapter identityAdapter = new FsIdentityStoreAdapter();
        identityAdapter.store = fileStore;
        FsApiCredentialStoreAdapter apiAdapter = new FsApiCredentialStoreAdapter();
        apiAdapter.store = fileStore;
        FsWebSessionStoreAdapter sessionAdapter = new FsWebSessionStoreAdapter();
        sessionAdapter.store = fileStore;
        FsIdentityInvitationStoreAdapter invitationAdapter = new FsIdentityInvitationStoreAdapter();
        invitationAdapter.store = fileStore;
        FsBootstrapStateStoreAdapter bootstrapAdapter = new FsBootstrapStateStoreAdapter();
        bootstrapAdapter.store = fileStore;

        identityStore = identityAdapter;
        apiCredentialStore = apiAdapter;
        webSessionStore = sessionAdapter;
        identityInvitationStore = invitationAdapter;
        bootstrapStateStore = bootstrapAdapter;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
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
        assertEquals(0, foundMem.get().revision());

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
        assertEquals(0, found.get().revision());

        Optional<ApiCredentialDto> foundByDigest = apiCredentialStore.findByKeyDigest("digest-abc");
        assertTrue(foundByDigest.isPresent());

        apiCredentialStore.deleteById(credId);
        assertTrue(apiCredentialStore.findById(credId).isEmpty());
    }

    // ─── API credentials ───────────────────────────────────────

    @Test
    void testApiCredentialSaveIsOptimisticallyLockedOnRevision() {
        Instant now = Instant.now();
        String credId = "cred-" + UUID.randomUUID();
        ApiCredentialDto created = credential(credId, "user-lock", "digest-lock", 0, now);

        assertTrue(apiCredentialStore.save(created), "the first save creates the row");
        assertEquals(0, apiCredentialStore.findById(credId).orElseThrow().revision());

        assertTrue(apiCredentialStore.save(created), "a matching revision is accepted");
        assertEquals(
                1,
                apiCredentialStore.findById(credId).orElseThrow().revision(),
                "the adapter persists revision + 1, not the caller's value");

        ApiCredentialDto stale = credential(credId, "user-lock", "digest-lock", 999, now);
        assertFalse(apiCredentialStore.save(stale), "a stale revision is rejected");
        assertEquals(
                1,
                apiCredentialStore.findById(credId).orElseThrow().revision(),
                "the rejected write left the stored row untouched");
    }

    @Test
    void testApiCredentialListingByUserId() {
        Instant now = Instant.now();
        apiCredentialStore.save(credential("cred-a1", "user-a", "digest-a1", 0, now));
        apiCredentialStore.save(credential("cred-a2", "user-a", "digest-a2", 0, now));
        apiCredentialStore.save(credential("cred-b1", "user-b", "digest-b1", 0, now));

        assertEquals(2, apiCredentialStore.listByUserId("user-a").size());
        assertEquals(1, apiCredentialStore.listByUserId("user-b").size());
        assertTrue(apiCredentialStore.listByUserId("nobody").isEmpty());
        assertTrue(
                apiCredentialStore.listByUserId("user-a").stream()
                        .allMatch(c -> c.userId().equals("user-a")),
                "every listed credential belongs to the requested user");
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
        assertEquals(0, found.get().revision());

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
        assertEquals(0, found.get().revision());

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

    // ─── Listing and lookup paths ──────────────────────────────

    @Test
    void testListUsersReturnsEveryPersistedUser() {
        String a = "user-" + UUID.randomUUID();
        String b = "user-" + UUID.randomUUID();
        identityStore.saveUser(new IdentityUserDto(a, "A", "ACTIVE", Instant.now(), Instant.now()));
        identityStore.saveUser(new IdentityUserDto(b, "B", "ACTIVE", Instant.now(), Instant.now()));

        var users = identityStore.listUsers();
        assertEquals(2, users.size());
        assertTrue(users.stream().anyMatch(u -> u.userId().equals(a)));
        assertTrue(users.stream().anyMatch(u -> u.userId().equals(b)));
    }

    @Test
    void testExternalIdentityLookupByUser() {
        String mine = "user-" + UUID.randomUUID();
        String other = "user-" + UUID.randomUUID();
        identityStore.saveExternalIdentity(new ExternalIdentityDto(
                "github", "https://github.com", "sub-mine", mine, "{}", Instant.now(), Instant.now()));
        identityStore.saveExternalIdentity(new ExternalIdentityDto(
                "github", "https://github.com", "sub-other", other, "{}", Instant.now(), Instant.now()));

        assertEquals(1, identityStore.listExternalIdentitiesByUserId(mine).size());
        assertTrue(identityStore.findExternalIdentityByUserId("github", mine).isPresent());
        assertTrue(
                identityStore.findExternalIdentityByUserId("gitlab", mine).isEmpty(),
                "the provider must match as well as the user");
        assertTrue(
                identityStore.findExternalIdentityByUserId("github", "nobody").isEmpty());
    }

    @Test
    void testWorkspaceLookupAndListing() {
        String owner = "user-" + UUID.randomUUID();
        String ws1 = "ws-" + UUID.randomUUID();
        String ws2 = "ws-" + UUID.randomUUID();
        identityStore.saveWorkspace(new IdentityWorkspaceDto(ws1, "One", owner, Instant.now(), Instant.now()));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(ws2, "Two", owner, Instant.now(), Instant.now()));

        assertTrue(identityStore.findWorkspaceById(ws1).isPresent());
        assertTrue(identityStore.findWorkspaceById("missing-workspace").isEmpty());
        assertEquals(2, identityStore.listWorkspaces().size());
    }

    // ─── Membership optimistic locking ─────────────────────────

    @Test
    void testMembershipSaveIsOptimisticallyLockedOnRevision() {
        String ws = "ws-" + UUID.randomUUID();
        String user = "user-" + UUID.randomUUID();
        WorkspaceMembershipDto created =
                new WorkspaceMembershipDto(ws, user, "MEMBER", "ACTIVE", 0, Instant.now(), Instant.now());

        assertTrue(identityStore.saveMembership(created), "the first save creates the row");
        assertEquals(0, identityStore.findMembership(ws, user).orElseThrow().revision());

        assertTrue(identityStore.saveMembership(created), "a matching revision is accepted");
        assertEquals(
                1,
                identityStore.findMembership(ws, user).orElseThrow().revision(),
                "the stored revision is incremented by the adapter");

        WorkspaceMembershipDto stale =
                new WorkspaceMembershipDto(ws, user, "MEMBER", "ACTIVE", 999, Instant.now(), Instant.now());
        assertFalse(identityStore.saveMembership(stale), "a stale revision is rejected");
        assertEquals(
                1,
                identityStore.findMembership(ws, user).orElseThrow().revision(),
                "the rejected write left the row untouched");
    }

    @Test
    void testMembershipListingsByWorkspaceAndByUser() {
        String ws1 = "ws-" + UUID.randomUUID();
        String ws2 = "ws-" + UUID.randomUUID();
        String u1 = "user-" + UUID.randomUUID();
        String u2 = "user-" + UUID.randomUUID();
        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws1, u1, "OWNER", "ACTIVE", 0, Instant.now(), Instant.now()));
        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws1, u2, "MEMBER", "ACTIVE", 0, Instant.now(), Instant.now()));
        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws2, u1, "MEMBER", "ACTIVE", 0, Instant.now(), Instant.now()));

        assertEquals(2, identityStore.listMembershipsByWorkspaceId(ws1).size());
        assertEquals(1, identityStore.listMembershipsByWorkspaceId(ws2).size());
        assertEquals(2, identityStore.listMembershipsByUserId(u1).size());
        assertEquals(1, identityStore.listMembershipsByUserId(u2).size());
    }

    @Test
    void testDeleteMembershipIfNotLastOwner() {
        String ws = "ws-" + UUID.randomUUID();
        String soloOwner = "user-" + UUID.randomUUID();
        String ownerA = "user-" + UUID.randomUUID();
        String ownerB = "user-" + UUID.randomUUID();
        String member = "user-" + UUID.randomUUID();

        assertEquals(0, identityStore.deleteMembershipIfNotLastOwner(ws, "nobody"), "no such membership");

        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws, soloOwner, "OWNER", "ACTIVE", 0, Instant.now(), Instant.now()));
        assertEquals(0, identityStore.deleteMembershipIfNotLastOwner(ws, soloOwner), "the last owner is protected");
        assertTrue(identityStore.findMembership(ws, soloOwner).isPresent());

        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws, member, "MEMBER", "ACTIVE", 0, Instant.now(), Instant.now()));
        assertEquals(1, identityStore.deleteMembershipIfNotLastOwner(ws, member), "a non-owner is removable");
        assertTrue(identityStore.findMembership(ws, member).isEmpty());

        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws, ownerA, "OWNER", "ACTIVE", 0, Instant.now(), Instant.now()));
        identityStore.saveMembership(
                new WorkspaceMembershipDto(ws, ownerB, "OWNER", "ACTIVE", 0, Instant.now(), Instant.now()));
        assertEquals(
                1,
                identityStore.deleteMembershipIfNotLastOwner(ws, ownerA),
                "one of several active owners is removable");
        assertTrue(identityStore.findMembership(ws, ownerA).isEmpty());
        assertTrue(identityStore.findMembership(ws, ownerB).isPresent());
    }

    // ─── Web sessions ──────────────────────────────────────────

    @Test
    void testWebSessionSaveIsOptimisticallyLockedOnRevision() {
        Instant now = Instant.now();
        WebSessionDto created = session("sess-lock", "user-lock", "token-lock", now);

        assertTrue(webSessionStore.save(created), "the first save creates the row");
        assertEquals(0, webSessionStore.findById("sess-lock").orElseThrow().revision());

        assertTrue(webSessionStore.save(created), "a matching revision is accepted");
        assertEquals(
                1,
                webSessionStore.findById("sess-lock").orElseThrow().revision(),
                "the stored revision is incremented by the adapter");

        WebSessionDto stale = new WebSessionDto(
                "sess-lock",
                "user-lock",
                "token-lock",
                "csrf",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                999,
                now,
                now);
        assertFalse(webSessionStore.save(stale), "a stale revision is rejected");
        assertEquals(1, webSessionStore.findById("sess-lock").orElseThrow().revision());
    }

    @Test
    void testWebSessionLookupByTokenDigestAndByUser() {
        Instant now = Instant.now();
        webSessionStore.save(session("sess-a", "user-a", "token-a", now));
        webSessionStore.save(session("sess-b", "user-b", "token-b", now));

        assertEquals(
                "sess-a",
                webSessionStore.findByTokenDigest("token-a").orElseThrow().sessionId());
        assertTrue(webSessionStore.findByTokenDigest("no-such-token").isEmpty());
        assertEquals(1, webSessionStore.listByUserId("user-a").size());
        assertEquals(1, webSessionStore.listByUserId("user-b").size());
        assertTrue(webSessionStore.listByUserId("nobody").isEmpty());
    }

    @Test
    void testWebSessionDeletionByIdAndByUser() {
        Instant now = Instant.now();
        webSessionStore.save(session("sess-x", "user-x", "token-x", now));
        webSessionStore.save(session("sess-y", "user-x", "token-y", now));
        webSessionStore.save(session("sess-z", "user-z", "token-z", now));

        assertEquals(0, webSessionStore.deleteById("missing"), "deleting an absent session removes nothing");
        assertEquals(1, webSessionStore.deleteById("sess-z"));
        assertEquals(0, webSessionStore.deleteById("sess-z"), "the second delete finds nothing");

        assertEquals(2, webSessionStore.deleteByUserId("user-x"));
        assertEquals(0, webSessionStore.deleteByUserId("user-x"), "nothing is left to remove");
        assertTrue(webSessionStore.listByUserId("user-x").isEmpty());
    }

    // ─── Identity invitations ──────────────────────────────────

    @Test
    void testInvitationSaveIsOptimisticallyLockedOnRevision() {
        Instant now = Instant.now();
        IdentityInvitationDto created = invitation("inv-lock", "ws-lock", "token-inv", now.plusSeconds(3600), now);

        assertTrue(identityInvitationStore.save(created), "the first save creates the row");
        assertEquals(
                0, identityInvitationStore.findById("inv-lock").orElseThrow().revision());

        assertTrue(identityInvitationStore.save(created), "a matching revision is accepted");
        assertEquals(
                1,
                identityInvitationStore.findById("inv-lock").orElseThrow().revision(),
                "the stored revision is incremented by the adapter");

        IdentityInvitationDto stale = new IdentityInvitationDto(
                "inv-lock", "ws-lock", "MEMBER", "token-inv", null, now.plusSeconds(3600), null, null, 999, now, now);
        assertFalse(identityInvitationStore.save(stale), "a stale revision is rejected");
        assertEquals(
                1, identityInvitationStore.findById("inv-lock").orElseThrow().revision());
    }

    @Test
    void testInvitationLookupByTokenDigestAndByWorkspace() {
        Instant now = Instant.now();
        identityInvitationStore.save(invitation("inv-a", "ws-1", "token-1", now.plusSeconds(3600), now));
        identityInvitationStore.save(invitation("inv-b", "ws-1", "token-2", now.plusSeconds(3600), now));
        identityInvitationStore.save(invitation("inv-c", "ws-2", "token-3", now.plusSeconds(3600), now));

        assertEquals(
                "inv-a",
                identityInvitationStore
                        .findByTokenDigest("token-1")
                        .orElseThrow()
                        .invitationId());
        assertTrue(identityInvitationStore.findByTokenDigest("no-such-token").isEmpty());
        assertEquals(2, identityInvitationStore.listByWorkspaceId("ws-1").size());
        assertEquals(1, identityInvitationStore.listByWorkspaceId("ws-2").size());
        assertTrue(identityInvitationStore.listByWorkspaceId("ws-none").isEmpty());
    }

    @Test
    void testInvitationDeletionById() {
        Instant now = Instant.now();
        identityInvitationStore.save(invitation("inv-del", "ws-del", "token-del", now.plusSeconds(3600), now));

        assertEquals(1, identityInvitationStore.deleteById("inv-del"));
        assertEquals(0, identityInvitationStore.deleteById("inv-del"), "the second delete finds nothing");
        assertTrue(identityInvitationStore.findById("inv-del").isEmpty());
    }

    // ─── Builders ──────────────────────────────────────────────

    private static ApiCredentialDto credential(
            String credentialId, String userId, String keyDigest, int revision, Instant now) {
        return new ApiCredentialDto(credentialId, userId, keyDigest, "OWNER", revision, null, null, now, now);
    }

    private static WebSessionDto session(String sessionId, String userId, String tokenDigest, Instant now) {
        return new WebSessionDto(
                sessionId,
                userId,
                tokenDigest,
                "csrf",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                0,
                now,
                now);
    }

    private static IdentityInvitationDto invitation(
            String invitationId, String workspaceId, String tokenDigest, Instant expiresAt, Instant now) {
        return new IdentityInvitationDto(
                invitationId, workspaceId, "MEMBER", tokenDigest, null, expiresAt, null, null, 0, now, now);
    }
}
