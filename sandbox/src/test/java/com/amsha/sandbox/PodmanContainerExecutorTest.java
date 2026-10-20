package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.common.util.PathSanitizer;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Unit tests for {@link PodmanContainerExecutor}.
 *
 * <p>The container arms are driven with a <b>fake {@code podman} executable</b> — a POSIX shell
 * script written to a temp directory and marked executable — rather than a mocked
 * {@code ProcessBuilder}. The production code only ever calls {@code toString()} on the configured
 * binary path, so a script lets the real {@code Process} plumbing (start, drain, wait, exit code)
 * run end to end. The OpenTelemetry child span is mocked, since no tracer is registered here.
 */
@ExtendWith(MockitoExtension.class)
class PodmanContainerExecutorTest {

    @Mock
    AgentOtelContext otelContext;

    @Mock
    SandboxSecretResolver secretResolver;

    @InjectMocks
    PodmanContainerExecutor executor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        executor.configuredPodmanImage = Optional.of("amsha-tool-runner:latest");
        executor.podmanPath = "podman-invalid-path-12345";
        executor.timeoutSeconds = 5;
        executor.networkTimeoutSeconds = 10;
        executor.containerMemoryLimit = "512m";
        executor.containerCpuLimit = "0.5";
        executor.containerNetwork = "bridge";
        executor.toolRunnerUser = "sandbox";
        executor.workspaceRootDir = "./target/test-workspaces";
    }

    @Test
    void testShutdownCleanWhenNoContainers() {
        assertDoesNotThrow(() -> executor.stopAllContainersOnShutdown());
    }

    @Test
    void testOnStartupAndShutdownWithActiveContainer() throws Exception {
        assertDoesNotThrow(() -> executor.onStartup(null));

        Field f = PodmanContainerExecutor.class.getDeclaredField("activeContainerNames");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) f.get(executor);
        set.add("dummy-container");

        assertDoesNotThrow(() -> executor.stopAllContainersOnShutdown());
        assertTrue(set.isEmpty());
    }

    @Test
    void testExecuteInContainerFailsWhenPodmanUnavailable() {
        when(secretResolver.resolveSecrets(anyMap())).thenReturn(Map.of());

        assertThrows(
                PodmanUnavailableException.class,
                () -> executor.executeInContainer("sess-123", Path.of("./target"), List.of("echo", "hi"), Map.of()));
    }

    @Test
    void testResolveTimeout() throws Exception {
        Method resolveTimeout = PodmanContainerExecutor.class.getDeclaredMethod("resolveTimeout", List.class);
        resolveTimeout.setAccessible(true);

        long tNormal = (long) resolveTimeout.invoke(executor, List.of("echo", "hello"));
        assertEquals(5, tNormal);

        long tNetwork = (long) resolveTimeout.invoke(executor, List.of("curl", "https://example.com"));
        assertEquals(10, tNetwork);
    }

    @Test
    void testBuildPodmanCmd() throws Exception {
        Method buildCmd = PodmanContainerExecutor.class.getDeclaredMethod(
                "buildPodmanCmd", String.class, List.class, Path.class, List.class);
        buildCmd.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> cmd = (List<String>) buildCmd.invoke(
                executor, "container-1", List.of("--env", "FOO=BAR"), Path.of("/tmp/ws"), List.of("ls", "-la"));

        assertTrue(cmd.contains("run"));
        assertTrue(cmd.contains("container-1"));
        assertTrue(cmd.contains("--memory=512m"));
        assertTrue(cmd.contains("--cpus=0.5"));
        assertTrue(cmd.contains("ls"));
    }

    @Test
    void testReadSandboxOutputSuccess() throws Exception {
        Process mockProc = mock(Process.class);
        when(mockProc.getInputStream())
                .thenReturn(new ByteArrayInputStream("output text\n".getBytes(StandardCharsets.UTF_8)));
        when(mockProc.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mockProc.waitFor(anyLong(), any())).thenReturn(true);
        when(mockProc.exitValue()).thenReturn(0);

        Method readOutput = PodmanContainerExecutor.class.getDeclaredMethod(
                "readSandboxOutputAndGetResult", Process.class, List.class);
        readOutput.setAccessible(true);

        String res = (String) readOutput.invoke(executor, mockProc, List.of("echo", "hi"));
        assertEquals("output text\n", res);
    }

    @Test
    void testReadSandboxOutputFailureExitCode() throws Exception {
        Process mockProc = mock(Process.class);
        when(mockProc.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mockProc.getErrorStream())
                .thenReturn(new ByteArrayInputStream("error details".getBytes(StandardCharsets.UTF_8)));
        when(mockProc.waitFor(anyLong(), any())).thenReturn(true);
        when(mockProc.exitValue()).thenReturn(1);

        Method readOutput = PodmanContainerExecutor.class.getDeclaredMethod(
                "readSandboxOutputAndGetResult", Process.class, List.class);
        readOutput.setAccessible(true);

        Exception ex = assertThrows(Exception.class, () -> readOutput.invoke(executor, mockProc, List.of("echo")));
        assertTrue(ex.getCause() instanceof ToolExecutionException);
    }

    @Test
    void testReadSandboxOutputExit125AndMissingImage() throws Exception {
        Method readOutput = PodmanContainerExecutor.class.getDeclaredMethod(
                "readSandboxOutputAndGetResult", Process.class, List.class);
        readOutput.setAccessible(true);

        // Exit 125
        Process proc125 = mock(Process.class);
        when(proc125.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(proc125.getErrorStream())
                .thenReturn(new ByteArrayInputStream("engine error".getBytes(StandardCharsets.UTF_8)));
        when(proc125.waitFor(anyLong(), any())).thenReturn(true);
        when(proc125.exitValue()).thenReturn(125);

        Exception ex1 = assertThrows(Exception.class, () -> readOutput.invoke(executor, proc125, List.of("echo")));
        assertTrue(ex1.getCause() instanceof IOException);

        // Missing image ("not found")
        Process procMissing = mock(Process.class);
        when(procMissing.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(procMissing.getErrorStream())
                .thenReturn(new ByteArrayInputStream("image not found on host".getBytes(StandardCharsets.UTF_8)));
        when(procMissing.waitFor(anyLong(), any())).thenReturn(true);
        when(procMissing.exitValue()).thenReturn(1);

        Exception ex2 = assertThrows(Exception.class, () -> readOutput.invoke(executor, procMissing, List.of("echo")));
        assertTrue(ex2.getCause() instanceof SandboxImageMissingException);
    }

    // ------------------------------------------------------------------
    // Engine / image availability probes
    // ------------------------------------------------------------------

    @Test
    void testCheckPodmanAvailableAcceptsAHealthyEngine() throws Exception {
        executor.podmanPath = healthyPodman().toString();
        Method check = declared("checkPodmanAvailable");

        assertDoesNotThrow(() -> check.invoke(executor));
        // A second call inside the 60s recheck window returns early on the cached healthy result.
        assertDoesNotThrow(() -> check.invoke(executor));
    }

    @Test
    void testCheckPodmanAvailableReportsNonZeroExitAndShortCircuitsTheRecheckWindow() throws Exception {
        executor.podmanPath = fakePodman("exit 3").toString();
        Method check = declared("checkPodmanAvailable");

        Exception first = assertThrows(Exception.class, () -> check.invoke(executor));
        assertTrue(first.getCause() instanceof PodmanUnavailableException);
        assertTrue(first.getCause().getMessage().contains("podman info failed or timed out"));

        // The second call lands inside the 60s recheck window and must not re-probe the binary.
        Exception second = assertThrows(Exception.class, () -> check.invoke(executor));
        assertTrue(second.getCause() instanceof PodmanUnavailableException);
        assertTrue(second.getCause().getMessage().contains("recently checked and unavailable"));
    }

    @Test
    void testCheckImageAvailableReportsAMissingImage() throws Exception {
        executor.podmanPath = fakePodman("""
                case "$1" in
                  info) exit 0 ;;
                  image) exit 1 ;;
                esac
                exit 0
                """).toString();
        when(secretResolver.resolveSecrets(anyMap())).thenReturn(Map.of());

        Exception ex = assertThrows(
                Exception.class,
                () -> executor.executeInContainer("sess-no-image", tempDir, List.of("echo"), Map.of()));

        assertTrue(ex instanceof SandboxImageMissingException, "unexpected: " + ex);
        assertTrue(ex.getMessage().contains("missing from Podman storage"));
    }

    @Test
    void testCheckImageAvailableMapsIoFailureToAMissingImage() throws Exception {
        executor.podmanPath = "podman-invalid-path-12345";
        Method check = declared("checkImageAvailable");

        Exception ex = assertThrows(Exception.class, () -> check.invoke(executor));

        assertTrue(ex.getCause() instanceof SandboxImageMissingException);
        assertTrue(ex.getCause().getMessage().contains("Failed to check sandbox image"));
    }

    // ------------------------------------------------------------------
    // Full execution path
    // ------------------------------------------------------------------

    @Test
    void testExecuteInContainerRunsTheGovernedPathWhenPodmanIsHealthy() throws Exception {
        executor.podmanPath = healthyPodman().toString();
        Path workspaceRoot = tempDir.resolve("workspaces");
        executor.workspaceRootDir = workspaceRoot.toString();
        when(secretResolver.resolveSecrets(anyMap()))
                .thenReturn(Map.of("GH_TOKEN", "s3cr3t".getBytes(StandardCharsets.UTF_8)));
        Span span = stubChildSpan();

        String result = executor.executeInContainer(
                "sess-happy", tempDir.resolve("ws"), List.of("echo", "hi"), Map.of("GH_TOKEN", "gh"));

        assertEquals("tool output\n", result);
        verify(span).setAttribute(AgentOTelConstants.ATTR_CONTAINER_IMAGE, executor.getSandboxImage());
        verify(span).setStatus(StatusCode.OK);
        verify(span).end();
        assertTrue(
                Files.isDirectory(workspaceRoot.resolve(PathSanitizer.sanitizeForFilesystem("sess-happy"))),
                "the missing session workspace should have been recreated");
        assertTrue(activeContainers().isEmpty(), "container tracking must be released after the run");
    }

    @Test
    void testExecuteInContainerFailsWhenTheWorkspaceCannotBeCreated() throws Exception {
        executor.podmanPath = healthyPodman().toString();
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        executor.workspaceRootDir = blocker.toString();
        when(secretResolver.resolveSecrets(anyMap())).thenReturn(Map.of());
        stubChildSpan();

        Exception ex = assertThrows(
                Exception.class, () -> executor.executeInContainer("sess-blocked", tempDir, List.of("echo"), Map.of()));

        assertTrue(ex instanceof IOException, "unexpected: " + ex);
        assertTrue(ex.getMessage().contains("Failed to create workspace directory"));
    }

    // ------------------------------------------------------------------
    // Output draining
    // ------------------------------------------------------------------

    @Test
    void testReadSandboxOutputTimesOutWhenTheProcessNeverFinishes() throws Exception {
        Process proc = mock(Process.class);
        CountDownLatch drainsStarted = new CountDownLatch(2);
        when(proc.getInputStream()).thenAnswer(_ -> {
            drainsStarted.countDown();
            return new ByteArrayInputStream(new byte[0]);
        });
        when(proc.getErrorStream()).thenAnswer(_ -> {
            drainsStarted.countDown();
            return new ByteArrayInputStream(new byte[0]);
        });
        when(proc.waitFor(anyLong(), any())).thenAnswer(_ -> {
            assertTrue(drainsStarted.await(5, TimeUnit.SECONDS), "both output drains should start before timeout");
            return false;
        });

        Exception ex = assertThrows(Exception.class, () -> readOutputMethod().invoke(executor, proc, List.of("echo")));

        assertTrue(ex.getCause() instanceof TimeoutException);
        assertTrue(ex.getCause().getMessage().contains("timed out after"));
        verify(proc).destroyForcibly();
    }

    @Test
    void testReadSandboxOutputFallsBackToStderrWhenTheStdoutDrainFails() throws Exception {
        Process proc = mock(Process.class);
        when(proc.getInputStream()).thenReturn(throwingOnRead());
        when(proc.getErrorStream())
                .thenReturn(new ByteArrayInputStream("stderr text".getBytes(StandardCharsets.UTF_8)));
        when(proc.waitFor(anyLong(), any())).thenReturn(true);
        when(proc.exitValue()).thenReturn(0);

        assertEquals("stderr text", readOutputMethod().invoke(executor, proc, List.of("echo")));
    }

    @Test
    void testReadSandboxOutputFallsBackToStdoutWhenTheStderrDrainFails() throws Exception {
        Process proc = mock(Process.class);
        when(proc.getInputStream())
                .thenReturn(new ByteArrayInputStream("stdout text".getBytes(StandardCharsets.UTF_8)));
        when(proc.getErrorStream()).thenReturn(throwingOnRead());
        when(proc.waitFor(anyLong(), any())).thenReturn(true);
        when(proc.exitValue()).thenReturn(0);

        assertEquals("stdout text", readOutputMethod().invoke(executor, proc, List.of("echo")));
    }

    @Test
    void testReadSandboxOutputSurvivesAFailedOutputRetrieval() throws Exception {
        Process proc = mock(Process.class);
        when(proc.getInputStream()).thenThrow(new IllegalStateException("stream unavailable"));
        when(proc.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(proc.waitFor(anyLong(), any())).thenReturn(true);
        when(proc.exitValue()).thenReturn(0);

        assertEquals("(tool returned no output)", readOutputMethod().invoke(executor, proc, List.of("echo")));
    }

    // ------------------------------------------------------------------
    // Shutdown + command construction
    // ------------------------------------------------------------------

    @Test
    void testStopAllContainersOnShutdownStopsEveryTrackedContainer() throws Exception {
        executor.podmanPath = healthyPodman().toString();
        activeContainers().add("zc-abc12345");

        assertDoesNotThrow(() -> executor.stopAllContainersOnShutdown());

        assertTrue(activeContainers().isEmpty());
    }

    @Test
    void testStopAllContainersOnShutdownWarnsWhenTheStopExitsNonZero() throws Exception {
        executor.podmanPath = fakePodman("exit 1").toString();
        activeContainers().add("zc-def67890");

        assertDoesNotThrow(() -> executor.stopAllContainersOnShutdown());

        assertTrue(activeContainers().isEmpty());
    }

    @Test
    void testBuildPodmanCmdOmitsBlankResourceLimits() throws Exception {
        executor.containerMemoryLimit = "   ";
        executor.containerCpuLimit = null;
        Method buildCmd = declared("buildPodmanCmd", String.class, List.class, Path.class, List.class);

        @SuppressWarnings("unchecked")
        List<String> cmd = (List<String>) buildCmd.invoke(executor, "c1", List.of(), Path.of("/tmp/ws"), List.of("ls"));

        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("--memory=")));
        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("--cpus=")));
    }

    @Test
    void testResolveTimeoutFallsBackToTheStandardBudgetForAnEmptyCommand() throws Exception {
        Method resolveTimeout = declared("resolveTimeout", List.class);

        long resolved = (long) resolveTimeout.invoke(executor, List.of());

        assertEquals(5, resolved);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Method declared(String name, Class<?>... params) throws Exception {
        Method method = PodmanContainerExecutor.class.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    private Method readOutputMethod() throws Exception {
        return declared("readSandboxOutputAndGetResult", Process.class, List.class);
    }

    @SuppressWarnings("unchecked")
    private Set<String> activeContainers() throws Exception {
        Field field = PodmanContainerExecutor.class.getDeclaredField("activeContainerNames");
        field.setAccessible(true);
        return (Set<String>) field.get(executor);
    }

    private Span stubChildSpan() {
        Span span = mock(Span.class);
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(span);
        return span;
    }

    /** Writes an executable POSIX shell script to use in place of the real podman binary. */
    private Path fakePodman(String body) throws IOException {
        Path exe = tempDir.resolve("podman-" + UUID.randomUUID() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "fake podman must be executable");
        return exe;
    }

    /** A fake podman reporting a healthy engine, a present image, and a successful tool run. */
    private Path healthyPodman() throws IOException {
        return fakePodman("""
                case "$1" in
                  info) exit 0 ;;
                  image) exit 0 ;;
                  run) echo "tool output" ;;
                  stop) exit 0 ;;
                esac
                exit 0
                """);
    }

    private static InputStream throwingOnRead() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("drain boom");
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                throw new IOException("drain boom");
            }
        };
    }
}
