package com.amsha.persistence.postgres;

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
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
class PostgresMcpConnectionStoreAdapterTest {

    @Inject
    McpConnectionStorePort store;

    @Test
    void mcpConnectionStoreContract() {
        String prefix = "postgres-" + UUID.randomUUID();
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
    void testPendingApprovalsRevisionsAndToolPolicies() {
        String prefix = "postgres-adv-" + UUID.randomUUID();
        String userId = prefix + "-user";
        Instant now = Instant.now();

        McpConnectionDto conn = new McpConnectionDto(
                prefix + "-conn-1",
                userId,
                "server-adv",
                "HTTP_SSE",
                "https://example.com/mcp",
                "NETWORK",
                true,
                "PENDING_APPROVAL",
                "task-123",
                1L,
                null,
                now,
                now);
        store.save(conn);

        // listPendingApprovals
        List<McpConnectionDto> pending = store.listPendingApprovals();
        assertTrue(pending.stream().anyMatch(c -> c.id().equals(conn.id())));

        // updateStateIfRevision
        McpConnectionDto approved = new McpConnectionDto(
                conn.id(),
                conn.userId(),
                conn.name(),
                conn.transport(),
                conn.endpoint(),
                conn.category(),
                true,
                "READY",
                null,
                2L,
                null,
                conn.createdAt(),
                Instant.now());
        assertFalse(store.updateStateIfRevision(approved, 5L));
        assertTrue(store.updateStateIfRevision(approved, 1L));

        // tool policies
        var toolPolicy = new McpConnectionToolPolicyDto(
                UUID.randomUUID().toString(),
                conn.id(),
                userId,
                "tool_a",
                true,
                true,
                "Tool A description",
                "{\"type\":\"object\"}",
                "hash-123",
                now,
                0L,
                now);
        store.replaceToolPolicies(conn.id(), userId, List.of(toolPolicy));

        List<McpConnectionToolPolicyDto> policies = store.listToolPolicies(conn.id(), userId);
        assertEquals(1, policies.size());
        assertEquals("tool_a", policies.get(0).toolName());
        assertTrue(policies.get(0).enabled());

        int modified = store.setToolEnabled(conn.id(), userId, "tool_a", false);
        assertEquals(1, modified);
        assertFalse(store.listToolPolicies(conn.id(), userId).get(0).enabled());
    }

    @Test
    void toolPolicyAndHeaderRefValidationRejectsBadInput() {
        String prefix = "pg-" + UUID.randomUUID();
        String user = prefix + "-user";
        McpConnectionDto conn = connection(prefix + "-mcp-val", user, "docs", true);
        store.save(conn);

        // Blank identifiers are rejected before any normalisation happens.
        assertTrue(store.listToolPolicies("", user).isEmpty());
        assertTrue(store.listToolPolicies(conn.id(), "").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.replaceToolPolicies("", user, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.replaceHeaderSecretRefs("", user, List.of()));
        assertEquals(0, store.setToolEnabled("", user, "tool_a", true));

        // A policy or header ref can only be written for a connection that exists.
        String missing = prefix + "-absent";
        assertThrows(IllegalArgumentException.class, () -> store.replaceToolPolicies(missing, user, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.replaceHeaderSecretRefs(missing, user, List.of()));

        // Ownership mismatch: the DTO must carry the connection/user it is being filed under.
        assertThrows(
                IllegalArgumentException.class,
                () -> store.replaceToolPolicies(
                        conn.id(), user, List.of(policy(prefix + "-pol-x", prefix + "-other", user, "tool_a"))));
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
                                policy(prefix + "-pol-1", conn.id(), user, "tool_a"),
                                policy(prefix + "-pol-2", conn.id(), user, "tool_a"))));
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
