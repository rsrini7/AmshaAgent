package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DirectExecutor}, the un-isolated host-execution strategy.
 *
 * <p>Most process arms use real short-lived children to exercise process start, drain, wait,
 * and termination. The stalled-reader test uses a controlled process stream to avoid relying on
 * shell background-job behavior across platforms. Private helpers ({@code waitForProcess},
 * {@code terminateProcess}, {@code canRun}) are reached reflectively where useful.
 * <p><b>Platform-locked residue:</b> the {@code python3} → {@code python} Microsoft Store shim fix
 * (lines 128-130) only fires when {@code python3} is absent from {@code PATH}. The surrounding branch
 * is exercised here with {@code os.name} forced to Windows, but the swap itself needs a host without
 * a working {@code python3}. Reported, not faked.
 */
class DirectExecutorTest {

    @Test
    void testDirectExecutionEcho() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("direct-exec-test");

        String output = executor.executeDirectly(
                tempDir, List.of("echo", "direct_ok"), Map.of("KEY", "VAL".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(output);
        assertTrue(output.contains("direct_ok"));
    }

    @Test
    void testDirectExecutionInvalidCommand() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("direct-exec-err");

        assertThrows(Exception.class, () -> {
            executor.executeDirectly(tempDir, List.of("non_existent_binary_xyz_123"), Map.of());
        });
    }

    // ------------------------------------------------------------------
    // Process lifecycle arms
    // ------------------------------------------------------------------

    @Test
    void testDirectExecutionRecreatesAMissingWorkspace() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 5;
        Path workspace = Files.createTempDirectory("direct-exec-ws").resolve("missing");
        assertFalse(Files.exists(workspace));

        String output = executor.executeDirectly(workspace, List.of("echo", "recreated"), Map.of());

        assertTrue(output.contains("recreated"));
        assertTrue(Files.isDirectory(workspace), "the missing workspace should have been recreated");
    }

    @Test
    void testDirectExecutionTimesOut() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 0; // waitFor(0, SECONDS) returns false immediately
        Path tempDir = Files.createTempDirectory("direct-exec-timeout");

        SecurityException ex = assertThrows(
                SecurityException.class, () -> executor.executeDirectly(tempDir, List.of("sleep", "5"), Map.of()));

        assertTrue(ex.getMessage().contains("Tool timed out"));
    }

    @Test
    void testDirectExecutionNonZeroExitRaisesToolExecutionException() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("direct-exec-fail");

        ToolExecutionException ex = assertThrows(
                ToolExecutionException.class,
                () -> executor.executeDirectly(tempDir, List.of("sh", "-c", "echo boom; exit 3"), Map.of()));

        assertEquals(3, ex.exitCode());
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void testDirectExecutionReapsAStalledOutputReader() throws Exception {
        Process process = mock(Process.class);
        ProcessHandle handle = mock(ProcessHandle.class);
        when(process.toHandle()).thenReturn(handle);
        when(handle.descendants()).thenAnswer(_ -> Stream.empty());
        when(process.waitFor(anyLong(), any())).thenReturn(true);
        when(process.destroyForcibly()).thenReturn(process);

        PipedInputStream stdout = new PipedInputStream();
        PipedOutputStream stdoutWriter = new PipedOutputStream(stdout);
        when(process.getInputStream()).thenReturn(stdout);

        DirectExecutor executor = new DirectExecutor() {
            @Override
            Process startProcess(ProcessBuilder processBuilder) {
                assertNotNull(processBuilder);
                return process;
            }
        };
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("direct-exec-stall");

        try {
            // Keep stdout open after the mocked process exits; this deterministically stalls the
            // drain reader without relying on host-shell background-process behavior.
            SecurityException ex = assertThrows(
                    SecurityException.class, () -> executor.executeDirectly(tempDir, List.of("echo"), Map.of()));
            assertTrue(ex.getMessage().contains("Tool timed out"));
        } finally {
            stdoutWriter.close();
        }
    }

    @Test
    void testDirectExecutionPropagatesInterruption() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 30;
        Path tempDir = Files.createTempDirectory("direct-exec-interrupt");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        executor.executeDirectly(tempDir, List.of("sleep", "10"), Map.of());
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                },
                "direct-exec-interrupt-test");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(400L);
        worker.interrupt();
        worker.join(10_000L);

        assertFalse(worker.isAlive(), "worker must not be left blocked");
        assertTrue(failure.get() instanceof InterruptedException, "unexpected failure: " + failure.get());
    }

    // ------------------------------------------------------------------
    // Output handling
    // ------------------------------------------------------------------

    @Test
    void testDirectExecutionSurvivesFailedDescendantEnumerationAndOutputClose() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        Process proc = mock(Process.class);
        ProcessHandle handle = mock(ProcessHandle.class);
        when(proc.toHandle()).thenReturn(handle);
        when(handle.descendants()).thenThrow(new RuntimeException("process enumeration denied"));
        InputStream unclosable = mock(InputStream.class);
        doThrow(new IOException("close refused")).when(unclosable).close();
        when(proc.getInputStream()).thenReturn(unclosable);

        Method terminate = DirectExecutor.class.getDeclaredMethod("terminateProcess", Process.class, Thread.class);
        terminate.setAccessible(true);

        assertDoesNotThrow(() -> terminate.invoke(executor, proc, null));
        verify(proc).destroyForcibly();
    }

    @Test
    void testDirectExecutionDropsAFailedOutputDrain() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 30;
        Process proc = mock(Process.class);
        when(proc.getInputStream()).thenReturn(throwingOnRead());
        when(proc.waitFor(anyLong(), any())).thenReturn(true);

        Method waitFor = DirectExecutor.class.getDeclaredMethod("waitForProcess", Process.class);
        waitFor.setAccessible(true);

        Object outcome = waitFor.invoke(executor, proc);

        assertEquals(true, readAccessor(outcome, "finished"));
        assertEquals("", readAccessor(outcome, "output"));
    }

    // ------------------------------------------------------------------
    // Windows command fixup
    // ------------------------------------------------------------------

    @Test
    void testWindowsFixupLeavesAWorkingPython3Alone() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        Method fixup = DirectExecutor.class.getDeclaredMethod("fixupCommandForWindows", List.class);
        fixup.setAccessible(true);

        withOsName("Windows 11", () -> {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) fixup.invoke(executor, List.of("python3", "x.py"));
            assertEquals(List.of("python3", "x.py"), result);
        });
    }

    @Test
    void testCanRunDistinguishesAvailableFromMissingBinaries() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        Method canRun = DirectExecutor.class.getDeclaredMethod("canRun", String.class);
        canRun.setAccessible(true);

        assertEquals(true, canRun.invoke(executor, "python3"));
        assertEquals(false, canRun.invoke(executor, "non_existent_binary_xyz_123"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Runs {@code body} with {@code os.name} forced, restoring the previous value afterwards. Surefire
     * reuses one JVM per module, so leaking the override would corrupt unrelated tests.
     */
    private static void withOsName(String osName, ThrowingRunnable body) throws Exception {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", osName);
            body.run();
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Object readAccessor(Object target, String name) throws Exception {
        Method accessor = target.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(target);
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
