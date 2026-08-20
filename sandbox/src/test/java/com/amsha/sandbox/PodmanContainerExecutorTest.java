package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.observability.AgentOtelContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PodmanContainerExecutorTest {

    @Mock
    AgentOtelContext otelContext;

    @Mock
    SandboxSecretResolver secretResolver;

    @InjectMocks
    PodmanContainerExecutor executor;

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
}
