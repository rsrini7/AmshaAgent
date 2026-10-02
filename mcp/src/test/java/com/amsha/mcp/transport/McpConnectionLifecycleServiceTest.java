package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCategory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpConnectionLifecycleServiceTest {

    @Mock
    Instance<McpConnectionStorePort> stores;

    @Mock
    McpConnectionStorePort store;

    @Mock
    SecretVault secretVault;

    @Mock
    AuditService auditService;

    @Mock
    McpClientService mcpClientService;

    McpConnectionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new McpConnectionLifecycleService();
        service.stores = stores;
        service.secretVault = secretVault;
        service.auditService = auditService;
        service.mcpClientService = mcpClientService;
        lenient().when(stores.stream()).thenAnswer(invocation -> Stream.of(store));
    }

    @Test
    void reconnectEnabledConnectionsRestoresHttpRuntimeState() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        List<McpConnectionHeaderSecretRefDto> refs =
                List.of(headerRef("ref-1", connection.id(), connection.userId(), "authorization", "vault-auth"));
        when(store.listEnabled()).thenReturn(List.of(connection));
        when(store.listToolPolicies(connection.id(), connection.userId())).thenReturn(List.of());
        when(store.listHeaderSecretRefs(connection.id(), connection.userId())).thenReturn(refs);

        int reconnected = service.reconnectEnabledConnections();

        assertEquals(1, reconnected);
        verify(mcpClientService).configureHeaderSecretRefs(connection.name(), connection.userId(), refs);
        verify(mcpClientService)
                .reconnectDurableForUser(
                        connection.name(),
                        connection.userId(),
                        connection.endpoint(),
                        ToolCategory.NETWORK,
                        Map.of(),
                        Set.of());
        verify(auditService)
                .recordMandatory(
                        anyString(),
                        eq(connection.userId()),
                        anyString(),
                        eq(SecurityEventType.MCP_SERVER_CONNECTED),
                        eq("server=docs reconnect=true"));
    }

    @Test
    void reconcilePendingApprovalsClearsRestartOrphans() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.listPendingApprovals()).thenReturn(List.of(connection));
        when(store.updateStateIfRevision(any(McpConnectionDto.class), eq(connection.revision())))
                .thenReturn(true);

        assertEquals(1, service.reconcilePendingApprovals());
        verify(store).updateStateIfRevision(any(McpConnectionDto.class), eq(connection.revision()));
        verify(auditService)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.APPROVAL_DENIED),
                        contains("APPROVAL_LOST_ON_RESTART"));
    }

    @Test
    void reconnectEnabledConnectionsSkipsNonHttpTransports() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "stdio", "STDIO", true);
        when(store.listEnabled()).thenReturn(List.of(connection));

        int reconnected = service.reconnectEnabledConnections();

        assertEquals(0, reconnected);
        verify(mcpClientService, never())
                .reconnectDurableForUser(anyString(), anyString(), anyString(), any(), any(), any());
        verify(mcpClientService, never()).configureHeaderSecretRefs(anyString(), anyString(), any());
    }

    @Test
    void deleteAllByUserIdDeletesDurableRowsRuntimeStateAndVaultRefs() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        McpConnectionHeaderSecretRefDto ref =
                headerRef("ref-1", connection.id(), connection.userId(), "authorization", "vault-auth");
        when(store.listByUserId("user-1")).thenReturn(List.of(connection));
        when(store.listHeaderSecretRefs(connection.id(), "user-1")).thenReturn(List.of(ref));
        when(store.deleteByIdAndUserId(connection.id(), "user-1")).thenReturn(1);

        int deleted = service.deleteAllByUserId("user-1");

        assertEquals(1, deleted);
        verify(store).deleteByIdAndUserId(connection.id(), "user-1");
        verify(mcpClientService).clearHttpConnectionState(connection.name(), "user-1");
        verify(secretVault).remove(ref.vaultKeyRef(), "user-1");
        verify(auditService)
                .recordMandatory(
                        anyString(),
                        eq("user-1"),
                        anyString(),
                        eq(SecurityEventType.MCP_CREDENTIAL_REMOVED),
                        eq("connectionId=mcp-1 header=authorization"));
    }

    @Test
    void deleteAllByUserIdQueuesCleanupFailureWhenVaultRemoveFails() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        McpConnectionHeaderSecretRefDto ref =
                headerRef("ref-1", connection.id(), connection.userId(), "authorization", "vault-auth");
        when(store.listByUserId("user-1")).thenReturn(List.of(connection));
        when(store.listHeaderSecretRefs(connection.id(), "user-1")).thenReturn(List.of(ref));
        when(store.deleteByIdAndUserId(connection.id(), "user-1")).thenReturn(1);
        doThrow(new IllegalStateException("vault offline")).when(secretVault).remove(ref.vaultKeyRef(), "user-1");

        int deleted = service.deleteAllByUserId("user-1");

        assertEquals(1, deleted);
        verify(store).saveCleanupFailure(any(McpConnectionCleanupFailureDto.class));
        verify(auditService)
                .recordMandatory(
                        anyString(),
                        eq("user-1"),
                        anyString(),
                        eq(SecurityEventType.MCP_CREDENTIAL_CLEANUP_FAILED),
                        eq("connectionId=mcp-1 header=authorization attempts=1"));
    }

    @Test
    void retryCleanupFailuresRemovesVaultKeyAndDeletesRetryRecord() {
        McpConnectionCleanupFailureDto failure =
                cleanupFailure("failure-1", "mcp-1", "user-1", "authorization", "vault-auth", 2);
        when(store.listCleanupFailures()).thenReturn(List.of(failure));

        int cleaned = service.retryCleanupFailures();

        assertEquals(1, cleaned);
        verify(secretVault).remove(failure.vaultKeyRef(), failure.userId());
        verify(store).deleteCleanupFailure(failure.id(), failure.userId());
        verify(auditService)
                .recordMandatory(
                        anyString(),
                        eq("user-1"),
                        anyString(),
                        eq(SecurityEventType.MCP_CREDENTIAL_REMOVED),
                        eq("connectionId=mcp-1 header=authorization cleanupRetry=true"));
    }

    @Test
    void testValidateHttpRegistrationRejectsInvalidUrlOrForbiddenHeader() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateHttpRegistration("https://user:pass@example.com/mcp", Map.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateHttpRegistration("https://example.com/mcp", Map.of("Host", "invalid.host")));
    }

    @Test
    void testDeleteByNameAndUserId() {
        McpConnectionDto connection = connection("mcp-2", "user-2", "docs-2", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByNameAndUserId("docs-2", "user-2")).thenReturn(Optional.of(connection));
        when(store.listHeaderSecretRefs(connection.id(), "user-2")).thenReturn(List.of());
        when(store.deleteByIdAndUserId(connection.id(), "user-2")).thenReturn(1);

        assertTrue(service.deleteByNameAndUserId("docs-2", "user-2"));
        verify(mcpClientService).clearHttpConnectionState("docs-2", "user-2");
    }

    @Test
    void testDeleteByIdAndUserId() {
        McpConnectionDto connection = connection("mcp-5", "user-5", "docs-5", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByIdAndUserId("mcp-5", "user-5")).thenReturn(Optional.of(connection));
        when(store.listHeaderSecretRefs("mcp-5", "user-5")).thenReturn(List.of());
        when(store.deleteByIdAndUserId("mcp-5", "user-5")).thenReturn(1);

        assertTrue(service.deleteByIdAndUserId("mcp-5", "user-5"));
    }

    @Test
    void testPersistSuccessfulHttp() {
        when(store.findByNameAndUserId("my-serv", "usr-10")).thenReturn(Optional.empty());

        McpConnectionLifecycleService.PersistedConnection persisted = service.persistSuccessfulHttp(
                "my-serv", "https://example.com/mcp", ToolCategory.NETWORK, "usr-10", Map.of("x-token", "val123"));

        assertNotNull(persisted);
        assertNotNull(persisted.connection());
        assertEquals("my-serv", persisted.connection().name());
        assertEquals(1, persisted.headerSecretRefs().size());
    }

    @Test
    void testListByUserId() {
        McpConnectionDto connection = connection("mcp-3", "user-3", "docs-3", McpTransportType.HTTP_SSE.name(), true);
        when(store.listByUserId("user-3")).thenReturn(List.of(connection));

        List<McpConnectionDto> list = service.listByUserId("user-3");
        assertEquals(1, list.size());
        assertEquals("docs-3", list.get(0).name());
    }

    @Test
    void testValidateHttpRegistrationOutboundPolicyRejection() {
        service.outboundHttpPolicyService = mock(OutboundHttpPolicyService.class);
        when(service.outboundHttpPolicyService.evaluate(
                        eq("https://blocked.example.com/mcp"), eq(OutboundPurpose.MCP_TOOL_CALL), isNull(), isNull()))
                .thenReturn(OutboundPolicyDecision.deny("Denied by policy", OutboundPurpose.MCP_TOOL_CALL, "system"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.validateHttpRegistration("https://blocked.example.com/mcp", Map.of()));
        assertTrue(ex.getMessage().contains("rejected by outbound policy"));
    }

    @Test
    void testPersistSuccessfulHttpKeepingHeaders() {
        McpConnectionDto connection =
                connection("mcp-keep", "user-k", "keep-serv", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByNameAndUserId("keep-serv", "user-k")).thenReturn(Optional.of(connection));
        when(store.listHeaderSecretRefs(connection.id(), "user-k")).thenReturn(List.of());

        var persisted = service.persistSuccessfulHttpKeepingHeaders(
                "keep-serv", "https://example.com/mcp", ToolCategory.NETWORK, "user-k");
        assertNotNull(persisted);
        assertEquals("keep-serv", persisted.connection().name());
    }

    @Test
    void testDeleteNonExistentReturnsFalse() {
        when(store.findByNameAndUserId("missing", "user-x")).thenReturn(Optional.empty());
        when(store.findByIdAndUserId("missing", "user-x")).thenReturn(Optional.empty());

        assertFalse(service.deleteByNameAndUserId("missing", "user-x"));
        assertFalse(service.deleteByIdAndUserId("missing", "user-x"));
    }

    @Test
    void testDeleteAllByUserIdEdgeCases() {
        assertEquals(0, service.deleteAllByUserId(null));
        assertEquals(0, service.deleteAllByUserId(""));

        when(store.listByUserId("user-y")).thenReturn(List.of());
        assertEquals(0, service.deleteAllByUserId("user-y"));
    }

    @Test
    void testClassifyReconnectFailure() throws Exception {
        var classify = McpConnectionLifecycleService.class.getDeclaredMethod(
                "classifyReconnectFailure", RuntimeException.class);
        classify.setAccessible(true);

        assertEquals("HANDSHAKE_FAILED", classify.invoke(null, new McpConnectionException("mcp fail")));
        assertEquals("MCP_RECONNECT_FAILED", classify.invoke(null, new RuntimeException("generic fail")));
    }

    @Test
    void shutdownPreventsTheStartupReconnectTaskFromTouchingPersistence() {
        service.onStartup(null);

        service.onShutdown();

        assertDoesNotThrow(service::runStartupReconciliation);
        verifyNoInteractions(stores, store, secretVault, auditService, mcpClientService);
    }

    // --- store() delegation: reads, writes and the no-store fallbacks ---

    @Test
    void readDelegatesFallBackToEmptyWhenNoStoreIsWired() {
        when(stores.stream()).thenAnswer(invocation -> Stream.empty());

        assertEquals(List.of(), service.listByUserId("user-1"));
        assertTrue(service.findByNameAndUserId("docs", "user-1").isEmpty());
        assertEquals(List.of(), service.listToolPolicies("mcp-1", "user-1"));
        assertEquals(List.of(), service.headerSecretRefs("mcp-1", "user-1"));
        assertEquals(0, service.setToolEnabled("mcp-1", "user-1", "search", true));
        assertTrue(service.updateState("mcp-1", "user-1", "CONNECTED", true, null, null)
                .isEmpty());
        assertFalse(service.deleteByNameAndUserId("docs", "user-1"));
        assertFalse(service.deleteByIdAndUserId("mcp-1", "user-1"));
        assertEquals(0, service.deleteAllByUserId("user-1"));
    }

    @Test
    void persistenceFallsBackToAnEmptyResultWhenNoStoreIsWired() {
        when(stores.stream()).thenAnswer(invocation -> Stream.empty());

        var created = service.persistSuccessfulHttp(
                "docs", "https://example.com/mcp", ToolCategory.NETWORK, "user-1", Map.of("x-token", "value"));
        assertNull(created.connection());
        assertTrue(created.headerSecretRefs().isEmpty());

        var kept = service.persistSuccessfulHttpKeepingHeaders(
                "docs", "https://example.com/mcp", ToolCategory.NETWORK, "user-1");
        assertNull(kept.connection());
        assertTrue(kept.headerSecretRefs().isEmpty());
    }

    @Test
    void listToolPoliciesDelegatesToTheStore() {
        McpConnectionToolPolicyDto policy = toolPolicy("pol-1", "mcp-1", "user-1", "search");
        when(store.listToolPolicies("mcp-1", "user-1")).thenReturn(List.of(policy));

        assertEquals(List.of(policy), service.listToolPolicies("mcp-1", "user-1"));
        verify(store).listToolPolicies("mcp-1", "user-1");
    }

    @Test
    void headerSecretRefsDelegatesToTheStore() {
        McpConnectionHeaderSecretRefDto ref = headerRef("ref-1", "mcp-1", "user-1", "authorization", "vault-a");
        when(store.listHeaderSecretRefs("mcp-1", "user-1")).thenReturn(List.of(ref));

        assertEquals(List.of(ref), service.headerSecretRefs("mcp-1", "user-1"));
        verify(store).listHeaderSecretRefs("mcp-1", "user-1");
    }

    @Test
    void findByNameAndUserIdReturnsTheStoredConnectionOrEmpty() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByNameAndUserId("docs", "user-1")).thenReturn(Optional.of(connection));
        when(store.findByNameAndUserId("missing", "user-1")).thenReturn(Optional.empty());

        assertEquals(Optional.of(connection), service.findByNameAndUserId("docs", "user-1"));
        assertTrue(service.findByNameAndUserId("missing", "user-1").isEmpty());
    }

    @Test
    void replaceToolPoliciesWritesThroughTheStoreAndIsANoOpWithoutOne() {
        McpConnectionToolPolicyDto policy = toolPolicy("pol-1", "mcp-1", "user-1", "search");

        service.replaceToolPolicies("mcp-1", "user-1", List.of(policy));

        when(stores.stream()).thenAnswer(invocation -> Stream.empty());
        service.replaceToolPolicies("mcp-1", "user-1", List.of(policy));

        // The second call must not reach the store: a missing port is a silent no-op, not a second write.
        verify(store, times(1)).replaceToolPolicies("mcp-1", "user-1", List.of(policy));
    }

    @Test
    void setToolEnabledReturnsTheStoreCount() {
        when(store.setToolEnabled("mcp-1", "user-1", "search", false)).thenReturn(1);

        assertEquals(1, service.setToolEnabled("mcp-1", "user-1", "search", false));
    }

    @Test
    void updateStateBumpsTheRevisionOnlyWhenTheCompareAndSetSucceeds() {
        McpConnectionDto current = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByIdAndUserId("mcp-1", "user-1")).thenReturn(Optional.of(current));
        when(store.updateStateIfRevision(any(McpConnectionDto.class), eq(current.revision())))
                .thenReturn(true);

        Optional<McpConnectionDto> updated = service.updateState("mcp-1", "user-1", "CONNECTED", true, "task-9", null);

        McpConnectionDto value = updated.orElseThrow();
        assertEquals("CONNECTED", value.state());
        assertEquals("task-9", value.approvalTaskId());
        assertEquals(current.revision() + 1, value.revision());
        assertEquals(current.createdAt(), value.createdAt());
    }

    @Test
    void updateStateReturnsEmptyWhenTheCompareAndSetLosesTheRace() {
        McpConnectionDto current = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByIdAndUserId("mcp-1", "user-1")).thenReturn(Optional.of(current));
        when(store.updateStateIfRevision(any(McpConnectionDto.class), eq(current.revision())))
                .thenReturn(false);

        assertTrue(service.updateState("mcp-1", "user-1", "CONNECTED", true, null, null)
                .isEmpty());
    }

    @Test
    void updateStateReturnsEmptyWhenTheConnectionIsAbsent() {
        when(store.findByIdAndUserId("missing", "user-1")).thenReturn(Optional.empty());

        assertTrue(service.updateState("missing", "user-1", "CONNECTED", true, null, null)
                .isEmpty());
    }

    @Test
    void deleteConnectionReturnsFalseWhenTheStoreDeletesNothing() {
        McpConnectionDto connection = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByNameAndUserId("docs", "user-1")).thenReturn(Optional.of(connection));
        when(store.listHeaderSecretRefs(connection.id(), "user-1")).thenReturn(List.of());
        when(store.deleteByIdAndUserId(connection.id(), "user-1")).thenReturn(0);

        assertFalse(service.deleteByNameAndUserId("docs", "user-1"));
        verify(mcpClientService, never()).clearHttpConnectionState(anyString(), anyString());
    }

    @Test
    void deleteAllByUserIdSkipsConnectionsThatFailToDelete() {
        McpConnectionDto kept = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.listByUserId("user-1")).thenReturn(List.of(kept));
        when(store.listHeaderSecretRefs(kept.id(), "user-1")).thenReturn(List.of());
        when(store.deleteByIdAndUserId(kept.id(), "user-1")).thenReturn(0);

        assertEquals(0, service.deleteAllByUserId("user-1"));
    }

    @Test
    void persistSuccessfulHttpWithoutHeadersKeepsNoSecretRefs() {
        McpConnectionDto existing = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        when(store.findByNameAndUserId("docs", "user-1")).thenReturn(Optional.of(existing));
        when(store.listHeaderSecretRefs("mcp-1", "user-1")).thenReturn(List.of());

        var persisted =
                service.persistSuccessfulHttp("docs", "https://example.com/mcp", ToolCategory.NETWORK, "user-1");

        assertTrue(persisted.headerSecretRefs().isEmpty());
        verifyNoInteractions(secretVault);
    }

    @Test
    void persistSuccessfulHttpRotatesAnExistingHeaderSecretInsteadOfConfiguringIt() {
        McpConnectionDto existing = connection("mcp-1", "user-1", "docs", McpTransportType.HTTP_SSE.name(), true);
        // Same normalised name, and the vault key is deterministic, so this is the same slot being rotated.
        McpConnectionHeaderSecretRefDto oldRef =
                headerRef("ref-old", "mcp-1", "user-1", "x-token", "mcp:mcp-1:header:x-token");
        when(store.findByNameAndUserId("docs", "user-1")).thenReturn(Optional.of(existing));
        when(store.listHeaderSecretRefs("mcp-1", "user-1")).thenReturn(List.of(oldRef));

        var persisted = service.persistSuccessfulHttp(
                "docs", "https://example.com/mcp", ToolCategory.NETWORK, "user-1", Map.of("X-Token", "new-value"));

        assertEquals(1, persisted.headerSecretRefs().size());
        assertEquals("x-token", persisted.headerSecretRefs().get(0).headerName());
        verify(auditService)
                .recordMandatory(
                        anyString(),
                        eq("user-1"),
                        anyString(),
                        eq(SecurityEventType.MCP_CREDENTIAL_ROTATED),
                        eq("connectionId=mcp-1 header=x-token"));
        // The rotated ref keeps the same vault key, so the old ref is not torn down again.
        verify(secretVault, never()).remove(anyString(), anyString());
    }

    @Test
    void validateHttpRegistrationFallsThroughWhenOutboundPolicyAllowsTheEndpoint() {
        service.outboundHttpPolicyService = mock(OutboundHttpPolicyService.class);
        when(service.outboundHttpPolicyService.evaluate(
                        eq("https://example.com/mcp"), eq(OutboundPurpose.MCP_TOOL_CALL), isNull(), isNull()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MCP_TOOL_CALL, "system"));

        assertDoesNotThrow(
                () -> service.validateHttpRegistration("https://example.com/mcp", Map.of("x-token", "value")));
    }

    private static McpConnectionToolPolicyDto toolPolicy(
            String id, String connectionId, String userId, String toolName) {
        Instant now = Instant.now();
        return new McpConnectionToolPolicyDto(
                id, connectionId, userId, toolName, true, true, "description", "{}", "hash", now, 0L, now);
    }

    private static McpConnectionDto connection(
            String id, String userId, String name, String transport, boolean enabled) {
        Instant now = Instant.now();
        return new McpConnectionDto(
                id,
                userId,
                name,
                transport,
                "https://example.test/" + id,
                ToolCategory.NETWORK.name(),
                enabled,
                now,
                now);
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
