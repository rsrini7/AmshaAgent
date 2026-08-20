package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.sandbox.SandboxCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class PodmanMcpTransportCoverageTest {

    @Test
    void testCircuitBreakerOpenThrows() {
        PodmanMcpTransport transport = new PodmanMcpTransport();
        SandboxCircuitBreaker breaker = mock(SandboxCircuitBreaker.class);
        when(breaker.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.OPEN);
        when(breaker.shouldAttemptPodman()).thenReturn(false);

        transport.sandboxCircuitBreaker = breaker;
        transport.registry = new DynamicToolCapabilityRegistry();
        transport.objectMapper = new ObjectMapper();
        transport.init();

        assertFalse(transport.hasServer("non-existent"));

        McpServerConfig config =
                new McpServerConfig("test-server", "img:latest", List.of(), Map.of(), ToolCategory.COMPUTE, "user-1");
        assertThrows(RuntimeException.class, () -> {
            transport.connect(config).await().indefinitely();
        });

        transport.cleanup();
    }
}
