package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Drives {@link StdioMcpTransportClient} directly through its package-private testing seams rather than through
 * {@code McpClientService} (which {@link McpStdioReconnectTest} already exercises end-to-end). That makes the
 * individual failure paths reachable: a missing writer, a missing reader, EOF, a JSON-RPC error object, stream
 * close failures, and the health-check reconnect task.
 *
 * <p><strong>Three regions are deliberately left dark, because no input reaches them.</strong>
 * <ul>
 *   <li>{@code healthCheck}'s "already restored, skipping duplicate connect" guard. The task calls
 *       {@code cleanupStdio(serverName)} first, which does {@code processes.remove(serverName)}; the very next
 *       statement reads {@code processes.get(serverName)} and can therefore only ever see {@code null}. Reaching it
 *       would need a concurrent {@code connect} for the same server, and the task holds that server's lock.
 *   <li>{@code sendNotificationStdio}'s {@code if (out == null) return;}. Its only caller is {@code connect}, which
 *       has just done {@code processWriters.put(serverName, process.getOutputStream())} — and a
 *       {@link java.util.concurrent.ConcurrentHashMap} cannot even hold a null value.
 *   <li>{@code connect}'s {@code if (e instanceof InterruptedException)}. Everything inside the {@code try} throws
 *       only {@code IOException} or unchecked types ({@code processStarter.start} declares {@code IOException};
 *       {@code sendRequestStdio} can only produce {@code IOException} or the JSON-RPC {@code RuntimeException}), so
 *       an {@code InterruptedException} cannot be the caught exception. The sibling handlers in {@code close} and
 *       {@code cleanupStdio} <em>are</em> reachable — {@code awaitTermination} and {@code waitFor} both declare it —
 *       and both are covered below.
 * </ul>
 */
class StdioMcpTransportClientTest {

