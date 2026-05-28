package com.arcclaw.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.arcclaw.core.sandbox.SandboxCircuitBreaker;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertThrows(
                RuntimeException.class, () -> transport.connect(config).await().indefinitely());
    }

    @Test
    void testDisconnectCleanup() {
        // Mock state
        transport.disconnect("unknown-server").await().indefinitely();
        // verify no exceptions
    }

    @Test
    void testCircuitBreakerBlocksWhenOpenAndNotAttempting() {
        when(mockCB.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.OPEN);
        when(mockCB.shouldAttemptPodman()).thenReturn(false);
        McpServerConfig config = new McpServerConfig("s", "img", List.of(), Map.of());
        assertThrows(
                RuntimeException.class, () -> transport.connect(config).await().indefinitely());
        verify(mockCB, atLeastOnce()).getState();
        verify(mockCB).shouldAttemptPodman();
    }

    @Test
    void testRecordFailureCalledOnIOException() {
        // state=OPEN + shouldAttemptPodman()=true → passes guard, hits ProcessBuilder,
        // fails with IOException ("podman" not found), → recordFailure() must be called.
        when(mockCB.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.OPEN);
        when(mockCB.shouldAttemptPodman()).thenReturn(true);
        McpServerConfig config = new McpServerConfig("test", "nonexistent-image-xyz-404", List.of(), Map.of());
        assertThrows(
                RuntimeException.class, () -> transport.connect(config).await().indefinitely());
        verify(mockCB).recordFailure(any());
    }

    @Test
    void testPreDestroyCleanup() throws Exception {
        Process mockProcess = mock(Process.class);
        when(mockProcess.waitFor(3, TimeUnit.SECONDS)).thenReturn(true);

        Field activeProcessesField = PodmanMcpTransport.class.getDeclaredField("activeProcesses");
        activeProcessesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Process> activeProcesses =
                (ConcurrentHashMap<String, Process>) activeProcessesField.get(transport);
        activeProcesses.put("test-session", mockProcess);

        transport.cleanup();

        verify(mockProcess).destroy();
        assertTrue(activeProcesses.isEmpty());
    }
}
