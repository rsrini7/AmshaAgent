package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteMcpConnectionStoreAdapterTest {

    @Inject
    McpConnectionStorePort store;

    @Test
    void mcpConnectionStoreContract() {
        exerciseContract(store, "sqlite-" + UUID.randomUUID());
    }

    static void exerciseContract(McpConnectionStorePort store, String prefix) {
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
    void pendingApprovalsRevisionAndToolPolicies() {
        String prefix = "sqlite-" + UUID.randomUUID();
        String user = prefix + "-user";
        Instant now = Instant.now();
        McpConnectionDto pending = new McpConnectionDto(
                prefix + "-mcp-pending",
                user,
                "pending-srv",
                "HTTP_SSE",
                "https://example.test/mcp",
                "NETWORK",
                false,
                "PENDING_APPROVAL",
                "task-1",
                0,
                null,
                now,
                now);
        store.save(pending);

        assertTrue(
                store.listPendingApprovals().stream().anyMatch(c -> pending.id().equals(c.id())));

        McpConnectionDto approved = new McpConnectionDto(
                pending.id(),
                user,
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
        assertFalse(store.updateStateIfRevision(approved, 5), "stale revision must not apply");
        assertTrue(store.updateStateIfRevision(approved, 0));

        store.replaceToolPolicies(
                pending.id(), user, List.of(policy(prefix + "-pol-1", pending.id(), user, "search_tool")));
        List<McpConnectionToolPolicyDto> policies = store.listToolPolicies(pending.id(), user);
        assertEquals(1, policies.size());
        assertEquals("search_tool", policies.get(0).toolName());

        assertEquals(1, store.setToolEnabled(pending.id(), user, "search_tool", false));
        assertFalse(store.listToolPolicies(pending.id(), user).get(0).enabled());
        assertEquals(0, store.setToolEnabled(pending.id(), user, "absent_tool", false), "unknown tool");
    }

    @Test
    void toolPolicyAndHeaderRefValidationRejectsBadInput() {
        String prefix = "sqlite-" + UUID.randomUUID();
        String user = prefix + "-user";
        McpConnectionDto conn = connection(prefix + "-mcp-val", user, "docs", true);
        store.save(conn);

        // Blank identifiers are rejected before any normalisation happens.
        assertTrue(store.listToolPolicies("", user).isEmpty());
        assertTrue(store.listToolPolicies(conn.id(), "").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.replaceToolPolicies("", user, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.replaceHeaderSecretRefs("", user, List.of()));
        assertEquals(0, store.setToolEnabled("", user, "search_tool", true));

        // A policy or header ref can only be written for a connection that exists.
        String missing = prefix + "-absent";
        assertThrows(IllegalArgumentException.class, () -> store.replaceToolPolicies(missing, user, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.replaceHeaderSecretRefs(missing, user, List.of()));

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
