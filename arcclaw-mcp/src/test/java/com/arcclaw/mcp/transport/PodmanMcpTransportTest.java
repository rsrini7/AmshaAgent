package com.arcclaw.mcp.transport;

import com.arcclaw.core.sandbox.SandboxCircuitBreaker;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PodmanMcpTransportTest {

    private PodmanMcpTransport transport;
    private SandboxCircuitBreaker mockCB;
    private DynamicToolCapabilityRegistry mockRegistry;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        transport = new PodmanMcpTransport();
        mockCB = mock(SandboxCircuitBreaker.class);
        mockRegistry = mock(DynamicToolCapabilityRegistry.class);
        transport.sandboxCircuitBreaker = mockCB;
        transport.registry = mockRegistry;
        transport.objectMapper = mapper;
    }

    @Test
    void testConnectWhenCircuitOpen() {
        when(mockCB.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.OPEN);
        when(mockCB.shouldAttemptPodman()).thenReturn(false);

        McpServerConfig config = new McpServerConfig("test", "img", List.of(), Map.of());
        assertThrows(RuntimeException.class, () -> transport.connect(config).await().indefinitely());
    }

    @Test
    void testDisconnectCleanup() {
        // Mock state
        transport.disconnect("unknown-server").await().indefinitely();
        // verify no exceptions
    }
    
    // Complex subprocess tests often require more setup, 
    // but we'll stick to the core logic verification.
}
