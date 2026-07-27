package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;

import com.amsha.core.sandbox.SandboxCircuitBreaker;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodmanMcpTransportTest {

    private PodmanMcpTransport transport;

    @BeforeEach
    void setUp() {
        transport = new PodmanMcpTransport();
        transport.sandboxCircuitBreaker = mock(SandboxCircuitBreaker.class);
        transport.registry = new DynamicToolCapabilityRegistry();
        transport.objectMapper = new ObjectMapper();
        transport.init();
    }

    @Test
    void testDisconnectNonExistentServer() {
        assertDoesNotThrow(() -> transport.disconnect("ghost-server").await().indefinitely());
    }

    @Test
    void testHealthCheckNoThrow() throws Exception {
        Method healthCheck = PodmanMcpTransport.class.getDeclaredMethod("healthCheck");
        healthCheck.setAccessible(true);
        assertDoesNotThrow(() -> healthCheck.invoke(transport));
    }

    @Test
    void testCleanupNoThrow() {
        assertDoesNotThrow(transport::cleanup);
    }
}