    /** Two result lines: the {@code initialize} response, then the {@code tools/list} response. */
    private static final String CONNECT_NDJSON = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search"}]}}
            {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search"}]}}
            """;

    /** As above, plus the {@code tools/call} response that {@code invoke} reads third. */
    private static final String INVOKE_NDJSON = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search"}]}}
            {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search"}]}}
            {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"tool output"}]}}
            """;

    private static final String NO_SUCH_COMMAND = "/nonexistent/amsha-no-such-binary";

    private final ObjectMapper mapper = new ObjectMapper();

    private StdioMcpTransportClient newClient(
            MeterRegistry registry,
            Supplier<ManagedExecutor> executorSupplier,
            StdioMcpTransportClient.ToolRegistrationListener listener) {
        return new StdioMcpTransportClient(mapper, registry, executorSupplier, listener, new McpJsonRpcCodec(mapper));
    }

    private StdioMcpTransportClient newClient() {
        return newClient(null, null, null);
    }

    private StdioMcpTransportClient.StdioConfig config(StdioMcpTransportClient client) {
        return client.createConfig("cmd", List.of(), Map.of(), ToolCategory.NETWORK, "owner-1");
    }

    /** A process that answers every read with the supplied NDJSON and swallows what is written to it. */
    private static Process respondingProcess(String ndjson) {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.destroyForcibly()).thenReturn(process);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));
        return process;
    }

    private static Process deadProcess() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.destroyForcibly()).thenReturn(process);
        return process;
    }

    /** Records scheduled tasks instead of running them, so the reconnect body can be driven step by step. */
    private static ManagedExecutor recordingExecutor(AtomicReference<Runnable> sink) {
        ManagedExecutor executor = mock(ManagedExecutor.class);
        doAnswer(invocation -> {
                    sink.set(invocation.getArgument(0));
                    return null;
                })
                .when(executor)
                .execute(any());
        return executor;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void awaitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeoutMs + " ms");
    }

    // ---- connect -------------------------------------------------------------

    @Test
    void connectStartsTheProcessSendsTheHandshakeAndPublishesTheDiscoveredTools() {
        AtomicReference<ProcessBuilder> started = new AtomicReference<>();
        AtomicReference<String> publishedServer = new AtomicReference<>();
        AtomicReference<JsonNode> publishedTools = new AtomicReference<>();
        AtomicReference<ToolCategory> publishedCategory = new AtomicReference<>();
        StdioMcpTransportClient client = newClient(null, null, (server, tools, category, owner) -> {
            publishedServer.set(server);
            publishedTools.set(tools);
            publishedCategory.set(category);
        });
        client.setProcessStarterForTesting(pb -> {
            started.set(pb);
            return respondingProcess(CONNECT_NDJSON);
        });

        JsonNode result =
                client.connect("srv", "cmd", List.of("--flag"), Map.of("K", "V"), ToolCategory.NETWORK, "owner-1");

        assertEquals(List.of("cmd", "--flag"), started.get().command());
        assertEquals("V", started.get().environment().get("K"));
        assertEquals("srv", publishedServer.get());
        assertEquals(ToolCategory.NETWORK, publishedCategory.get());
        assertEquals(result, publishedTools.get(), "the listener must receive the tools/list result verbatim");
        assertTrue(client.hasServer("srv"));
        assertTrue(client.hasConfig("srv"));
    }

    @Test
    void connectToleratesNullArgsNullEnvironmentAndNoRegistrationListener() {
        StdioMcpTransportClient client = newClient();
        client.setProcessStarterForTesting(pb -> respondingProcess(CONNECT_NDJSON));

        client.connect("srv", "cmd", null, null, ToolCategory.NETWORK, "owner-1");

        assertTrue(client.hasServer("srv"));
        assertEquals(1, client.getConfigsForTesting().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> client.getConfigsForTesting().clear(),
                "the testing accessor must not hand out a mutable view of live state");
    }

    @Test
    void connectFailureIncrementsTheLoadFailureMetricAndWrapsTheCause() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StdioMcpTransportClient client = newClient(registry, null, null);
        IOException cause = new IOException("cannot spawn");
        client.setProcessStarterForTesting(pb -> {
            throw cause;
        });

        McpConnectionException thrown = assertThrows(
                McpConnectionException.class,
                () -> client.connect("srv", "cmd", List.of(), Map.of(), ToolCategory.NETWORK, "owner-1"));

        assertTrue(thrown.getMessage().contains("srv"));
        assertSame(cause, thrown.getCause());
        assertEquals(
                1.0,
                registry.get("amsha.mcp.tool.load.failure")
                        .tags("server", "srv", "phase", "stdio_connect")
                        .counter()
                        .count(),
                "a failed connect must be counted under its phase tag");
    }

    @Test
    void connectTearsDownAProcessThatClosesTheConnectionImmediately() {
        StdioMcpTransportClient client = newClient();
        client.setProcessStarterForTesting(pb -> respondingProcess(""));

        assertThrows(
                McpConnectionException.class,
                () -> client.connect("srv", "cmd", List.of(), Map.of(), ToolCategory.NETWORK, "owner-1"));

        assertFalse(client.hasServer("srv"), "a transport failure must not leave the process registered");
    }

    @Test
    void connectSurfacesAJsonRpcErrorObjectAsAFailure() {
        StdioMcpTransportClient client = newClient();
        client.setProcessStarterForTesting(
                pb -> respondingProcess("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601}}\n"));

        McpConnectionException thrown = assertThrows(
                McpConnectionException.class,
                () -> client.connect("srv", "cmd", List.of(), Map.of(), ToolCategory.NETWORK, "owner-1"));

        assertTrue(
                thrown.getCause().getMessage().contains("MCP server error"),
                () -> "cause was: " + thrown.getCause().getMessage());
    }

    // ---- invoke --------------------------------------------------------------

    @Test
    void invokeReconnectsAConfiguredServerThatIsNotRunning() {
        StdioMcpTransportClient client = newClient();
        AtomicInteger starts = new AtomicInteger();
        client.setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(INVOKE_NDJSON);
        });
        client.registerProcessForTesting("srv", null, null, null, config(client));

        String result = client.invoke("srv", "search", "{}");

        assertEquals("tool output", result);
        assertEquals(1, starts.get(), "a configured but stopped server must be started on demand");
    }

    @Test
    void invokeWithoutAWriterFailsAsAnIoFailureRatherThanHanging() {
        StdioMcpTransportClient client = newClient();
        client.registerProcessForTesting(
                "srv",
                respondingProcess(INVOKE_NDJSON),
                null,
                new BufferedReader(new StringReader("")),
                config(client));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.invoke("srv", "search", "{}"));

        assertTrue(thrown.getMessage().contains("search"));
        assertTrue(
                thrown.getCause().getMessage().contains("No writer"),
                () -> "cause was: " + thrown.getCause().getMessage());
    }

    @Test
    void invokeWithoutAReaderFailsAsAnIoFailure() {
        StdioMcpTransportClient client = newClient();
        client.registerProcessForTesting(
                "srv", respondingProcess(INVOKE_NDJSON), new ByteArrayOutputStream(), null, config(client));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.invoke("srv", "search", "{}"));

        assertTrue(
                thrown.getCause().getMessage().contains("No reader"),
                () -> "cause was: " + thrown.getCause().getMessage());
    }

    @Test
    void invokeOfAServerThatIsNeitherRunningNorConfiguredFailsWithoutSpawningAnything() {
        StdioMcpTransportClient client = newClient();
        AtomicInteger starts = new AtomicInteger();
        client.setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(INVOKE_NDJSON);
        });

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.invoke("ghost", "search", "{}"));

        assertEquals(0, starts.get(), "an unconfigured server must not be started on demand");
        assertTrue(
                thrown.getCause().getMessage().contains("No writer"),
                () -> "cause was: " + thrown.getCause().getMessage());
    }

    // ---- health check --------------------------------------------------------

    @Test
    void healthCheckDoesNothingWhenNoProcessIsRegistered() {
        StdioMcpTransportClient client = newClient();

        client.healthCheck();

        assertTrue(client.getProcessesForTesting().isEmpty());
    }

    @Test
    void healthCheckLeavesALiveProcessAlone() {
        StdioMcpTransportClient client = newClient();
        Process alive = respondingProcess(CONNECT_NDJSON);
        client.registerProcessForTesting(
                "srv", alive, new ByteArrayOutputStream(), new BufferedReader(new StringReader("")), config(client));

        client.healthCheck();

        assertTrue(client.hasServer("srv"), "a live process must survive a health check");
        verify(alive, never()).destroyForcibly();
    }

    @Test
    void healthCheckCleansUpADeadProcessThatHasNoConfig() {
        StdioMcpTransportClient client = newClient();
        Process dead = deadProcess();
        client.registerProcessForTesting(
                "srv", dead, new ByteArrayOutputStream(), new BufferedReader(new StringReader("")), null);

        client.healthCheck();

        assertFalse(client.hasServer("srv"), "a dead process with no config can only be torn down");
        verify(dead).destroyForcibly();
    }

    @Test
    void healthCheckQueuesOneReconnectPerServerUntilTheTaskRuns() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ManagedExecutor executor = recordingExecutor(queued);
        StdioMcpTransportClient client = newClient(null, () -> executor, null);
        client.registerProcessForTesting(
                "srv",
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                config(client));
        AtomicInteger starts = new AtomicInteger();
        client.setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(CONNECT_NDJSON);
        });

        // 1. The first sweep queues a reconnect but does not run it.
        client.healthCheck();
        Runnable task = queued.get();
        assertNotNull(task, "the first sweep must queue a reconnect");
        assertEquals(0, starts.get(), "the queued task must not have run yet");

        // 2. While that task is still in flight, a second sweep must skip the server rather than queue a duplicate.
        queued.set(null);
        client.healthCheck();
        assertNull(queued.get(), "a server already being reconnected must not be queued twice");
        assertEquals(0, starts.get());

        // 3. Running the task reconnects and releases the in-flight guard.
        task.run();
        assertEquals(1, starts.get());
        assertTrue(client.hasServer("srv"));

        // 4. Break the server again. If the guard had leaked, this sweep would be skipped; because the task's
        //    finally block released it, a fresh reconnect is queued.
        client.registerProcessForTesting(
                "srv",
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                config(client));
        queued.set(null);
        client.healthCheck();
        assertNotNull(queued.get(), "the in-flight guard must have been released when the task finished");
    }

    @Test
    void healthCheckRunsTheReconnectInlineWhenNoExecutorSupplierIsConfigured() {
        StdioMcpTransportClient client = newClient(null, null, null);
        client.registerProcessForTesting(
                "srv",
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                config(client));
        AtomicInteger starts = new AtomicInteger();
        client.setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(CONNECT_NDJSON);
        });

        client.healthCheck();

        assertEquals(1, starts.get(), "without an executor the reconnect must run on the calling thread");
        assertTrue(client.hasServer("srv"));
    }

    @Test
    void healthCheckSurvivesAReconnectThatThrows() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ManagedExecutor executor = recordingExecutor(queued);
        StdioMcpTransportClient client = newClient(null, () -> executor, null);
        client.registerProcessForTesting(
                "srv",
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                config(client));
        client.setProcessStarterForTesting(pb -> {
            throw new IOException("spawn failed");
        });

        client.healthCheck();

        assertDoesNotThrow(() -> queued.get().run(), "a failed reconnect must be logged, not propagated");
        assertFalse(client.hasServer("srv"));
    }

    // ---- init / close --------------------------------------------------------

    @Test
    void initIgnoresNonPositiveIntervals() {
        StdioMcpTransportClient client = newClient();

        client.init(0);
        client.init(-5);

        assertDoesNotThrow(client::close, "closing a client that never started a scheduler must be a no-op");
    }

    @Test
    void initSchedulesHealthChecksAndCloseStopsThem() throws Exception {
        StdioMcpTransportClient client = newClient();
        client.registerProcessForTesting(
                "srv",
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                config(client));
        AtomicInteger starts = new AtomicInteger();
        client.setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(CONNECT_NDJSON);
        });

        client.init(20);
        awaitUntil(() -> starts.get() > 0, 5_000);

        assertTrue(client.hasServer("srv"), "the scheduled health check must have reconnected the server");
        client.close();
    }

    @Test
    void closeForcesShutdownWhenTheExecutorRefusesToTerminate() throws Exception {
        StdioMcpTransportClient client = newClient();
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.awaitTermination(2, TimeUnit.SECONDS)).thenReturn(false);
        setField(client, "healthCheckExecutor", executor);

        client.close();

        verify(executor).shutdown();
        verify(executor).shutdownNow();
    }

    @Test
    void closeForcesShutdownAndRestoresInterruptsWhenWaitingIsInterrupted() throws Exception {
        StdioMcpTransportClient client = newClient();
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.awaitTermination(2, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        setField(client, "healthCheckExecutor", executor);

        try {
            client.close();
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }
        verify(executor).shutdownNow();
    }

    @Test
    void closeTearsDownEveryRegisteredStream() {
        StdioMcpTransportClient client = newClient();
        client.registerProcessForTesting(
                "with-process",
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                null);
        client.registerProcessForTesting("writer-only", null, new ByteArrayOutputStream(), null, null);
        client.registerProcessForTesting("reader-only", null, null, new BufferedReader(new StringReader("")), null);

        client.close();

        assertTrue(client.getProcessesForTesting().isEmpty(), "close must drain the process map");
    }

    // ---- cleanup -------------------------------------------------------------

    @Test
    void cleanupSwallowsStreamCloseFailuresAndStillDestroysTheProcess() throws Exception {
        StdioMcpTransportClient client = newClient();
        Process process = deadProcess();
        OutputStream writer = mock(OutputStream.class);
        BufferedReader reader = mock(BufferedReader.class);
        doThrow(new IOException("writer closed")).when(writer).close();
        doThrow(new IOException("reader closed")).when(reader).close();
        client.registerProcessForTesting("srv", process, writer, reader, null);

        assertDoesNotThrow(() -> client.cleanup("srv"));

        verify(process).destroyForcibly();
    }

    @Test
    void cleanupRestoresInterruptsWhenWaitingForTheProcessIsInterrupted() throws Exception {
        StdioMcpTransportClient client = newClient();
        Process process = deadProcess();
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        client.registerProcessForTesting("srv", process, new ByteArrayOutputStream(), null, null);

        try {
            client.cleanup("srv");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }
        verify(process).destroyForcibly();
    }

    // ---- testing seams -------------------------------------------------------

    @Test
    void setProcessStarterForTestingFallsBackToTheRealStarterWhenGivenNull() {
        StdioMcpTransportClient client = newClient();
        client.setProcessStarterForTesting(pb -> respondingProcess(CONNECT_NDJSON));
        client.setProcessStarterForTesting(null);

        // The real ProcessBuilder.start() is now installed, so a command that cannot exist fails as an IOException
        // rather than being served by the stub above.
        McpConnectionException thrown = assertThrows(
                McpConnectionException.class,
                () -> client.connect(NO_SUCH_COMMAND, "cmd", List.of(), Map.of(), ToolCategory.NETWORK, "o"));

        assertInstanceOf(IOException.class, thrown.getCause());
        assertFalse(client.hasServer(NO_SUCH_COMMAND));
    }

    @Test
    void registerProcessForTestingIgnoresNullComponents() {
        StdioMcpTransportClient client = newClient();

        client.registerProcessForTesting("nothing", null, null, null, null);
        assertTrue(client.getProcessesForTesting().isEmpty());
        assertTrue(client.getConfigsForTesting().isEmpty());

        Process process = respondingProcess(CONNECT_NDJSON);
        client.registerProcessForTesting("only-process", process, null, null, null);
        assertEquals(process, client.getProcessesForTesting().get("only-process"));
    }
}
