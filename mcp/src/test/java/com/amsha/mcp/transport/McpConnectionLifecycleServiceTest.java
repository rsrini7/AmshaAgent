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
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
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
                .connectForUserWithEnabledTools(
                        connection.name(),
                        connection.userId(),
                        connection.endpoint(),
                        ToolCategory.NETWORK,
                        Map.of(),
                        true,
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
                .connectForUserWithEnabledTools(
                        anyString(), anyString(), anyString(), any(), any(), anyBoolean(), any());
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
