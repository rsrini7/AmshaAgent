package com.arcclaw.mcp.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.arcclaw.mcp.tool.DynamicToolCapability;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.tool.McpTransportType;
import com.arcclaw.mcp.transport.McpClientService;
import com.arcclaw.mcp.transport.PodmanMcpTransport;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalMcpToolAdapterTest {

    private ExternalMcpToolAdapter adapter;
    private PodmanMcpTransport mockPodman;
    private McpClientService mockClient;
    private DynamicToolCapabilityRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        adapter = new ExternalMcpToolAdapter();
        mockPodman = mock(PodmanMcpTransport.class);
        mockClient = mock(McpClientService.class);
        mockRegistry = mock(DynamicToolCapabilityRegistry.class);
        adapter.podmanMcpTransport = mockPodman;
        adapter.mcpClientService = mockClient;
        adapter.registry = mockRegistry;
    }

    @Test
    void testInvokeStdioTool() {
        DynamicToolCapability cap = new DynamicToolCapability("tool", "desc", "server", McpTransportType.STDIO, false, "{}");
        when(mockRegistry.find("tool")).thenReturn(Optional.of(cap));
        when(mockPodman.invoke("server", "tool", "{}")).thenReturn(Uni.createFrom().item("ok"));

        String result = adapter.invoke("tool", "{}");
        assertEquals("ok", result);
    }

    @Test
    void testInvokeHttpTool() {
        DynamicToolCapability cap = new DynamicToolCapability("tool", "desc", "server", McpTransportType.HTTP_SSE, false, "{}");
        when(mockRegistry.find("tool")).thenReturn(Optional.of(cap));
        when(mockClient.invoke("server", "tool", "{}")).thenReturn("http-ok");

        String result = adapter.invoke("tool", "{}");
        assertEquals("http-ok", result);
    }

    @Test
    void testUnregisteredTool() {
        when(mockRegistry.find("unknown")).thenReturn(Optional.empty());
        assertThrows(McpToolNotFoundException.class, () -> adapter.invoke("unknown", "{}"));
    }
}
