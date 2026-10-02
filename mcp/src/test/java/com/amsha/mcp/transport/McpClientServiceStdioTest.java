package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Covers the STDIO half of {@link McpClientService} — the {@code connectStdio} overload chain, owner resolution, and
 * the managed-executor selection that the STDIO health-check reconnect depends on.
 *
 * <p>{@link McpClientServiceTest} exercises the HTTP path through WireMock. This class needs no server at all: the
 * service builds its own {@link StdioMcpTransportClient} in {@code init()}, and that transport exposes
 * {@code setProcessStarterForTesting} / {@code registerProcessForTesting}, so a hand-written fake process drives the
 * whole handshake. Keeping the two halves apart means the STDIO assertions stay deterministic while the HTTP ones
 * keep using the stub server the existing class is already built around.</p>
 */
class McpClientServiceStdioTest {

    /** The {@code initialize} response, then the {@code tools/list} response. */
    private static final String CONNECT_NDJSON = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search"}]}}
            {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search"}]}}
            """;

    private McpClientService service;
    private DynamicToolCapabilityRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new McpClientService();
        registry = new DynamicToolCapabilityRegistry();
        service.registry = registry;
        service.objectMapper = new ObjectMapper();
        service.meterRegistry = new SimpleMeterRegistry();
        service.managedExecutors = mock(Instance.class);
        // healthCheckIntervalMs keeps its primitive default of 0 outside CDI, so init() starts no scheduler thread.
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.cleanup();
    }

    // ---- owner resolution ----------------------------------------------------

    @Test
    void connectStdioRejectsANullOwner() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.connectStdio("srv", "cmd", List.of(), Map.of(), ToolCategory.NETWORK, null));
    }

    @Test
    void connectStdioRejectsABlankOwner() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.connectStdio("srv", "cmd", List.of(), Map.of(), ToolCategory.NETWORK, "   "));
    }

    @Test
    void connectStdioRejectsTheUnknownPlaceholderOwner() {
        // "unknown" is what WorkspaceContext falls back to when no user is bound, so it must never be treated as a
        // real owner: an unbound caller must not be able to register a local process against a shared scope.
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> service.connectStdio("srv", "cmd", List.of(), Map.of(), ToolCategory.NETWORK, "unknown"));

        assertTrue(thrown.getMessage().contains("bound user owner"), thrown.getMessage());
    }

    @Test
    void connectStdioRejectsANullCategoryBeforeStartingAnyProcess() {
        AtomicInteger starts = new AtomicInteger();
        service.stdioTransport().setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(CONNECT_NDJSON);
        });

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> service.connectStdio("srv", "cmd", List.of(), Map.of(), null, "user-1"));

        assertTrue(thrown.getMessage().contains("category must not be null"), thrown.getMessage());
        assertEquals(0, starts.get(), "an invalid category must be rejected before a process is spawned");
    }

    @Test
    void connectStdioResolvesTheOwnerFromTheWorkspaceWhenNoneIsGiven() throws Exception {
        stubProcessStarter();

        WorkspaceContext.run("sess-1", "user-9", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            service.connectStdio("workspace-server", "cmd", List.of(), Map.of(), ToolCategory.NETWORK);
            return null;
        });

        assertEquals(
                "user-9",
                service.stdioTransport()
                        .getConfigsForTesting()
                        .get("workspace-server")
                        .ownerUserId(),
                "the 5-arg overload must bind the connection to the ambient workspace user");
    }

    @Test
    void connectStdioDefaultsToTheNetworkCategory() throws Exception {
        stubProcessStarter();

        WorkspaceContext.run("sess-2", "user-9", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            service.connectStdio("default-category-server", "cmd", List.of(), Map.of());
            return null;
        });

        assertEquals(
                ToolCategory.NETWORK,
                service.stdioTransport()
                        .getConfigsForTesting()
                        .get("default-category-server")
                        .category());
    }

    // ---- the STDIO handshake -------------------------------------------------

    @Test
    void connectStdioRegistersTheDiscoveredToolsThroughTheStdioListener() {
        stubProcessStarter();

        service.connectStdio(
                "stdio-server", "cmd", List.of("--flag"), Map.of("K", "V"), ToolCategory.NETWORK, "user-1");

        // Registration is scoped to the owning user, so read the capability back through that scope. Outside a
        // workspace the ambient scope resolves to "unknown", so the scope-less accessor would report a false miss.
        var capability = registry.find("user-1", "search")
                .orElseThrow(() -> new AssertionError("the handshake's tools must reach the dynamic registry"));
        assertEquals("stdio-server", capability.mcpServerName());
        assertEquals(McpTransportType.STDIO, capability.transport());
        assertEquals(ToolCategory.NETWORK, capability.category());
        assertTrue(service.hasStdioServer("stdio-server"));
    }

    // ---- managed executor selection -----------------------------------------

    @Test
    void healthCheckRunsTheStdioReconnectInlineWhenNoExecutorIsAvailable() {
        service.managedExecutors = null;
        AtomicInteger starts = registerDeadStdioServer("inline-server");
        stubProcessStarter(starts);

        service.healthCheck();

        assertEquals(1, starts.get(), "with no ManagedExecutor the reconnect must run on the calling thread");
        assertTrue(service.hasStdioServer("inline-server"));
    }

    @Test
    void healthCheckFallsBackToTheDefaultExecutorWhenNoNamedExecutorIsRegistered() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ManagedExecutor fallback = recordingExecutor(queued);
        service.managedExecutors = instanceWithNoNamedExecutor(fallback);
        AtomicInteger starts = registerDeadStdioServer("fallback-server");
        stubProcessStarter(starts);

        service.healthCheck();

        assertEquals(0, starts.get(), "the default executor must be handed the reconnect rather than running it");
        assertNotNull(queued.get(), "the reconnect must have been dispatched to the default executor");

        queued.get().run();
        assertEquals(1, starts.get());
        assertTrue(service.hasStdioServer("fallback-server"));
    }

    // ---- lifecycle -----------------------------------------------------------

    @Test
    void initBuildsTheHttpTransportThatTheConnectPathUses() {
        assertNotNull(service.httpTransport());
        assertFalse(
                service.httpTransport().serverUrl("never-connected").isPresent(),
                "the accessor must expose the live transport, not a detached copy");
    }

    @Test
    void cleanupToleratesAServiceThatNeverBuiltAStdioTransport() throws Exception {
        setField(service, "stdioTransport", null);

        assertDoesNotThrow(service::cleanup);
    }

    // ---- helpers -------------------------------------------------------------

    private void stubProcessStarter() {
        service.stdioTransport().setProcessStarterForTesting(pb -> respondingProcess(CONNECT_NDJSON));
    }

    private void stubProcessStarter(AtomicInteger starts) {
        service.stdioTransport().setProcessStarterForTesting(pb -> {
            starts.incrementAndGet();
            return respondingProcess(CONNECT_NDJSON);
        });
    }

    /** Registers a dead process plus its config so the health check has something to reconnect. */
    private AtomicInteger registerDeadStdioServer(String serverName) {
        var transport = service.stdioTransport();
        transport.registerProcessForTesting(
                serverName,
                deadProcess(),
                new ByteArrayOutputStream(),
                new BufferedReader(new StringReader("")),
                transport.createConfig("cmd", List.of(), Map.of(), ToolCategory.NETWORK, "user-1"));
        return new AtomicInteger();
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

    /** Records dispatched tasks instead of running them, so the caller can decide when the reconnect happens. */
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

    /**
     * An {@code Instance} whose named lookup finds nothing, forcing {@code getExecutor} down its {@code orElseGet}
     * branch onto the default-qualified executor.
     */
    @SuppressWarnings("unchecked")
    private static Instance<ManagedExecutor> instanceWithNoNamedExecutor(ManagedExecutor fallback) {
        Instance<ManagedExecutor> instance = mock(Instance.class);
        when(instance.select(any(AnnotationLiteral.class))).thenReturn(instance);
        when(instance.stream()).thenReturn(Stream.empty());
        when(instance.get()).thenReturn(fallback);
        return instance;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
