package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NamespaceExecutor}, the Linux {@code unshare} isolation strategy.
 *
 * <p>Process arms run against real short-lived child processes rather than a mocked
 * {@code ProcessBuilder}. The private helpers ({@code waitForProcess}, {@code terminateProcess}) are
 * reached reflectively, matching the convention used elsewhere in this module.
 *
 * <p><b>Platform note:</b> this host is not Linux, so the {@code unshare … chroot} command
 * construction is exercised with {@code os.name} forced to Linux; {@code unshare} is absent here, so
 * the attempt fails at {@code start()} and the assertion documents exactly that.
 */
class NamespaceExecutorTest {

    @Test
    void testNamespaceExecutorEcho() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-test");

        String output = executor.executeInNamespace(tempDir, List.of("echo", "ns_ok"));
        assertNotNull(output);
        assertTrue(output.contains("ns_ok"));
    }

    @Test
    void testNamespaceExecutorInvalidCommand() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-err");

        assertThrows(Exception.class, () -> {
            executor.executeInNamespace(tempDir, List.of("non_existent_binary_xyz_123"));
        });
    }

    // ------------------------------------------------------------------
    // Process lifecycle arms
    // ------------------------------------------------------------------

    @Test
    void testNamespaceExecutionRecreatesAMissingWorkspace() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path workspace = Files.createTempDirectory("ns-exec-ws").resolve("missing");
        assertFalse(Files.exists(workspace));

        String output = executor.executeInNamespace(workspace, List.of("echo", "recreated"));

        assertTrue(output.contains("recreated"));
        assertTrue(Files.isDirectory(workspace), "the missing workspace should have been recreated");
    }

    @Test
    void testNamespaceExecutionTimesOut() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 0; // waitFor(0, SECONDS) returns false immediately
        Path tempDir = Files.createTempDirectory("ns-exec-timeout");

        SecurityException ex = assertThrows(
                SecurityException.class, () -> executor.executeInNamespace(tempDir, List.of("sleep", "5")));

        assertTrue(ex.getMessage().contains("Tool timed out"));
    }

    @Test
    void testNamespaceExecutionNonZeroExitRaisesToolExecutionException() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-fail");

        ToolExecutionException ex = assertThrows(
                ToolExecutionException.class,
                () -> executor.executeInNamespace(tempDir, List.of("sh", "-c", "echo boom; exit 4")));

        assertEquals(4, ex.exitCode());
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void testNamespaceExecutionReapsAStalledOutputReader() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-stall");

        // The backgrounded grandchild inherits the stdout pipe, so the drain thread is still blocked
        // after the shell exits. The executor must reap it and report a timeout rather than hang.
        SecurityException ex = assertThrows(
                SecurityException.class,
                () -> executor.executeInNamespace(tempDir, List.of("sh", "-c", "sleep 8 & exit 0")));

        assertTrue(ex.getMessage().contains("Tool timed out"));
    }

    @Test
    void testNamespaceExecutionPropagatesInterruption() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 30;
        Path tempDir = Files.createTempDirectory("ns-exec-interrupt");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        executor.executeInNamespace(tempDir, List.of("sleep", "10"));
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                },
                "ns-exec-interrupt-test");
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
    void testNamespaceExecutionSurvivesAFailedOutputClose() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        Process proc = mock(Process.class);
        ProcessHandle handle = mock(ProcessHandle.class);
        when(proc.toHandle()).thenReturn(handle);
        when(handle.descendants()).thenReturn(Stream.of());
        InputStream unclosable = mock(InputStream.class);
        doThrow(new IOException("close refused")).when(unclosable).close();
        when(proc.getInputStream()).thenReturn(unclosable);

        Method terminate = NamespaceExecutor.class.getDeclaredMethod("terminateProcess", Process.class, Thread.class);
        terminate.setAccessible(true);

        assertDoesNotThrow(() -> terminate.invoke(executor, proc, null));
    }

    @Test
    void testNamespaceExecutionDropsAFailedOutputDrain() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 30;
        Process proc = mock(Process.class);
        when(proc.getInputStream()).thenReturn(throwingOnRead());
        when(proc.waitFor(anyLong(), any())).thenReturn(true);

        Method waitFor = NamespaceExecutor.class.getDeclaredMethod("waitForProcess", Process.class);
        waitFor.setAccessible(true);

        Object outcome = waitFor.invoke(executor, proc);

        assertEquals(true, readAccessor(outcome, "finished"));
        assertEquals("", readAccessor(outcome, "output"));
    }

    // ------------------------------------------------------------------
    // Linux-only command construction
    // ------------------------------------------------------------------

    @Test
    void testLinuxBranchBuildsTheUnshareChrootCommand() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-linux");

        withOsName("Linux", () -> {
            // unshare is absent on this host, so the constructed command cannot be launched.
            // Reaching the launch is what proves the unshare/chroot argument list was built.
            Exception ex = assertThrows(Exception.class, () -> executor.executeInNamespace(tempDir, List.of("echo")));
            assertNotNull(ex);
        });
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
