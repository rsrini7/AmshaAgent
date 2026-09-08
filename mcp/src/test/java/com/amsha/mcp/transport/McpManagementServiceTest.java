package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.mcp.transport.McpManagementServiceModels.ApprovalExecution;
import com.amsha.mcp.transport.McpManagementServiceModels.ConnectionSnapshot;
import com.amsha.mcp.transport.McpManagementServiceModels.DisconnectResult;
import com.amsha.mcp.transport.McpManagementServiceModels.DiscoveryPreview;
import com.amsha.mcp.transport.McpManagementServiceModels.ToolPreview;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.tool.api.ToolCategory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpManagementServiceTest {

    @Mock
    McpClientService clientService;

    @Mock
    McpConnectionLifecycleService lifecycleService;

    @Mock
    DynamicToolCapabilityRegistry registry;

    @Mock
    AuditService auditService;

    @Mock
    McpApprovalService approvalService;

    McpManagementService service;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        service = new McpManagementService(clientService, lifecycleService, registry, auditService, approvalService);
    }

    private McpConnectionDto connection(String name, String state) {
        return connection(name, state, "id-1", "user-1", 0L, null, true);
    }

    private McpConnectionDto connection(
            String name,
            String state,
            String id,
            String userId,
            long revision,
            String approvalTaskId,
            boolean enabled) {
        return new McpConnectionDto(
                id,
                userId,
                name,
                "HTTP_SSE",
                "https://example.test/mcp",
                "NETWORK",
                enabled,
                state,
                approvalTaskId,
                revision,
                null,
                now,
                now);
    }

    private McpConnectionToolPolicyDto policy(String name, boolean enabled) {
        return new McpConnectionToolPolicyDto(
                "policy-1", "id-1", "user-1", name, true, enabled, "Search", "{}", "hash", now, 0, now);
    }

    // ==================== Request Operations & Validation Tests ====================

    @Test
    void testRequestDiscoverySuccess() {
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.DISCOVER),
                        eq("server"),
                        eq("https://example.com/mcp"),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-1", "DISCOVER", "server", "https://example.com/mcp", null, now, null));

        var pending = service.requestDiscovery("user-1", "server", "https://example.com/mcp", Map.of("X-Key", "v"));
        assertEquals("task-1", pending.taskId());
        verify(lifecycleService).validateHttpRegistration(eq("https://example.com/mcp"), any());
    }

    @Test
    void testRequestDiscoveryInvalidNameThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.requestDiscovery("user-1", "   ", "https://example.com/mcp", Map.of()));
    }

    @Test
    void testRequestConnectWithoutExistingConnection() {
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.empty());
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.CONNECT),
                        eq("server"),
                        eq("https://example.com/mcp"),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-2", "CONNECT", "server", "https://example.com/mcp", null, now, null));

        var pending = service.requestConnect("user-1", "server", "https://example.com/mcp", Map.of());
        assertEquals("task-2", pending.taskId());
        verify(lifecycleService, never())
                .updateState(any(), any(), eq(McpManagementService.STATE_PENDING_APPROVAL), anyBoolean(), any(), any());
    }

    @Test
    void testRequestConnectWithExistingConnectionMarksPending() {
        McpConnectionDto existing = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.CONNECT),
                        eq("server"),
                        eq("https://example.com/mcp"),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-2", "CONNECT", "server", "https://example.com/mcp", null, now, null));

        var pending = service.requestConnect("user-1", "server", "https://example.com/mcp", Map.of());
        assertEquals("task-2", pending.taskId());
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_PENDING_APPROVAL, true, "task-2", null);
    }

    @Test
    void testRequestReconnectSuccess() {
        McpConnectionDto existing = connection("server", "DISCONNECTED", "id-1", "user-1", 3L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.RECONNECT),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        eq(3L)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-3", "RECONNECT", "server", "https://example.test/mcp", null, now, 3L));

        var pending = service.requestReconnect("user-1", "server", 3L);
        assertEquals("task-3", pending.taskId());
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_PENDING_APPROVAL, true, "task-3", null);

        // Overloaded variant without revision
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.RECONNECT),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        eq(null)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-3b", "RECONNECT", "server", "https://example.test/mcp", null, now, null));
        var pending2 = service.requestReconnect("user-1", "server");
        assertEquals("task-3b", pending2.taskId());
    }

    @Test
    void testRequestReconnectNotFoundThrows() {
        when(lifecycleService.findByNameAndUserId("missing", "user-1")).thenReturn(Optional.empty());
        assertThrows(McpConnectionException.class, () -> service.requestReconnect("user-1", "missing"));
    }

    @Test
    void testRequestReconnectRevisionMismatchThrows() {
        McpConnectionDto existing = connection("server", "DISCONNECTED", "id-1", "user-1", 3L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        assertThrows(McpRevisionConflictException.class, () -> service.requestReconnect("user-1", "server", 4L));
    }

    @Test
    void testRequestRotateHeadersSuccess() {
        McpConnectionDto existing = connection("server", "CONNECTED", "id-1", "user-1", 1L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.ROTATE_HEADERS),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        eq(1L)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-4", "ROTATE_HEADERS", "server", "https://example.test/mcp", null, now, 1L));

        var pending = service.requestRotateHeaders("user-1", "server", Map.of("X-Key", "new-v"), 1L);
        assertEquals("task-4", pending.taskId());

        // Overloaded variant without revision
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.ROTATE_HEADERS),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        eq(null)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-4b", "ROTATE_HEADERS", "server", "https://example.test/mcp", null, now, null));
        var pending2 = service.requestRotateHeaders("user-1", "server", Map.of("X-Key", "new-v"));
        assertEquals("task-4b", pending2.taskId());
    }

    @Test
    void testRequestRotateHeadersRevisionMismatchThrows() {
        McpConnectionDto existing = connection("server", "CONNECTED", "id-1", "user-1", 1L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        assertThrows(
                McpRevisionConflictException.class,
                () -> service.requestRotateHeaders("user-1", "server", Map.of("X-Key", "new-v"), 2L));
    }

    @Test
    void testRequestReconfigureSuccess() {
        McpConnectionDto existing = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.RECONFIGURE),
                        eq("server"),
                        eq("https://example.test/new-mcp"),
                        any(),
                        any(),
                        eq(2L),
                        eq(false)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-5", "RECONFIGURE", "server", "https://example.test/new-mcp", null, now, 2L));

        var pending = service.requestReconfigure(
                "user-1", "server", "https://example.test/new-mcp", Map.of("Auth", "Bearer abc"), 2L);
        assertEquals("task-5", pending.taskId());

        // Overloaded with blank endpoint (falls back to existing endpoint)
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.RECONFIGURE),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        eq(2L),
                        eq(true)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-5b", "RECONFIGURE", "server", "https://example.test/mcp", null, now, 2L));
        var pending2 = service.requestReconfigure("user-1", "server", "  ", null, true, 2L);
        assertEquals("task-5b", pending2.taskId());
    }

    @Test
    void testRequestReconfigureClearCredentialsWithNonEmptyHeadersThrows() {
        McpConnectionDto existing = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.requestReconfigure(
                        "user-1", "server", "https://example.test/mcp", Map.of("H", "V"), true, 2L));
        assertTrue(ex.getMessage().contains("MCP clearCredentials cannot include replacement headers"));
    }

    @Test
    void testRequestEnableToolSuccess() {
        McpConnectionDto existing = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.ENABLE_TOOL),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        eq("search"),
                        eq(2L)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-6", "ENABLE_TOOL", "server", "https://example.test/mcp", "search", now, 2L));

        var pending = service.requestEnableTool("user-1", "server", "search", 2L);
        assertEquals("task-6", pending.taskId());

        // Overloaded variant without revision
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.ENABLE_TOOL),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        eq("search"),
                        eq(null)))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-6b", "ENABLE_TOOL", "server", "https://example.test/mcp", "search", now, null));
        var pending2 = service.requestEnableTool("user-1", "server", "search");
        assertEquals("task-6b", pending2.taskId());
    }

    @Test
    void testRequestEnableToolBlankToolNameThrows() {
        McpConnectionDto existing = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(existing));
        assertThrows(IllegalArgumentException.class, () -> service.requestEnableTool("user-1", "server", "   "));
    }

    // ==================== Approve Execution Tests ====================

    @Test
    void testApproveDiscover() throws Exception {
        McpApprovalService.ApprovedOperation op = new McpApprovalService.ApprovedOperation(
                "task-disc",
                McpApprovalService.Operation.DISCOVER,
                "server",
                "https://example.test/mcp",
                Map.of(),
                null,
                "receipt-1",
                null,
                false);
        when(approvalService.approve("user-1", "task-disc")).thenReturn(op);
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "receipt-1"))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("calc", "Calculator", "{}")));

        var execution = service.approve("user-1", "task-disc");
        assertEquals("task-disc", execution.taskId());
        assertEquals("DISCOVER", execution.operation());
        assertTrue(execution.result() instanceof DiscoveryPreview);
        var preview = (DiscoveryPreview) execution.result();
        assertEquals("server", preview.name());
        assertEquals(1, preview.tools().size());
        verify(clientService).clearHttpConnectionState("server", "user-1");
    }

    @Test
    void testApproveConnect() throws Exception {
        McpApprovalService.ApprovedOperation op = new McpApprovalService.ApprovedOperation(
                "task-conn",
                McpApprovalService.Operation.CONNECT,
                "server",
                "https://example.test/mcp",
                Map.of(),
                null,
                "receipt-2",
                null,
                false);
        when(approvalService.approve("user-1", "task-conn")).thenReturn(op);

        McpConnectionDto conn = connection("server", "CONNECTED");
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "receipt-2"))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1", Map.of()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(conn, List.of()));
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", false)));

        var execution = service.approve("user-1", "task-conn");
        assertEquals("CONNECT", execution.operation());
        assertTrue(execution.result() instanceof ConnectionSnapshot);
    }

    @Test
    void testApproveReconnect() throws Exception {
        McpApprovalService.ApprovedOperation op = new McpApprovalService.ApprovedOperation(
                "task-rec",
                McpApprovalService.Operation.RECONNECT,
                "server",
                "https://example.test/mcp",
                Map.of(),
                null,
                "receipt-3",
                0L,
                false);
        when(approvalService.approve("user-1", "task-rec")).thenReturn(op);

        McpConnectionDto pendingConn = connection("server", "PENDING_APPROVAL", "id-1", "user-1", 1L, "task-rec", true);
        McpConnectionDto connectedConn = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1"))
                .thenReturn(Optional.of(pendingConn))
                .thenReturn(Optional.of(connectedConn));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", true)));
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "receipt-3",
                        OutboundPurpose.MCP_CONNECT,
                        Set.of("search")))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));

        var execution = service.approve("user-1", "task-rec");
        assertEquals("RECONNECT", execution.operation());
        assertTrue(execution.result() instanceof ConnectionSnapshot);
    }

    @Test
    void testApproveReconfigure() throws Exception {
        McpApprovalService.ApprovedOperation op = new McpApprovalService.ApprovedOperation(
                "task-recfg",
                McpApprovalService.Operation.RECONFIGURE,
                "server",
                "https://example.test/new-mcp",
                Map.of("X-Auth", "token"),
                null,
                "receipt-4",
                0L,
                false);
        when(approvalService.approve("user-1", "task-recfg")).thenReturn(op);

        McpConnectionDto pendingConn =
                connection("server", "PENDING_APPROVAL", "id-1", "user-1", 1L, "task-recfg", true);
        McpConnectionDto reconfiguredConn = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1"))
                .thenReturn(Optional.of(pendingConn))
                .thenReturn(Optional.of(reconfiguredConn));
        when(lifecycleService.headerSecretRefs("id-1", "user-1")).thenReturn(List.of());
        when(clientService.discoverForUser(
                        "server",
                        "user-1",
                        "https://example.test/new-mcp",
                        ToolCategory.NETWORK,
                        Map.of("X-Auth", "token"),
                        "receipt-4"))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));
        when(lifecycleService.persistSuccessfulHttp(
                        "server",
                        "https://example.test/new-mcp",
                        ToolCategory.NETWORK,
                        "user-1",
                        Map.of("X-Auth", "token")))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(reconfiguredConn, List.of()));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", false)));

        var execution = service.approve("user-1", "task-recfg");
        assertEquals("RECONFIGURE", execution.operation());
        assertTrue(execution.result() instanceof ConnectionSnapshot);
    }

    @Test
    void testApproveRotateHeaders() throws Exception {
        McpApprovalService.ApprovedOperation op = new McpApprovalService.ApprovedOperation(
                "task-rot",
                McpApprovalService.Operation.ROTATE_HEADERS,
                "server",
                "https://example.test/mcp",
                Map.of("X-New", "val"),
                null,
                "receipt-5",
                0L,
                false);
        when(approvalService.approve("user-1", "task-rot")).thenReturn(op);

        McpConnectionDto pendingConn = connection("server", "PENDING_APPROVAL", "id-1", "user-1", 1L, "task-rot", true);
        McpConnectionDto rotatedConn = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1"))
                .thenReturn(Optional.of(pendingConn))
                .thenReturn(Optional.of(pendingConn))
                .thenReturn(Optional.of(rotatedConn));
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1", Map.of("X-New", "val")))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(rotatedConn, List.of()));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", true)));
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "receipt-5",
                        OutboundPurpose.MCP_CONNECT,
                        Set.of("search")))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));

        var execution = service.approve("user-1", "task-rot");
        assertEquals("ROTATE_HEADERS", execution.operation());
        assertTrue(execution.result() instanceof ConnectionSnapshot);
    }

    @Test
    void testApproveEnableTool() throws Exception {
        McpApprovalService.ApprovedOperation op = new McpApprovalService.ApprovedOperation(
                "task-en",
                McpApprovalService.Operation.ENABLE_TOOL,
                "server",
                "https://example.test/mcp",
                Map.of(),
                "search",
                "receipt-6",
                0L,
                false);
        when(approvalService.approve("user-1", "task-en")).thenReturn(op);

        McpConnectionDto pendingConn = connection("server", "PENDING_APPROVAL", "id-1", "user-1", 1L, "task-en", true);
        McpConnectionDto enabledConn = connection("server", "CONNECTED", "id-1", "user-1", 2L, null, true);
        when(lifecycleService.findByNameAndUserId("server", "user-1"))
                .thenReturn(Optional.of(pendingConn))
                .thenReturn(Optional.of(pendingConn))
                .thenReturn(Optional.of(enabledConn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "search", true)).thenReturn(1);
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", true)));
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "receipt-6",
                        OutboundPurpose.MCP_TOOL_ENABLE,
                        Set.of("search")))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));

        var execution = service.approve("user-1", "task-en");
        assertEquals("ENABLE_TOOL", execution.operation());
        assertTrue(execution.result() instanceof ConnectionSnapshot);
    }

    // ==================== Reject Approval Tests ====================

    @Test
    void testRejectApprovalWhenApprovalNotFoundReturnsFalse() {
        when(approvalService.reject("user-1", "task-x")).thenReturn(false);
        boolean result = service.rejectApproval("user-1", "task-x");
        assertFalse(result);
        verify(lifecycleService, never()).updateState(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void testRejectApprovalForEnabledConnectionClearsToDisconnected() {
        when(approvalService.reject("user-1", "task-1")).thenReturn(true);
        when(approvalService.find("user-1", "task-1"))
                .thenReturn(Optional.of(new McpApprovalService.PendingApproval(
                        "task-1", "CONNECT", "server", "https://example.test/mcp", null, now, null)));
        McpConnectionDto enabledConn = connection("server", "PENDING_APPROVAL", "id-1", "user-1", 1L, "task-1", true);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(enabledConn));

        boolean rejected = service.rejectApproval("user-1", "task-1");
        assertTrue(rejected);
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_DISCONNECTED, true, null, "APPROVAL_DENIED");
        verify(auditService)
                .recordMandatory(
                        "mcp-management", "user-1", "system", SecurityEventType.APPROVAL_DENIED, "task=task-1");
    }

    @Test
    void testRejectApprovalForDisabledConnectionClearsToPaused() {
        when(approvalService.reject("user-1", "task-1")).thenReturn(true);
        when(approvalService.find("user-1", "task-1"))
                .thenReturn(Optional.of(new McpApprovalService.PendingApproval(
                        "task-1", "CONNECT", "server", "https://example.test/mcp", null, now, null)));
        McpConnectionDto pausedConn = connection("server", "PENDING_APPROVAL", "id-1", "user-1", 1L, "task-1", false);
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(pausedConn));

        boolean rejected = service.rejectApproval("user-1", "task-1");
        assertTrue(rejected);
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_PAUSED, false, null, "APPROVAL_DENIED");
    }

    // ==================== Query Operations Tests ====================

    @Test
    void testListConnectionsForUser() {
        when(lifecycleService.listByUserId("user-1"))
                .thenReturn(List.of(connection("srv-1", "CONNECTED"), connection("srv-2", "DISCONNECTED")));
        when(lifecycleService.listToolPolicies(any(), eq("user-1"))).thenReturn(List.of(policy("t1", true)));

        var list = service.list("user-1");
        assertEquals(2, list.size());
        assertEquals("srv-1", list.get(0).name());
        assertEquals("srv-2", list.get(1).name());

        // Blank or null user ID returns empty list
        assertTrue(service.list(null).isEmpty());
        assertTrue(service.list("   ").isEmpty());
    }

    @Test
    void testPendingApprovals() {
        when(approvalService.list("user-1"))
                .thenReturn(List.of(new McpApprovalService.PendingApproval(
                        "t1", "CONNECT", "srv", "https://example.test", null, now, null)));
        assertEquals(1, service.pendingApprovals("user-1").size());
    }

    @Test
    void testListToolsSuccess() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        McpConnectionToolPolicyDto availablePolicy = new McpConnectionToolPolicyDto(
                "p1", "id-1", "user-1", "t1", true, true, "desc", "{}", "h", now, 0, now);
        McpConnectionToolPolicyDto unavailablePolicy = new McpConnectionToolPolicyDto(
                "p2", "id-1", "user-1", "t2", false, false, "desc2", "{}", "h2", now, 0, now);
        when(lifecycleService.listToolPolicies("id-1", "user-1"))
                .thenReturn(List.of(availablePolicy, unavailablePolicy));

        var tools = service.listTools("user-1", "server");
        assertEquals(1, tools.size());
        assertEquals("t1", tools.get(0).name());
        assertEquals("desc", tools.get(0).description());
    }

    @Test
    void testListToolsConnectionNotFoundThrows() {
        when(lifecycleService.findByNameAndUserId("ghost", "user-1")).thenReturn(Optional.empty());
        assertThrows(McpConnectionException.class, () -> service.listTools("user-1", "ghost"));
    }

    // ==================== Direct Lifecycle & Edge Cases Tests ====================

    @Test
    void testDiscoverWithApproval() throws Exception {
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "token-1"))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("calc", "Calc", "{}")));

        var preview = service.discoverWithApproval("user-1", "server", "https://example.test/mcp", Map.of(), "token-1");
        assertEquals(1, preview.tools().size());
        verify(clientService).clearHttpConnectionState("server", "user-1");
    }

    @Test
    void testConnectNewPersistenceBackendUnavailableThrows() throws Exception {
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), (String) null))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1", Map.of()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        McpConnectionException ex = assertThrows(
                McpConnectionException.class,
                () -> service.connectNew("user-1", "server", "https://example.test/mcp", Map.of()));
        assertTrue(ex.getMessage().contains("MCP persistence backend is unavailable"));
        verify(clientService).clearHttpConnectionState("server", "user-1");
    }

    @Test
    void testConnectNewWithApproval() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "token-app"))
                .thenReturn(List.of());
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1", Map.of()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(conn, List.of()));
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));

        var snapshot =
                service.connectNewWithApproval("user-1", "server", "https://example.test/mcp", Map.of(), "token-app");
        assertNotNull(snapshot);
        verify(lifecycleService).updateState("id-1", "user-1", McpManagementService.STATE_CONNECTED, true, null, null);
    }

    @Test
    void testRegisterFromApprovedCheckpoint() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.COMPUTE, Map.of(), "chk-token"))
                .thenReturn(List.of());
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.COMPUTE, "user-1", Map.of()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(conn, List.of()));
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));

        var snapshot = service.registerFromApprovedCheckpoint(
                "user-1", "server", "https://example.test/mcp", Map.of(), "chk-token", ToolCategory.COMPUTE);
        assertNotNull(snapshot);
    }

    @Test
    void testConnectHandshakeFailureSetsDegradedState() throws Exception {
        McpConnectionDto conn = connection("server", "DISCONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());
        doThrow(new McpConnectionException("TLS failed"))
                .when(clientService)
                .connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        (String) null,
                        OutboundPurpose.MCP_CONNECT,
                        Set.of());

        assertThrows(McpConnectionException.class, () -> service.connect("user-1", "server"));
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_DEGRADED, true, null, "HANDSHAKE_FAILED");
    }

    @Test
    void testConnectGenericFailureSetsDegradedState() throws Exception {
        McpConnectionDto conn = connection("server", "DISCONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());
        doThrow(new IllegalStateException("Network unreachable"))
                .when(clientService)
                .connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        (String) null,
                        OutboundPurpose.MCP_CONNECT,
                        Set.of());

        assertThrows(IllegalStateException.class, () -> service.connect("user-1", "server"));
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_DEGRADED, true, null, "MCP_OPERATION_FAILED");
    }

    @Test
    void testConnectWithApprovalVariants() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "app-tok",
                        OutboundPurpose.MCP_CONNECT,
                        Set.of()))
                .thenReturn(List.of());

        var snapshot1 = service.connectWithApproval("user-1", "server", "app-tok");
        assertNotNull(snapshot1);

        var snapshot2 = service.connectWithApproval("user-1", "server", "app-tok", 0L);
        assertNotNull(snapshot2);
    }

    @Test
    void testEnableToolDirectSuccess() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "calc", true)).thenReturn(1);
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("calc", true)));
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        (String) null,
                        OutboundPurpose.MCP_CONNECT,
                        Set.of("calc")))
                .thenReturn(List.of());

        var snapshot1 = service.enableTool("user-1", "server", "calc");
        assertNotNull(snapshot1);

        var snapshot2 = service.enableTool("user-1", "server", "calc", 0L);
        assertNotNull(snapshot2);
    }

    @Test
    void testEnableToolNotFoundInPoliciesThrows() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "nonexistent", true))
                .thenReturn(0);

        McpConnectionException ex =
                assertThrows(McpConnectionException.class, () -> service.enableTool("user-1", "server", "nonexistent"));
        assertTrue(ex.getMessage().contains("MCP tool was not discovered: nonexistent"));
    }

    @Test
    void testEnableToolWithApprovalSuccessAndFailureRollback() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "calc", true)).thenReturn(1);
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("calc", true)));
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "tok-en",
                        OutboundPurpose.MCP_TOOL_ENABLE,
                        Set.of("calc")))
                .thenReturn(List.of());

        var snapshot = service.enableToolWithApproval("user-1", "server", "calc", "tok-en");
        assertNotNull(snapshot);

        // Failure rollback path
        doThrow(new RuntimeException("connect error"))
                .when(clientService)
                .connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "tok-en",
                        OutboundPurpose.MCP_TOOL_ENABLE,
                        Set.of("calc"));

        assertThrows(
                RuntimeException.class, () -> service.enableToolWithApproval("user-1", "server", "calc", "tok-en", 0L));
        verify(lifecycleService).setToolEnabled("id-1", "user-1", "calc", false);
    }

    @Test
    void testEnableToolWithApprovalToolNotFoundThrows() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "missing", true)).thenReturn(0);

        assertThrows(
                McpConnectionException.class,
                () -> service.enableToolWithApproval("user-1", "server", "missing", "tok"));
    }

    @Test
    void testDisableToolSuccess() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "calc", false)).thenReturn(1);
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());

        var snapshot = service.disableTool("user-1", "server", "calc");
        assertNotNull(snapshot);
        verify(registry).drainAndDeregister(eq("user-1"), eq("calc"), any());

        // Overloaded variant with revision
        var snapshot2 = service.disableTool("user-1", "server", "calc", 0L);
        assertNotNull(snapshot2);
    }

    @Test
    void testDisableToolNotFoundThrows() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "missing", false))
                .thenReturn(0);

        assertThrows(McpConnectionException.class, () -> service.disableTool("user-1", "server", "missing"));
    }

    @Test
    void testRotateHeadersDirectAndWithApproval() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1", Map.of("H", "V")))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(conn, List.of()));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        (String) null,
                        OutboundPurpose.MCP_CONNECT,
                        Set.of()))
                .thenReturn(List.of());

        var snapshot = service.rotateHeaders("user-1", "server", Map.of("H", "V"));
        assertNotNull(snapshot);

        // With revision
        var snapshot2 = service.rotateHeaders("user-1", "server", Map.of("H", "V"), 0L);
        assertNotNull(snapshot2);

        // Rotate with approval
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        "app-tok",
                        OutboundPurpose.MCP_CONNECT,
                        Set.of()))
                .thenReturn(List.of());
        var snapshot3 = service.rotateHeadersWithApproval("user-1", "server", Map.of("H", "V"), "app-tok");
        assertNotNull(snapshot3);

        var snapshot4 = service.rotateHeadersWithApproval("user-1", "server", Map.of("H", "V"), "app-tok", 0L);
        assertNotNull(snapshot4);
    }

    @Test
    void testReconfigureWithApprovalKeepingHeaders() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.headerSecretRefs("id-1", "user-1")).thenReturn(List.of());
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "tok-recfg"))
                .thenReturn(List.of());
        when(lifecycleService.persistSuccessfulHttpKeepingHeaders(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1"))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(conn, List.of()));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());

        var snapshot = service.reconfigureWithApproval(
                "user-1", "server", "https://example.test/mcp", Map.of(), "tok-recfg", 0L);
        assertNotNull(snapshot);
        verify(lifecycleService).persistSuccessfulHttpKeepingHeaders(any(), any(), any(), any());
    }

    @Test
    void testReconfigureWithApprovalClearCredentialsRestoresOnFailure() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        McpConnectionHeaderSecretRefDto previousRef =
                new McpConnectionHeaderSecretRefDto("ref-1", "id-1", "user-1", "authorization", "secret-1", now, now);
        when(lifecycleService.headerSecretRefs("id-1", "user-1")).thenReturn(List.of(previousRef));

        doThrow(new RuntimeException("Discovery failed"))
                .when(clientService)
                .discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "tok-fail");

        assertThrows(
                RuntimeException.class,
                () -> service.reconfigureWithApproval(
                        "user-1", "server", "https://example.test/mcp", Map.of(), true, "tok-fail", 0L));

        verify(clientService).configureHeaderSecretRefs("server", "user-1", List.of());
        verify(clientService).configureHeaderSecretRefs("server", "user-1", List.of(previousRef));
        verify(lifecycleService)
                .updateState("id-1", "user-1", McpManagementService.STATE_DEGRADED, true, null, "MCP_OPERATION_FAILED");
    }

    @Test
    void testReconfigureWithApprovalPersistenceUnavailableThrows() throws Exception {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.headerSecretRefs("id-1", "user-1")).thenReturn(List.of());
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), "tok"))
                .thenReturn(List.of());
        when(lifecycleService.persistSuccessfulHttpKeepingHeaders(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1"))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        McpConnectionException ex = assertThrows(
                McpConnectionException.class,
                () -> service.reconfigureWithApproval(
                        "user-1", "server", "https://example.test/mcp", Map.of(), "tok", 0L));
        assertTrue(ex.getMessage().contains("MCP persistence backend is unavailable"));
    }

    @Test
    void testPauseSuccess() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(registry.getAll("user-1"))
                .thenReturn(List.of(
                        new DynamicToolCapability("tool1", "desc", "server", McpTransportType.HTTP_SSE, false, "{}")));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());

        var snapshot = service.pause("user-1", "server");
        assertNotNull(snapshot);
        verify(registry).drainAndDeregister(eq("user-1"), eq("tool1"), any());
        verify(lifecycleService).updateState("id-1", "user-1", McpManagementService.STATE_PAUSED, false, null, null);

        // With revision
        var snapshot2 = service.pause("user-1", "server", 0L);
        assertNotNull(snapshot2);
    }

    @Test
    void testResumeAndReconnectDirect() throws Exception {
        McpConnectionDto conn = connection("server", "PAUSED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of());
        when(clientService.connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        (String) null,
                        OutboundPurpose.MCP_CONNECT,
                        Set.of()))
                .thenReturn(List.of());

        var resSnapshot = service.resume("user-1", "server");
        assertNotNull(resSnapshot);

        var resSnapshot2 = service.resume("user-1", "server", 0L);
        assertNotNull(resSnapshot2);

        var recSnapshot = service.reconnect("user-1", "server");
        assertNotNull(recSnapshot);

        var recSnapshot2 = service.reconnect("user-1", "server", 0L);
        assertNotNull(recSnapshot2);
    }

    @Test
    void testDisconnectWithResultNotFound() {
        when(lifecycleService.findByNameAndUserId("ghost", "user-1")).thenReturn(Optional.empty());
        var res = service.disconnectWithResult("user-1", "ghost");
        assertFalse(res.deregistered());
        assertEquals(0, res.toolsRemoved());
    }

    @Test
    void testDisconnectWithResultSuccess() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(registry.getAll("user-1"))
                .thenReturn(List.of(
                        new DynamicToolCapability("t1", "desc", "server", McpTransportType.HTTP_SSE, false, "{}")));
        when(lifecycleService.deleteByNameAndUserId("server", "user-1")).thenReturn(true);

        var res = service.disconnectWithResult("user-1", "server", 0L);
        assertTrue(res.deregistered());
        assertEquals(1, res.toolsRemoved());
        verify(auditService)
                .recordMandatory(
                        "mcp-management",
                        "user-1",
                        "system",
                        SecurityEventType.MCP_SERVER_DISCONNECTED,
                        "server=server");

        // Convenience method
        assertTrue(service.disconnect("user-1", "server"));
    }

    @Test
    void testDisconnectWithResultDeleteFails() {
        McpConnectionDto conn = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(conn));
        when(registry.getAll("user-1")).thenReturn(List.of());
        when(lifecycleService.deleteByNameAndUserId("server", "user-1")).thenReturn(false);

        var res = service.disconnectWithResult("user-1", "server");
        assertFalse(res.deregistered());
        assertEquals(0, res.toolsRemoved());
        verify(auditService, never())
                .recordMandatory(any(), any(), any(), eq(SecurityEventType.MCP_SERVER_DISCONNECTED), any());
    }

    @Test
    void testRequireUserValidationAcrossOperations() {
        assertThrows(IllegalArgumentException.class, () -> service.enableTool(null, "server", "t"));
        assertThrows(IllegalArgumentException.class, () -> service.disableTool("   ", "server", "t"));
        assertThrows(IllegalArgumentException.class, () -> service.pause("unknown", "server"));
        assertThrows(IllegalArgumentException.class, () -> service.disconnectWithResult(null, "server"));
        assertThrows(
                IllegalArgumentException.class, () -> service.discover("", "server", "https://example.test", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.connectNew("unknown", "server", "https://example.test", Map.of()));
    }

    // ==================== Models Coverage Tests ====================

    @Test
    void testModelsCoverage() {
        ToolPreview tool = new ToolPreview("calc", "Calculator tool", "{\"type\":\"object\"}", "hash123");
        assertEquals("calc", tool.name());
        assertEquals("Calculator tool", tool.description());
        assertEquals("{\"type\":\"object\"}", tool.inputSchema());
        assertEquals("hash123", tool.schemaHash());

        DiscoveryPreview discovery = new DiscoveryPreview("srv", "https://example.test", "HTTP_SSE", List.of(tool));
        assertEquals("srv", discovery.name());
        assertEquals("https://example.test", discovery.endpoint());
        assertEquals("HTTP_SSE", discovery.transport());
        assertEquals(1, discovery.tools().size());

        DisconnectResult dcResult = new DisconnectResult(true, 5);
        assertTrue(dcResult.deregistered());
        assertEquals(5, dcResult.toolsRemoved());

        ApprovalExecution exec = new ApprovalExecution("task-123", "CONNECT", dcResult);
        assertEquals("task-123", exec.taskId());
        assertEquals("CONNECT", exec.operation());
        assertEquals(dcResult, exec.result());

        ConnectionSnapshot snapshot =
                new ConnectionSnapshot("srv", "https://example.test", "CONNECTED", "HTTP_SSE", 1, 2, 0, List.of());
        assertEquals("srv", snapshot.name());
        assertEquals("https://example.test", snapshot.endpoint());
        assertEquals("CONNECTED", snapshot.state());
        assertEquals("HTTP_SSE", snapshot.transport());
        assertEquals(1, snapshot.enabledToolCount());
        assertEquals(2, snapshot.discoveredToolCount());
        assertEquals(0, snapshot.revision());
        assertTrue(snapshot.tools().isEmpty());
    }
}
