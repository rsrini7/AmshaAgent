package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;

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

    @Test
    void discoveryIsThrowaway() throws Exception {
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), false))
                .thenReturn(List.of(new McpClientService.DiscoveredTool("search", "Search", "{}")));

        var preview = service.discover("user-1", "server", "https://example.test/mcp", Map.of(), false);

        assertEquals(1, preview.tools().size());
        verify(lifecycleService, never()).persistSuccessfulHttp(any(), any(), any(), any(), any());
        verify(lifecycleService, never()).replaceToolPolicies(any(), any(), any());
        verify(clientService).clearHttpConnectionState("server", "user-1");
    }

    @Test
    void connectPersistsDiscoveredToolsDisabled() throws Exception {
        McpConnectionDto connection = connection("server", "CONNECTED");
        var tool = new McpClientService.DiscoveredTool("search", "Search", "{}");
        when(clientService.discoverForUser(
                        "server", "user-1", "https://example.test/mcp", ToolCategory.NETWORK, Map.of(), false))
                .thenReturn(List.of(tool));
        when(lifecycleService.persistSuccessfulHttp(
                        "server", "https://example.test/mcp", ToolCategory.NETWORK, "user-1", Map.of()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(connection, List.of()));
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(connection));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", false)));

        var snapshot = service.connectNew("user-1", "server", "https://example.test/mcp", Map.of(), false);

        assertEquals(0, snapshot.enabledToolCount());
        assertEquals(1, snapshot.discoveredToolCount());
        verify(lifecycleService).replaceToolPolicies(any(), any(), any());
        verify(lifecycleService).updateState("id-1", "user-1", "CONNECTED", true, null, null);
    }

    @Test
    void enableRollsPolicyBackWhenHandshakeFails() throws Exception {
        McpConnectionDto connection = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(connection));
        when(lifecycleService.listToolPolicies("id-1", "user-1")).thenReturn(List.of(policy("search", false)));
        when(lifecycleService.setToolEnabled("id-1", "user-1", "search", true)).thenReturn(1);
        when(lifecycleService.setToolEnabled("id-1", "user-1", "search", false)).thenReturn(1);
        doThrow(new McpConnectionException("offline"))
                .when(clientService)
                .connectForUserWithEnabledTools(
                        "server",
                        "user-1",
                        "https://example.test/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        false,
                        Set.of());

        assertThrows(McpConnectionException.class, () -> service.enableTool("user-1", "server", "search", false));

        verify(lifecycleService).setToolEnabled("id-1", "user-1", "search", false);
    }

    @Test
    void testRequestDiscovery() {
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.DISCOVER),
                        eq("server"),
                        eq("https://example.com"),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-1", "DISCOVER", "server", "https://example.com", null, now, null));

        var pending = service.requestDiscovery("user-1", "server", "https://example.com", Map.of());
        assertEquals("task-1", pending.taskId());
    }

    @Test
    void testRequestConnect() {
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.CONNECT),
                        eq("server"),
                        eq("https://example.com"),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-2", "CONNECT", "server", "https://example.com", null, now, null));

        var pending = service.requestConnect("user-1", "server", "https://example.com", Map.of());
        assertEquals("task-2", pending.taskId());
    }

    @Test
    void testRequestReconnect() {
        McpConnectionDto connection = connection("server", "DISCONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(connection));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.RECONNECT),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-3", "RECONNECT", "server", "https://example.test/mcp", null, now, null));

        var pending = service.requestReconnect("user-1", "server");
        assertEquals("task-3", pending.taskId());
    }

    @Test
    void testRequestRotateHeaders() {
        McpConnectionDto connection = connection("server", "CONNECTED");
        when(lifecycleService.findByNameAndUserId("server", "user-1")).thenReturn(Optional.of(connection));
        when(approvalService.request(
                        eq("user-1"),
                        eq(McpApprovalService.Operation.ROTATE_HEADERS),
                        eq("server"),
                        eq("https://example.test/mcp"),
                        any(),
                        any(),
                        any()))
                .thenReturn(new McpApprovalService.PendingApproval(
                        "task-4", "ROTATE_HEADERS", "server", "https://example.test/mcp", null, now, null));

        var pending = service.requestRotateHeaders("user-1", "server", Map.of("X-Key", "val"));
        assertEquals("task-4", pending.taskId());
    }

    @Test
    void testRejectApproval() {
        when(approvalService.reject("user-1", "task-1")).thenReturn(true);
        when(approvalService.find("user-1", "task-1")).thenReturn(Optional.empty());

        boolean rejected = service.rejectApproval("user-1", "task-1");
        assertEquals(true, rejected);
    }

    @Test
    void testListAndPendingApprovals() {
        when(approvalService.list("user-1")).thenReturn(List.of());
        assertEquals(0, service.pendingApprovals("user-1").size());

        when(lifecycleService.listByUserId("user-1")).thenReturn(List.of());
        assertEquals(0, service.list("user-1").size());
    }

    private McpConnectionDto connection(String name, String state) {
        return new McpConnectionDto(
                "id-1",
                "user-1",
                name,
                "HTTP_SSE",
                "https://example.test/mcp",
                "NETWORK",
                true,
                state,
                null,
                0,
                null,
                now,
                now);
    }

    private McpConnectionToolPolicyDto policy(String name, boolean enabled) {
        return new McpConnectionToolPolicyDto(
                "policy-1", "id-1", "user-1", name, true, enabled, "Search", "{}", "hash", now, 0, now);
    }
}
