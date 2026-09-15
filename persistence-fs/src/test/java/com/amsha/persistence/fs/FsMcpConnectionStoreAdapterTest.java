package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsMcpConnectionStoreAdapterTest {

    private Path tempDir;
    private McpConnectionStorePort store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-mcp-test-");
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
        FsMcpConnectionStoreAdapter adapter = new FsMcpConnectionStoreAdapter();
        adapter.store = fileStore;
        store = adapter;
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
    void mcpConnectionStoreContract() {
        String prefix = "fs-" + UUID.randomUUID();
        String userOne = prefix + "-user-1";
        String userTwo = prefix + "-user-2";
        McpConnectionDto userOneConnection = connection(prefix + "-mcp-1", userOne, "docs", true);
        McpConnectionDto disabled = connection(prefix + "-mcp-2", userOne, "disabled", false);
        McpConnectionDto userTwoConnection = connection(prefix + "-mcp-3", userTwo, "docs", true);

        store.save(userOneConnection);
        store.save(disabled);
        store.save(userTwoConnection);

        assertConnection(
                userOneConnection,
                store.findByIdAndUserId(userOneConnection.id(), userOne).orElseThrow());
        assertTrue(store.findByIdAndUserId(userOneConnection.id(), userTwo).isEmpty());
        assertConnection(
                userTwoConnection, store.findByNameAndUserId("docs", userTwo).orElseThrow());
        assertEquals(Set.of(userOneConnection.id(), disabled.id()), ids(store.listByUserId(userOne)));
        Set<String> enabledIds = ids(store.listEnabled());
        assertTrue(enabledIds.containsAll(Set.of(userOneConnection.id(), userTwoConnection.id())));
        assertFalse(enabledIds.contains(disabled.id()));

        store.replaceHeaderSecretRefs(
                userOneConnection.id(),
                userOne,
                List.of(headerRef(prefix + "-ref-1", userOneConnection.id(), userOne, "Authorization", "vault-auth")));
        List<McpConnectionHeaderSecretRefDto> refs = store.listHeaderSecretRefs(userOneConnection.id(), userOne);
        assertEquals(1, refs.size());
        assertEquals("authorization", refs.get(0).headerName());
        assertTrue(store.listHeaderSecretRefs(userOneConnection.id(), userTwo).isEmpty());

        assertThrows(
                IllegalArgumentException.class,
                () -> store.replaceHeaderSecretRefs(
                        userOneConnection.id(),
                        userOne,
                        List.of(
                                headerRef(prefix + "-ref-2", userOneConnection.id(), userOne, "X-Api-Key", "vault-1"),
                                headerRef(
                                        prefix + "-ref-3", userOneConnection.id(), userOne, "x-api-key", "vault-2"))));

        McpConnectionCleanupFailureDto failure = cleanupFailure(
                prefix + "-failure-1", userOneConnection.id(), userOne, "authorization", "vault-auth", 1);
        store.saveCleanupFailure(failure);
        assertTrue(store.listCleanupFailures().stream()
                .anyMatch(row -> failure.id().equals(row.id())));
        assertEquals(0, store.deleteCleanupFailure(failure.id(), userTwo));
        assertEquals(1, store.deleteCleanupFailure(failure.id(), userOne));

        assertEquals(0, store.deleteByIdAndUserId(userOneConnection.id(), userTwo));
        assertEquals(1, store.deleteByIdAndUserId(userOneConnection.id(), userOne));
        assertTrue(store.findByIdAndUserId(userOneConnection.id(), userOne).isEmpty());
        assertTrue(store.listHeaderSecretRefs(userOneConnection.id(), userOne).isEmpty());
        assertConnection(
                userTwoConnection,
                store.findByIdAndUserId(userTwoConnection.id(), userTwo).orElseThrow());
    }

    @Test
    void mcpConnectionPendingApprovalsAndToolPolicies() {
        String userId = "usr-policies";
        McpConnectionDto pending = new McpConnectionDto(
                "mcp-pending",
                userId,
                "pending-srv",
                "HTTP_SSE",
                "https://example.com/mcp",
                "NETWORK",
                false,
                "PENDING_APPROVAL",
                "task-1",
                0,
                null,
                Instant.now(),
                Instant.now());
        store.save(pending);

        List<McpConnectionDto> pendingList = store.listPendingApprovals();
        assertTrue(pendingList.stream().anyMatch(c -> "mcp-pending".equals(c.id())));

        // Revision update
        McpConnectionDto approved = new McpConnectionDto(
                pending.id(),
                pending.userId(),
                pending.name(),
                pending.transport(),
                pending.endpoint(),
                pending.category(),
                true,
                "READY",
                null,
                1,
                null,
                pending.createdAt(),
                Instant.now());
        assertFalse(store.updateStateIfRevision(approved, 5)); // Wrong revision
        assertTrue(store.updateStateIfRevision(approved, 0)); // Correct revision

        // Tool policies
        Instant now = Instant.now();
        McpConnectionToolPolicyDto policy1 = new McpConnectionToolPolicyDto(
                "pol-1",
                approved.id(),
                userId,
                "search_tool",
                true,
                true,
                "Search description",
                "{\"type\":\"object\"}",
                "hash1",
                now,
                0,
                now);
        store.replaceToolPolicies(approved.id(), userId, List.of(policy1));

        List<McpConnectionToolPolicyDto> policies = store.listToolPolicies(approved.id(), userId);
        assertEquals(1, policies.size());
        assertEquals("search_tool", policies.get(0).toolName());
        assertTrue(policies.get(0).enabled());

        int modified = store.setToolEnabled(approved.id(), userId, "search_tool", false);
        assertEquals(1, modified);
        assertFalse(store.listToolPolicies(approved.id(), userId).get(0).enabled());
    }

    @Test
    void toolPolicyAndHeaderRefValidationRejectsBadInput() {
        String prefix = "fs-" + UUID.randomUUID();
        String user = prefix + "-user";
        McpConnectionDto conn = connection(prefix + "-mcp-val", user, "docs", true);
        store.save(conn);

        // Blank identifiers short-circuit the read paths and are rejected by the write paths.
        assertTrue(store.listToolPolicies("", user).isEmpty());
        assertTrue(store.listToolPolicies(conn.id(), "").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.replaceToolPolicies("", user, List.of()));
        assertEquals(0, store.setToolEnabled("", user, "search_tool", true));

        // A policy or header ref can only be written for a connection that exists. Unlike the SQL adapters this one
        // has no separate blank-id guard on replaceHeaderSecretRefs, so the same lookup rejects it.
        String missing = prefix + "-absent";
        assertThrows(IllegalArgumentException.class, () -> store.replaceToolPolicies(missing, user, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.replaceHeaderSecretRefs(missing, user, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.replaceHeaderSecretRefs("", user, List.of()));

        // Ownership mismatch: the DTO must carry the connection/user it is being filed under.
        assertThrows(
                IllegalArgumentException.class,
                () -> store.replaceToolPolicies(
                        conn.id(), user, List.of(policy(prefix + "-pol-x", prefix + "-other", user, "search_tool"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.replaceHeaderSecretRefs(
                        conn.id(),
                        user,
                        List.of(headerRef(prefix + "-ref-x", prefix + "-other", user, "X-Api-Key", "vault-x"))));

        // Duplicate names are rejected case-insensitively for headers (normalised to lower case) and exactly for tools.
        assertThrows(
                IllegalArgumentException.class,
                () -> store.replaceToolPolicies(
                        conn.id(),
                        user,
                        List.of(
                                policy(prefix + "-pol-1", conn.id(), user, "search_tool"),
                                policy(prefix + "-pol-2", conn.id(), user, "search_tool"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.replaceHeaderSecretRefs(
                        conn.id(),
                        user,
                        List.of(
                                headerRef(prefix + "-ref-1", conn.id(), user, "X-Api-Key", "vault-1"),
                                headerRef(prefix + "-ref-2", conn.id(), user, "x-api-key", "vault-2"))));
    }

    private static McpConnectionToolPolicyDto policy(String id, String connectionId, String userId, String toolName) {
        Instant now = Instant.now();
        return new McpConnectionToolPolicyDto(
                id, connectionId, userId, toolName, true, true, "desc", "{\"type\":\"object\"}", "hash", now, 0, now);
    }

    private static Set<String> ids(List<McpConnectionDto> connections) {
        return connections.stream().map(McpConnectionDto::id).collect(Collectors.toSet());
    }

    private static void assertConnection(McpConnectionDto expected, McpConnectionDto actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.userId(), actual.userId());
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.transport(), actual.transport());
        assertEquals(expected.endpoint(), actual.endpoint());
        assertEquals(expected.category(), actual.category());
        assertEquals(expected.enabled(), actual.enabled());
    }

    private static McpConnectionDto connection(String id, String userId, String name, boolean enabled) {
        Instant now = Instant.now();
        return new McpConnectionDto(
                id, userId, name, "HTTP_SSE", "https://example.test/" + id, "NETWORK", enabled, now, now);
    }

    private static McpConnectionHeaderSecretRefDto headerRef(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef) {
        Instant now = Instant.now();
        return new McpConnectionHeaderSecretRefDto(id, connectionId, userId, headerName, vaultKeyRef, now, now);
    }

    private static McpConnectionCleanupFailureDto cleanupFailure(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef, int attempts) {
        Instant now = Instant.now();
        return new McpConnectionCleanupFailureDto(
                id, connectionId, userId, headerName, vaultKeyRef, "cleanup failed", attempts, now, now);
    }
}
