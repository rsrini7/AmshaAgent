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
}
