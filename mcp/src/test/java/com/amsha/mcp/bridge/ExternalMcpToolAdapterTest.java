package com.amsha.mcp.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.PodmanMcpTransport;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;

class ExternalMcpToolAdapterTest {

    private ExternalMcpToolAdapter adapter;
    private PodmanMcpTransport mockPodman;
    private McpClientService mockClient;
    private DynamicToolCapabilityRegistry mockRegistry;
    private AuditService mockAuditService;

    @BeforeEach
    void setUp() {
        adapter = new ExternalMcpToolAdapter();
        mockPodman = mock(PodmanMcpTransport.class);
        mockClient = mock(McpClientService.class);
        mockRegistry = mock(DynamicToolCapabilityRegistry.class);
        mockAuditService = mock(AuditService.class);
        adapter.podmanMcpTransport = mockPodman;
        adapter.mcpClientService = mockClient;
        adapter.registry = mockRegistry;
        adapter.auditService = mockAuditService;
        adapter.objectMapper = new ObjectMapper();
    }

    @Test
    void testInvokeStdioTool() {
        DynamicToolCapability cap =
                new DynamicToolCapability("tool", "desc", "server", McpTransportType.STDIO, false, "{}");
        when(mockRegistry.find("unknown", "tool")).thenReturn(Optional.of(cap));
        when(mockRegistry.acquireInvocation("unknown", "tool")).thenReturn(true);
        when(mockPodman.invoke("server", "tool", "{}"))
                .thenReturn(Uni.createFrom().item("ok"));

        String result = adapter.invoke("tool", "{}");
        assertEquals("ok", result);
    }

    @Test
    void testInvokeHttpTool() {
        DynamicToolCapability cap =
                new DynamicToolCapability("tool", "desc", "server", McpTransportType.HTTP_SSE, false, "{}");
        when(mockRegistry.find("unknown", "tool")).thenReturn(Optional.of(cap));
        when(mockRegistry.acquireInvocation("unknown", "tool")).thenReturn(true);
        when(mockClient.invoke("server", "tool", "{}")).thenReturn("http-ok");

        String result = adapter.invoke("tool", "{}");
        assertEquals("http-ok", result);
    }

    @Test
    void testUnregisteredTool() {
        when(mockRegistry.find("unknown", "unknown")).thenReturn(Optional.empty());
        assertThrows(McpToolNotFoundException.class, () -> adapter.invoke("unknown", "{}"));
    }

    @Test
    void dynamicInvokerExecutesUsingOwningUserScope() {
        DynamicToolCapability cap =
                new DynamicToolCapability("tool", "desc", "server", McpTransportType.HTTP_SSE, false, "{}");
        when(mockRegistry.find("alice", "tool")).thenReturn(Optional.of(cap));
        when(mockRegistry.acquireInvocation("alice", "tool")).thenReturn(true);
        when(mockClient.invoke("server", "tool", "{}", "alice")).thenReturn("scoped-ok");

        ToolExecutionContext context = new ToolExecutionContext(
                "session",
                "alice",
                "chat",
                "coordinator",
                AutonomyLevel.FULL,
                "trace",
                "rest",
                "chat",
                "goal",
                null,
                null,
                null);

        ToolResponse response = adapter.invoke("tool", Map.of(), context);

        assertEquals("scoped-ok", ((ToolResponse.ToolSuccess) response).output());
        verify(mockClient).invoke("server", "tool", "{}", "alice");
    }
}
