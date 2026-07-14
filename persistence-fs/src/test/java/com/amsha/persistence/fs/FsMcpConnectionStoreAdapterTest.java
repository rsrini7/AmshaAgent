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
