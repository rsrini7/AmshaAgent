package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.sandbox.SandboxResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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

    // ------------------------------------------------------------------
    // Strategy routing and fallback
    // ------------------------------------------------------------------

    @Test
    void testIsAllowInsecureFallbackReflectsConfiguration() {
        assertFalse(executor.isAllowInsecureFallback());

        executor.allowInsecureFallback = true;

        assertTrue(executor.isAllowInsecureFallback());
    }

    @Test
    void testCheckPodmanAvailabilityAcceptsAHealthyEngine(@TempDir Path dir) throws IOException {
        executor.podmanPath = fakePodman(dir, "exit 0").toString();

        assertDoesNotThrow(() -> executor.checkPodmanAvailability());
        verify(circuitBreaker, never()).openCircuit();
    }

    @Test
    void testCheckPodmanAvailabilityOpensTheCircuitOnANonZeroExit(@TempDir Path dir) throws IOException {
        executor.podmanPath = fakePodman(dir, "exit 1").toString();

        assertDoesNotThrow(() -> executor.checkPodmanAvailability());
        verify(circuitBreaker).openCircuit();
    }

    @Test
    void testNamespaceStrategyRunsInNamespace() throws Exception {
        executor.sandboxStrategy = SandboxStrategy.NAMESPACE;
        when(namespaceExecutor.executeInNamespace(any(), any())).thenReturn("ns output");

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertTrue(result.success());
        assertEquals("ns output", result.output());
    }

    @Test
    void testNamespaceFailureWithoutFallbackIsReportedAsInfraFailure() throws Exception {
        executor.sandboxStrategy = SandboxStrategy.NAMESPACE;
        when(namespaceExecutor.executeInNamespace(any(), any())).thenThrow(new IOException("namespace unavailable"));

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertFalse(result.success());
        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, result.reason());
    }

    @Test
    void testNamespaceFailureWithInsecureFallbackDegradesToHost() throws Exception {
        executor.sandboxStrategy = SandboxStrategy.NAMESPACE;
        executor.allowInsecureFallback = true;
        when(namespaceExecutor.executeInNamespace(any(), any())).thenThrow(new IOException("namespace unavailable"));
        when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("host output");

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertTrue(result.success());
        assertEquals("host output", result.output());
    }

    @Test
    void testWorkspacePathsAreRewrittenForHostExecution() throws Exception {
        executor.sandboxStrategy = SandboxStrategy.DIRECT;
        when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("ok");
        Path workspace = Path.of("./target/ws");

        executor.executeSandboxed(
                "sess-1", workspace, List.of("/workspace/script.py", "sh", "-c", "run /workspace/x"), Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(directExecutor).executeDirectly(any(), captor.capture(), any());
        String hostRoot = workspace.toAbsolutePath().toString().replace("\\", "/");
        assertEquals(hostRoot + "/script.py", captor.getValue().get(0));
        assertEquals("run " + hostRoot + "/x", captor.getValue().get(3));
    }

    @Test
    void testTryPodmanExecutionReturnsNullWhenTheCircuitTripsBetweenChecks() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true, false);
        when(namespaceExecutor.executeInNamespace(any(), any())).thenReturn("ns output");

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertTrue(result.success());
        assertEquals("ns output", result.output());
        verifyNoInteractions(podmanExecutor);
    }

    @Test
    void testImageMissingWithInsecureFallbackDegradesToHost() throws Exception {
        executor.allowInsecureFallback = true;
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any()))
                .thenThrow(new SandboxImageMissingException("image missing"));
        when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("host output");

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertTrue(result.success());
        assertEquals("host output", result.output());
    }

    @Test
    void testPodmanUnavailableRecordsCircuitFailure() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.CLOSED);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any()))
                .thenThrow(new PodmanUnavailableException("podman down"));

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertFalse(result.success());
        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, result.reason());
        verify(circuitBreaker).recordFailure(SandboxCircuitBreaker.CircuitBreakerState.CLOSED);
    }

    @Test
    void testPodmanIoFailureRecordsCircuitFailure() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any()))
                .thenThrow(new IOException("podman io failure"));

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertFalse(result.success());
        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, result.reason());
        verify(circuitBreaker).recordFailure(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN);
    }

    @Test
    void testUnexpectedPodmanFailureIsReportedAsInfraFailure() throws Exception {
        when(circuitBreaker.shouldAttemptPodman()).thenReturn(true);
        when(podmanExecutor.executeInContainer(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("unexpected sandbox fault"));

        SandboxResult result = executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of());

        assertFalse(result.success());
        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, result.reason());
        assertFalse(String.valueOf(result.reason()).contains("unexpected sandbox fault"));
    }

    // ------------------------------------------------------------------
    // Fallback secret resolution
    // ------------------------------------------------------------------

    @Test
    void testDirectStrategyResolvesFallbackSecrets() throws Exception {
        executor.sandboxStrategy = SandboxStrategy.DIRECT;
        when(secretResolver.resolveSecrets(anyMap())).thenReturn(Map.of("K", new byte[] {1}));
        when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("direct output");

        SandboxResult result =
                executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of("K", "vault-key"));

        assertTrue(result.success());
        assertEquals("direct output", result.output());
    }

    @Test
    void testFallbackSecretResolutionFailureIsSwallowed() throws Exception {
        executor.sandboxStrategy = SandboxStrategy.DIRECT;
        when(secretResolver.resolveSecrets(anyMap())).thenThrow(new SecurityException("vault locked"));
        when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("direct output");

        SandboxResult result =
                executor.executeSandboxed("sess-1", Path.of("./target"), List.of("echo"), Map.of("K", "vault-key"));

        assertTrue(result.success());
        assertEquals("direct output", result.output());
    }

    /** Writes an executable POSIX shell script to stand in for the podman binary. */
    private static Path fakePodman(Path dir, String body) throws IOException {
        Path exe = dir.resolve("podman-" + System.nanoTime() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "fake podman must be executable");
        return exe;
    }
}
