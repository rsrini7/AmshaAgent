package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.sandbox.SandboxResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessIsolationExecutorUnitTest {

    @Mock
    SandboxCircuitBreaker circuitBreaker;

    @Mock
    PodmanContainerExecutor podmanExecutor;

    @Mock
    NamespaceExecutor namespaceExecutor;

    @Mock
    DirectExecutor directExecutor;

    @Mock
    SandboxSecretResolver secretResolver;

    @InjectMocks
    ProcessIsolationExecutor executor;

    @BeforeEach
    void setUp() {
        executor.sandboxStrategy = SandboxStrategy.PODMAN;
        executor.sandboxEnabled = true;
        executor.podmanPath = "podman-invalid-path";
        executor.allowInsecureFallback = false;
    }

    @Test
    void testCheckPodmanAvailability() {
        assertDoesNotThrow(() -> executor.checkPodmanAvailability());
        verify(circuitBreaker).openCircuit();
    }

    @Test
    void testExecuteSandboxedDisabled() throws Exception {
        executor.sandboxEnabled = false;
        when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("direct output");

        SandboxResult res = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo", "hi"), Map.of());
        assertTrue(res.success());
        assertEquals("direct output", res.output());
    }

    @Test
    void testExecuteSandboxedPodmanSuccess() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any())).thenReturn("podman output");

        SandboxResult res = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo", "hi"), Map.of());
        assertTrue(res.success());
        assertEquals("podman output", res.output());
    }

    @Test
    void testToolExecutionFailureIsSanitized() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any()))
                .thenThrow(new ToolExecutionException("raw-provider-diagnostic", 7));

        SandboxResult result =
                executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo", "hi"), Map.of());

        assertFalse(result.success());
        assertEquals(SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE, result.output());
        assertFalse(result.output().contains("raw-provider-diagnostic"));
    }

    @Test
    void testInfrastructureFailureReasonIsSanitized() {
        SandboxResult result = SandboxResult.infraFailure("raw-provider-diagnostic");

        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, result.reason());
        assertFalse(result.reason().contains("raw-provider-diagnostic"));
    }

    @Test
    void testExecuteSandboxedPodmanImageMissing() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any()))
                .thenThrow(new SandboxImageMissingException("image missing"));

        SandboxResult res = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo", "hi"), Map.of());
        assertFalse(res.success());
        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, res.reason());
    }
}
