package com.amsha.app.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.List;

import com.amsha.core.audit.AuditService;
import com.amsha.mcp.config.McpToolAllowlist;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.McpConnectionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpConnectResourceTest {

    @Mock
    McpClientService mcpClientService;

    @Mock
    DynamicToolCapabilityRegistry registry;

    @Mock
    McpToolAllowlist config;

    @Mock
    AuditService auditService;

    McpConnectResource resource;

    private static final String SERVER_NAME = "test-server";
    private static final String SERVER_URL = "http://localhost:3000/mcp";

    @BeforeEach
    void setUp() {
        resource = new McpConnectResource();
        setField(resource, "mcpClientService", mcpClientService);
        setField(resource, "registry", registry);
        setField(resource, "config", config);
        setField(resource, "auditService", auditService);
    }

    @Test
    void connectReturns200OnSuccess() throws McpConnectionException {
        when(config.serverEnabled()).thenReturn(true);
        when(registry.getAll()).thenReturn(List.of());
        McpConnectResource.ConnectRequest request = new McpConnectResource.ConnectRequest(SERVER_NAME, SERVER_URL);

        Response resp = resource.connect(request);

        assertEquals(200, resp.getStatus());
        verify(mcpClientService).connect(SERVER_NAME, SERVER_URL);
    }

    @Test
    void connectReturns503WhenDisabled() {
        when(config.serverEnabled()).thenReturn(false);

        McpConnectResource.ConnectRequest request = new McpConnectResource.ConnectRequest(SERVER_NAME, SERVER_URL);
        Response resp = resource.connect(request);

        assertEquals(503, resp.getStatus());
    }

    @Test
    void connectReturns400WhenNameBlank() {
        when(config.serverEnabled()).thenReturn(true);

        McpConnectResource.ConnectRequest request = new McpConnectResource.ConnectRequest("", SERVER_URL);
        Response resp = resource.connect(request);

        assertEquals(400, resp.getStatus());
    }

    @Test
    void connectReturns400WhenUrlBlank() {
        when(config.serverEnabled()).thenReturn(true);

        McpConnectResource.ConnectRequest request = new McpConnectResource.ConnectRequest(SERVER_NAME, "");
        Response resp = resource.connect(request);

        assertEquals(400, resp.getStatus());
    }

    @Test
    void connectReturns502OnConnectionFailure() throws McpConnectionException {
        when(config.serverEnabled()).thenReturn(true);
        when(registry.getAll()).thenReturn(List.of());
        doThrow(new McpConnectionException("Connection refused"))
                .when(mcpClientService)
                .connect(SERVER_NAME, SERVER_URL);

        McpConnectResource.ConnectRequest request = new McpConnectResource.ConnectRequest(SERVER_NAME, SERVER_URL);
        Response resp = resource.connect(request);

        assertEquals(502, resp.getStatus());
    }

    @Test
    void disconnectReturns200WhenServerFound() {
        DynamicToolCapability tool = mock(DynamicToolCapability.class);
        when(tool.mcpServerName()).thenReturn(SERVER_NAME);
        when(tool.toolName()).thenReturn("tool-1");
        Collection<DynamicToolCapability> tools = List.of(tool);
        when(registry.getAll()).thenReturn(tools);

        Response resp = resource.disconnect(SERVER_NAME);

        assertEquals(200, resp.getStatus());
        McpConnectResource.DisconnectResponse body = (McpConnectResource.DisconnectResponse) resp.getEntity();
        assertTrue(body.deregistered());
        assertEquals(1, body.toolsRemoved());
    }

    @Test
    void disconnectReturns404WhenNoServerFound() {
        when(registry.getAll()).thenReturn(List.of());

        Response resp = resource.disconnect("nonexistent");

        assertEquals(404, resp.getStatus());
    }

    @Test
    void listConnectedReturns200() {
        DynamicToolCapability tool1 = mock(DynamicToolCapability.class);
        DynamicToolCapability tool2 = mock(DynamicToolCapability.class);
        when(tool1.mcpServerName()).thenReturn("server-a");
        when(tool2.mcpServerName()).thenReturn("server-a");
        when(registry.getAll()).thenReturn(List.of(tool1, tool2));

        Response resp = resource.listConnected();

        assertEquals(200, resp.getStatus());
    }

    @Test
    void listConnectedReturns200WithEmptyRegistry() {
        when(registry.getAll()).thenReturn(List.of());

        Response resp = resource.listConnected();

        assertEquals(200, resp.getStatus());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
