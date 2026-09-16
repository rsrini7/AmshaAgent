package com.amsha.app.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.mcp.config.McpToolAllowlist;
import com.amsha.mcp.transport.McpApprovalService;
import com.amsha.mcp.transport.McpConnectionException;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.mcp.transport.McpManagementServiceModels;
import com.amsha.mcp.transport.McpRevisionConflictException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpConnectResourceTest {
    @Mock
    McpToolAllowlist config;

    @Mock
    McpManagementService managementService;

    @Mock
    SecurityContext securityContext;

    @Mock
    HttpHeaders httpHeaders;

    @Mock
    Principal principal;

    McpConnectResource resource;

    @BeforeEach
    void setUp() {
        resource = new McpConnectResource();
        setField(resource, "config", config);
        setField(resource, "managementService", managementService);
        setField(resource, "securityContext", securityContext);
        setField(resource, "httpHeaders", httpHeaders);
        setField(resource, "webAuthRequired", false);
        lenient().when(securityContext.getUserPrincipal()).thenReturn(principal);
        lenient().when(principal.getName()).thenReturn("user-1");
        lenient().when(config.serverEnabled()).thenReturn(true);
    }

    @Test
    void unauthenticatedBrowserUsesPlatformIdScope() {
        when(securityContext.getUserPrincipal()).thenReturn(null);
        when(httpHeaders.getHeaderString("X-AMSHA-PLATFORM-ID")).thenReturn("browser-tab-1");
        when(managementService.list("browser-tab-1")).thenReturn(List.of());

        resource.listConnected();

        verify(managementService).list("browser-tab-1");
    }

    @Test
    void authenticatedPrincipalTakesPrecedenceOverPlatformId() {
        when(managementService.list("user-1")).thenReturn(List.of());

        resource.listConnected();

        verify(managementService).list("user-1");
    }

    @Test
    void anonymousPrincipalUsesPlatformIdWhenWebAuthIsDisabled() {
        when(principal.getName()).thenReturn("anonymous");
        when(httpHeaders.getHeaderString("X-AMSHA-PLATFORM-ID")).thenReturn("browser-tab-2");
        when(managementService.list("browser-tab-2")).thenReturn(List.of());

        resource.listConnected();

        verify(managementService).list("browser-tab-2");
    }

    @Test
    void platformIdIsIgnoredWhenWebAuthIsRequired() {
        setField(resource, "webAuthRequired", true);
        when(securityContext.getUserPrincipal()).thenReturn(null);
        String fallbackUserId = WorkspaceContext.getUserIdOrUnknown();
        when(managementService.list(fallbackUserId)).thenReturn(List.of());

        resource.listConnected();

        verify(managementService).list(fallbackUserId);
    }

    @Test
    void connectReturnsPendingApproval() {
        var pending = new McpApprovalService.PendingApproval(
                "task-1",
                "CONNECT",
                "test-server",
                "http://localhost:3000/mcp",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestConnect("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenReturn(pending);

        var response =
                resource.connect(new McpConnectResource.ConnectRequest("test-server", "http://localhost:3000/mcp"));

        assertEquals(202, response.getStatus());
    }

    @Test
    void discoverReturnsPendingApproval() {
        var pending = new McpApprovalService.PendingApproval(
                "task-2",
                "DISCOVER",
                "test-server",
                "http://localhost:3000/mcp",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestDiscovery("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenReturn(pending);

        var response = resource.discover(
                new McpConnectResource.DiscoverRequest("test-server", "http://localhost:3000/mcp", Map.of()));

        assertEquals(202, response.getStatus());
    }

    @Test
    void reconfigureUsesRevisionHeaderAndSharedApprovalFlow() {
        var pending = new McpApprovalService.PendingApproval(
                "task-edit",
                "RECONFIGURE",
                "test-server",
                "https://example.test/mcp",
                null,
                Instant.now().plusSeconds(60),
                4L);
        when(managementService.requestReconfigure(
                        "user-1", "test-server", "https://example.test/mcp", Map.of(), false, 4L))
                .thenReturn(pending);

        var response = resource.reconfigure(
                "test-server",
                new McpConnectResource.ReconfigureRequest("https://example.test/mcp", Map.of()),
                null,
                4L);

        assertEquals(202, response.getStatus());
        verify(managementService)
                .requestReconfigure("user-1", "test-server", "https://example.test/mcp", Map.of(), false, 4L);
    }

    @Test
    void reconfigurePropagatesExplicitCredentialClear() {
        var pending = new McpApprovalService.PendingApproval(
                "task-clear",
                "RECONFIGURE",
                "test-server",
                "https://example.test/mcp",
                null,
                Instant.now().plusSeconds(60),
                4L);
        when(managementService.requestReconfigure(
                        "user-1", "test-server", "https://example.test/mcp", Map.of(), true, 4L))
                .thenReturn(pending);

        var response = resource.reconfigure(
                "test-server",
                new McpConnectResource.ReconfigureRequest("https://example.test/mcp", Map.of(), true),
                null,
                4L);

        assertEquals(202, response.getStatus());
        verify(managementService)
                .requestReconfigure("user-1", "test-server", "https://example.test/mcp", Map.of(), true, 4L);
    }

    @Test
    void blankInputsReturn400() {
        assertEquals(
                400,
                resource.connect(new McpConnectResource.ConnectRequest("", "http://localhost/mcp"))
                        .getStatus());
        assertEquals(
                400,
                resource.connect(new McpConnectResource.ConnectRequest("name", ""))
                        .getStatus());
    }

    @Test
    void disabledServerReturns503() {
        when(config.serverEnabled()).thenReturn(false);
        assertEquals(
                503,
                resource.connect(new McpConnectResource.ConnectRequest("name", "http://localhost/mcp"))
                        .getStatus());
    }

    @Test
    void disconnectDelegatesToManagementService() {
        when(managementService.disconnectWithResult("user-1", "test-server"))
                .thenReturn(new McpManagementService.DisconnectResult(true, 2));
        assertEquals(200, resource.disconnect("test-server").getStatus());
    }

    @Test
    void disconnectMissingConnectionReturns404() {
        when(managementService.disconnectWithResult("user-1", "missing-server"))
                .thenReturn(new McpManagementService.DisconnectResult(false, 0));
        assertEquals(404, resource.disconnect("missing-server").getStatus());
    }

    @Test
    void listUsesDurableManagementSnapshots() {
        when(managementService.list("user-1")).thenReturn(List.of());
        assertEquals(200, resource.listConnected().getStatus());
    }

    @Test
    void listReturnsPendingMcpApprovals() {
        when(managementService.pendingApprovals("user-1")).thenReturn(List.of());
        assertEquals(200, resource.listApprovals().getStatus());
    }

    @Test
    void approveAndRejectLifecycleOperations() {
        when(managementService.approve("user-1", "task-100"))
                .thenReturn(new McpManagementService.ApprovalExecution("task-100", "CONNECT", "ok"));
        assertEquals(200, resource.approve("task-100").getStatus());

        when(managementService.rejectApproval("user-1", "task-100")).thenReturn(true);
        assertEquals(200, resource.reject("task-100").getStatus());

        when(managementService.rejectApproval("user-1", "missing-task")).thenReturn(false);
        assertEquals(404, resource.reject("missing-task").getStatus());
    }

    @Test
    void pauseResumeToolsAndRotateHeaders() {
        when(managementService.pause("user-1", "test-server", 1L))
                .thenReturn(new McpManagementService.ConnectionSnapshot(
                        "test-server", "https://example.com", "PAUSED", "HTTP_SSE", 0, 1, 2, List.of()));
        assertEquals(200, resource.pause("test-server", 1L).getStatus());

        var pending = new McpApprovalService.PendingApproval(
                "task-rec",
                "RECONNECT",
                "test-server",
                "https://example.com",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestReconnect("user-1", "test-server", 2L)).thenReturn(pending);
        assertEquals(202, resource.resume("test-server", 2L).getStatus());
        assertEquals(202, resource.reconnect("test-server", 2L).getStatus());

        when(managementService.listTools("user-1", "test-server")).thenReturn(List.of());
        assertEquals(200, resource.tools("test-server").getStatus());

        when(managementService.requestRotateHeaders("user-1", "test-server", Map.of("Auth", "secret"), 2L))
                .thenReturn(pending);
        assertEquals(
                202,
                resource.rotateHeaders(
                                "test-server",
                                new McpConnectResource.RotateHeadersRequest(Map.of("Auth", "secret")),
                                null,
                                2L)
                        .getStatus());
    }

    // ---- Approval-token branches (the `X-MCP-APPROVAL-TOKEN`-present arms) ----

    @Test
    void connectWithApprovalTokenUsesConnectNewWithApproval() {
        // Exercises the connect approvalToken branch (109-115); the 3-arg ctor is covered separately below.
        when(managementService.connectNewWithApproval(
                        "user-1", "test-server", "http://localhost:3000/mcp", Map.of(), "tok"))
                .thenReturn(new McpManagementService.ConnectionSnapshot(
                        "test-server", "http://localhost:3000/mcp", "CONNECTED", "HTTP_SSE", 0, 1, 2, List.of()));
        var response = resource.connect(
                new McpConnectResource.ConnectRequest("test-server", "http://localhost:3000/mcp", Map.of(), "tok"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void connectThreeArgConstructorIsExercised() {
        // Covers the 3-arg ConnectRequest ctor (64-65) which no approval/2-arg path instantiates.
        var pending = new McpApprovalService.PendingApproval(
                "task-1",
                "CONNECT",
                "test-server",
                "http://localhost:3000/mcp",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestConnect("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenReturn(pending);
        assertEquals(
                202,
                resource.connect(new McpConnectResource.ConnectRequest(
                                "test-server", "http://localhost:3000/mcp", Map.of()))
                        .getStatus());
    }

    @Test
    void discoverWithApprovalTokenUsesDiscoverWithApproval() {
        when(managementService.discoverWithApproval(
                        "user-1", "test-server", "http://localhost:3000/mcp", Map.of(), "tok"))
                .thenReturn(new McpManagementServiceModels.DiscoveryPreview(
                        "test-server", "http://localhost:3000/mcp", "HTTP_SSE", List.of()));
        var response = resource.discover(
                new McpConnectResource.DiscoverRequest("test-server", "http://localhost:3000/mcp", Map.of(), "tok"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void activateWithApprovalTokenUsesConnectWithApproval() {
        when(managementService.connectWithApproval("user-1", "test-server", "tok", 5L))
                .thenReturn(new McpManagementService.ConnectionSnapshot(
                        "test-server", "https://example.com", "CONNECTED", "HTTP_SSE", 0, 1, 2, List.of()));
        assertEquals(200, resource.activate("test-server", "tok", 5L).getStatus());
    }

    @Test
    void activateWithoutApprovalTokenRequestsReconnect() {
        var pending = new McpApprovalService.PendingApproval(
                "task-rec",
                "RECONNECT",
                "test-server",
                "https://example.com",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestReconnect("user-1", "test-server", 5L)).thenReturn(pending);
        assertEquals(202, resource.activate("test-server", null, 5L).getStatus());
    }

    @Test
    void reconfigureWithApprovalTokenUsesReconfigureWithApproval() {
        when(managementService.reconfigureWithApproval(
                        "user-1", "test-server", "https://example.test/mcp", Map.of(), true, "tok", 5L))
                .thenReturn(new McpManagementService.ConnectionSnapshot(
                        "test-server", "https://example.test/mcp", "RECONFIGURED", "HTTP_SSE", 0, 1, 2, List.of()));
        var response = resource.reconfigure(
                "test-server",
                new McpConnectResource.ReconfigureRequest("https://example.test/mcp", Map.of(), true),
                "tok",
                5L);
        assertEquals(200, response.getStatus());
    }

    @Test
    void rotateHeadersWithApprovalTokenUsesRotateHeadersWithApproval() {
        when(managementService.rotateHeadersWithApproval("user-1", "test-server", Map.of("Auth", "secret"), "tok", 5L))
                .thenReturn(new McpManagementService.ConnectionSnapshot(
                        "test-server", "https://example.com", "CONNECTED", "HTTP_SSE", 0, 1, 2, List.of()));
        assertEquals(
                200,
                resource.rotateHeaders(
                                "test-server",
                                new McpConnectResource.RotateHeadersRequest(Map.of("Auth", "secret")),
                                "tok",
                                5L)
                        .getStatus());
    }

    @Test
    void rotateHeadersWithNullRequestFallsBackToEmptyHeaders() {
        // Covers 336: request == null -> Map.of() before the pendingOperation delegation.
        var pending = new McpApprovalService.PendingApproval(
                "task-rot",
                "ROTATE_HEADERS",
                "test-server",
                "https://example.com",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestRotateHeaders("user-1", "test-server", Map.of(), 5L))
                .thenReturn(pending);
        assertEquals(202, resource.rotateHeaders("test-server", null, null, 5L).getStatus());
    }

    // ---- disconnect 3-arg (If-MCP-Revision header) branch ----

    @Test
    void disconnectWithRevisionHeaderUsesThreeArgDisconnectWithResult() {
        when(managementService.disconnectWithResult("user-1", "test-server", 5L))
                .thenReturn(new McpManagementService.DisconnectResult(true, 2));
        assertEquals(200, resource.disconnect("test-server", 5L).getStatus());
    }

    // ---- connect error paths ----

    @Test
    void connectIllegalArgumentExceptionMapsTo400() {
        when(managementService.requestConnect("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenThrow(new IllegalArgumentException("bad"));
        assertEquals(
                400,
                resource.connect(new McpConnectResource.ConnectRequest("test-server", "http://localhost:3000/mcp"))
                        .getStatus());
    }

    @Test
    void connectRevisionConflictMapsTo409() {
        when(managementService.requestConnect("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenThrow(new McpRevisionConflictException(1L, 2L));
        assertEquals(
                409,
                resource.connect(new McpConnectResource.ConnectRequest("test-server", "http://localhost:3000/mcp"))
                        .getStatus());
    }

    @Test
    void connectConnectionExceptionMapsTo502() {
        when(managementService.requestConnect("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenThrow(new McpConnectionException("down"));
        assertEquals(
                502,
                resource.connect(new McpConnectResource.ConnectRequest("test-server", "http://localhost:3000/mcp"))
                        .getStatus());
    }

    // ---- discover error paths ----

    @Test
    void discoverRejectsNullBlankNameAndBlankUrl() {
        // Covers 177-178.
        assertEquals(400, resource.discover(null).getStatus());
        assertEquals(
                400,
                resource.discover(new McpConnectResource.DiscoverRequest("", "http://localhost:3000/mcp", Map.of()))
                        .getStatus());
        assertEquals(
                400,
                resource.discover(new McpConnectResource.DiscoverRequest("test-server", "", Map.of()))
                        .getStatus());
    }

    @Test
    void discoverIllegalArgumentExceptionMapsTo400() {
        when(managementService.requestDiscovery("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenThrow(new IllegalArgumentException("bad"));
        assertEquals(
                400,
                resource.discover(new McpConnectResource.DiscoverRequest(
                                "test-server", "http://localhost:3000/mcp", Map.of()))
                        .getStatus());
    }

    @Test
    void discoverConnectionExceptionMapsTo502() {
        when(managementService.requestDiscovery("user-1", "test-server", "http://localhost:3000/mcp", Map.of()))
                .thenThrow(new McpConnectionException("down"));
        assertEquals(
                502,
                resource.discover(new McpConnectResource.DiscoverRequest(
                                "test-server", "http://localhost:3000/mcp", Map.of()))
                        .getStatus());
    }

    // ---- disconnect error paths ----

    @Test
    void disconnectRevisionConflictMapsTo409() {
        when(managementService.disconnectWithResult("user-1", "test-server"))
                .thenThrow(new McpRevisionConflictException(1L, 2L));
        assertEquals(409, resource.disconnect("test-server").getStatus());
    }

    @Test
    void disconnectConnectionExceptionMapsTo404() {
        when(managementService.disconnectWithResult("user-1", "test-server"))
                .thenThrow(new McpConnectionException("down"));
        assertEquals(404, resource.disconnect("test-server").getStatus());
    }

    @Test
    void disconnectIllegalArgumentExceptionMapsTo400() {
        when(managementService.disconnectWithResult("user-1", "test-server"))
                .thenThrow(new IllegalArgumentException("bad"));
        assertEquals(400, resource.disconnect("test-server").getStatus());
    }

    // ---- approve / reject error paths ----

    @Test
    void approveIllegalArgumentExceptionMapsTo400() {
        when(managementService.approve("user-1", "task-100")).thenThrow(new IllegalArgumentException("bad"));
        assertEquals(400, resource.approve("task-100").getStatus());
    }

    @Test
    void approveRevisionConflictMapsTo409() {
        when(managementService.approve("user-1", "task-100")).thenThrow(new McpRevisionConflictException(1L, 2L));
        assertEquals(409, resource.approve("task-100").getStatus());
    }

    @Test
    void approveConnectionExceptionMapsTo502() {
        when(managementService.approve("user-1", "task-100")).thenThrow(new McpConnectionException("down"));
        assertEquals(502, resource.approve("task-100").getStatus());
    }

    @Test
    void rejectIllegalArgumentExceptionMapsTo400() {
        when(managementService.rejectApproval("user-1", "task-100")).thenThrow(new IllegalArgumentException("bad"));
        assertEquals(400, resource.reject("task-100").getStatus());
    }

    @Test
    void rejectRevisionConflictMapsTo409() {
        when(managementService.rejectApproval("user-1", "task-100"))
                .thenThrow(new McpRevisionConflictException(1L, 2L));
        assertEquals(409, resource.reject("task-100").getStatus());
    }

    // ---- tools error paths ----

    @Test
    void toolsIllegalArgumentExceptionMapsTo400() {
        when(managementService.listTools("user-1", "test-server")).thenThrow(new IllegalArgumentException("bad"));
        assertEquals(400, resource.tools("test-server").getStatus());
    }

    @Test
    void toolsConnectionExceptionMapsTo502() {
        when(managementService.listTools("user-1", "test-server")).thenThrow(new McpConnectionException("down"));
        assertEquals(502, resource.tools("test-server").getStatus());
    }

    // ---- enableTool / disableTool happy + error paths (the operation() wrapper catches) ----

    @Test
    void enableToolRequestsApproval() {
        var pending = new McpApprovalService.PendingApproval(
                "task-en",
                "ENABLE_TOOL",
                "test-server",
                "https://example.com",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestEnableTool("user-1", "test-server", "tool-1", 5L))
                .thenReturn(pending);
        assertEquals(202, resource.enableTool("test-server", "tool-1", 5L).getStatus());
    }

    @Test
    void disableToolDelegatesAndReturnsSnapshot() {
        when(managementService.disableTool("user-1", "test-server", "tool-1", 5L))
                .thenReturn(new McpManagementService.ConnectionSnapshot(
                        "test-server", "https://example.com", "CONNECTED", "HTTP_SSE", 0, 1, 2, List.of()));
        assertEquals(200, resource.disableTool("test-server", "tool-1", 5L).getStatus());
    }

    @Test
    void disableToolIllegalArgumentExceptionMapsTo400() {
        when(managementService.disableTool("user-1", "test-server", "tool-1", 5L))
                .thenThrow(new IllegalArgumentException("bad"));
        assertEquals(400, resource.disableTool("test-server", "tool-1", 5L).getStatus());
    }

    @Test
    void disableToolRevisionConflictMapsTo409() {
        when(managementService.disableTool("user-1", "test-server", "tool-1", 5L))
                .thenThrow(new McpRevisionConflictException(1L, 2L));
        assertEquals(409, resource.disableTool("test-server", "tool-1", 5L).getStatus());
    }

    @Test
    void disableToolConnectionExceptionMapsTo502() {
        when(managementService.disableTool("user-1", "test-server", "tool-1", 5L))
                .thenThrow(new McpConnectionException("down"));
        assertEquals(502, resource.disableTool("test-server", "tool-1", 5L).getStatus());
    }

    // ---- pendingOperation() wrapper catches (via reconnect) ----

    @Test
    void reconnectIllegalArgumentExceptionMapsTo400() {
        when(managementService.requestReconnect("user-1", "test-server", 5L))
                .thenThrow(new IllegalArgumentException("bad"));
        assertEquals(400, resource.reconnect("test-server", 5L).getStatus());
    }

    @Test
    void reconnectRevisionConflictMapsTo409() {
        when(managementService.requestReconnect("user-1", "test-server", 5L))
                .thenThrow(new McpRevisionConflictException(1L, 2L));
        assertEquals(409, resource.reconnect("test-server", 5L).getStatus());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
