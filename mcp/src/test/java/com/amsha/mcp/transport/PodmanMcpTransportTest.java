package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.sandbox.SandboxCircuitBreaker;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodmanMcpTransportTest {

    private PodmanMcpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        transport = new PodmanMcpTransport();
        setField("sandboxCircuitBreaker", mock(SandboxCircuitBreaker.class));
        setField("registry", new DynamicToolCapabilityRegistry());
        setField("objectMapper", new ObjectMapper());
        transport.healthCheckIntervalMs = 0; // Disable automatic background health check by default
        transport.init();
    }

    @AfterEach
    void tearDown() {
        transport.cleanup();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Process> getActiveProcesses() throws Exception {
        Field field = PodmanMcpTransport.class.getDeclaredField("activeProcesses");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Process>) field.get(transport);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, OutputStream> getWriters() throws Exception {
        Field field = PodmanMcpTransport.class.getDeclaredField("writers");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, OutputStream>) field.get(transport);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, BufferedReader> getReaders() throws Exception {
        Field field = PodmanMcpTransport.class.getDeclaredField("readers");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, BufferedReader>) field.get(transport);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = PodmanMcpTransport.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(transport, value);
    }

    // ==================== Server Existence (hasServer) Tests ====================

    @Test
    void testHasServerReturnsTrueWhenPresentAndFalseWhenAbsent() throws Exception {
        assertFalse(transport.hasServer("server-1"));

        Process mockProcess = mock(Process.class);
        getActiveProcesses().put("server-1", mockProcess);
        assertTrue(transport.hasServer("server-1"));

        getActiveProcesses().remove("server-1");
        assertFalse(transport.hasServer("server-1"));
    }

    // ==================== Health Check Tests ====================

    @Test
    void testInitWithZeroOrNegativeIntervalDoesNotStartExecutor() throws Exception {
        PodmanMcpTransport customTransport = new PodmanMcpTransport();
        customTransport.healthCheckIntervalMs = 0;
        customTransport.init();

        Field executorField = PodmanMcpTransport.class.getDeclaredField("healthCheckExecutor");
        executorField.setAccessible(true);
        assertNull(executorField.get(customTransport));
    }

    @Test
    void testInitWithPositiveIntervalStartsExecutor() throws Exception {
        PodmanMcpTransport customTransport = new PodmanMcpTransport();
        customTransport.healthCheckIntervalMs = 15000;
        customTransport.init();

        Field executorField = PodmanMcpTransport.class.getDeclaredField("healthCheckExecutor");
        executorField.setAccessible(true);
        ScheduledExecutorService executor = (ScheduledExecutorService) executorField.get(customTransport);
        assertNotNull(executor);
        assertFalse(executor.isShutdown());

        customTransport.cleanup();
        assertTrue(executor.isShutdown());
    }

    @Test
    void testHealthCheckRetainsAliveProcess() throws Exception {
        Process aliveProcess = mock(Process.class);
        when(aliveProcess.isAlive()).thenReturn(true);
        when(aliveProcess.waitFor(anyLong(), any())).thenReturn(true);

        getActiveProcesses().put("alive-server", aliveProcess);
        getWriters().put("alive-server", new ByteArrayOutputStream());
        getReaders().put("alive-server", new BufferedReader(new StringReader("")));

        Method healthCheck = PodmanMcpTransport.class.getDeclaredMethod("healthCheck");
        healthCheck.setAccessible(true);
        healthCheck.invoke(transport);

        assertTrue(getActiveProcesses().containsKey("alive-server"));
        assertTrue(getWriters().containsKey("alive-server"));
        assertTrue(getReaders().containsKey("alive-server"));
    }

    @Test
    void testHealthCheckCleansUpDeadProcess() throws Exception {
        Process deadProcess = mock(Process.class);
        when(deadProcess.isAlive()).thenReturn(false);

        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader(""));

        getActiveProcesses().put("dead-server", deadProcess);
        getWriters().put("dead-server", writer);
        getReaders().put("dead-server", reader);

        Method healthCheck = PodmanMcpTransport.class.getDeclaredMethod("healthCheck");
        healthCheck.setAccessible(true);
        healthCheck.invoke(transport);

        assertFalse(getActiveProcesses().containsKey("dead-server"));
        assertFalse(getWriters().containsKey("dead-server"));
        assertFalse(getReaders().containsKey("dead-server"));
    }

    @Test
    void testHealthCheckHandlesStreamCloseExceptionsGracefully() throws Exception {
        Process deadProcess = mock(Process.class);
        when(deadProcess.isAlive()).thenReturn(false);

        OutputStream failingWriter = mock(OutputStream.class);
        doThrow(new IOException("close writer failed")).when(failingWriter).close();

        BufferedReader failingReader = mock(BufferedReader.class);
        doThrow(new IOException("close reader failed")).when(failingReader).close();

        getActiveProcesses().put("err-server", deadProcess);
        getWriters().put("err-server", failingWriter);
        getReaders().put("err-server", failingReader);

        Method healthCheck = PodmanMcpTransport.class.getDeclaredMethod("healthCheck");
        healthCheck.setAccessible(true);
        assertDoesNotThrow(() -> healthCheck.invoke(transport));

        assertFalse(getActiveProcesses().containsKey("err-server"));
        assertFalse(getWriters().containsKey("err-server"));
        assertFalse(getReaders().containsKey("err-server"));
    }

    // ==================== Connect Validation & Circuit Breaker Tests ====================

    @Test
    void testConnectOwnerValidation() {
        McpServerConfig nullOwner = new McpServerConfig("srv", "img", List.of(), Map.of(), ToolCategory.COMPUTE, null);
        IllegalArgumentException ex1 = assertThrows(
                IllegalArgumentException.class,
                () -> transport.connect(nullOwner).await().indefinitely());
        assertTrue(ex1.getMessage().contains("Podman MCP registration requires an explicit user owner"));

        McpServerConfig blankOwner =
                new McpServerConfig("srv", "img", List.of(), Map.of(), ToolCategory.COMPUTE, "   ");
        IllegalArgumentException ex2 = assertThrows(
                IllegalArgumentException.class,
                () -> transport.connect(blankOwner).await().indefinitely());
        assertTrue(ex2.getMessage().contains("Podman MCP registration requires an explicit user owner"));

        McpServerConfig unknownOwner =
                new McpServerConfig("srv", "img", List.of(), Map.of(), ToolCategory.COMPUTE, "unknown");
        IllegalArgumentException ex3 = assertThrows(
                IllegalArgumentException.class,
                () -> transport.connect(unknownOwner).await().indefinitely());
        assertTrue(ex3.getMessage().contains("Podman MCP registration requires an explicit user owner"));
    }

    @Test
    void testConnectCircuitBreakerOpenThrows() {
        when(transport.sandboxCircuitBreaker.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.OPEN);
        when(transport.sandboxCircuitBreaker.shouldAttemptPodman()).thenReturn(false);

        McpServerConfig config =
                new McpServerConfig("test-server", "img:latest", List.of(), Map.of(), ToolCategory.COMPUTE, "user-1");
        RuntimeException ex = assertThrows(
                RuntimeException.class, () -> transport.connect(config).await().indefinitely());
        assertTrue(ex.getMessage().contains("MCP Podman circuit breaker is OPEN for server: test-server"));
    }

    @Test
    void testConnectProcessStartFailureRecordsFailureAndThrows() {
        when(transport.sandboxCircuitBreaker.getState()).thenReturn(SandboxCircuitBreaker.CircuitBreakerState.CLOSED);

        McpServerConfig config = new McpServerConfig(
                "fail-server",
                "nonexistent-podman-image:tag",
                List.of("ENV1=VAL1"),
                Map.of("label1", "val1"),
                ToolCategory.COMPUTE,
                "user-1");

        RuntimeException ex = assertThrows(
                RuntimeException.class, () -> transport.connect(config).await().indefinitely());
        assertTrue(ex.getMessage().contains("Failed to connect to MCP server: fail-server"));
        verify(transport.sandboxCircuitBreaker).recordFailure(SandboxCircuitBreaker.CircuitBreakerState.CLOSED);
    }

    // ==================== Tool Registration Tests ====================

    @Test
    void testRegisterToolsExtractsMetadataAndRegistersInRegistry() throws Exception {
        Method registerToolsMethod = PodmanMcpTransport.class.getDeclaredMethod(
                "registerTools", McpServerConfig.class, JsonNode.class, String.class);
        registerToolsMethod.setAccessible(true);

        McpServerConfig config =
                new McpServerConfig("reg-server", "image:latest", List.of(), Map.of(), ToolCategory.COMPUTE, "user-1");

        String json = """
        {
            "tools": [
                {
                    "name": "run_analysis",
                    "description": "Performs system analysis",
                    "inputSchema": {"type": "object", "properties": {"target": {"type": "string"}}}
                },
                {
                    "name": "minimal_tool"
                }
            ]
        }
        """;
        JsonNode toolsResponse = transport.objectMapper.readTree(json);
        registerToolsMethod.invoke(transport, config, toolsResponse, "user-1");

        var analysisTool = transport.registry.find("user-1", "run_analysis");
        assertTrue(analysisTool.isPresent());
        assertEquals("run_analysis", analysisTool.get().toolName());
        assertEquals("Performs system analysis", analysisTool.get().description());
        assertEquals("reg-server", analysisTool.get().mcpServerName());
        assertEquals(McpTransportType.STDIO, analysisTool.get().transport());
        assertEquals(ToolCategory.COMPUTE, analysisTool.get().category());
        assertTrue(analysisTool.get().inputSchema().contains("target"));

        var minimalTool = transport.registry.find("user-1", "minimal_tool");
        assertTrue(minimalTool.isPresent());
        assertEquals("minimal_tool", minimalTool.get().toolName());
        assertEquals("", minimalTool.get().description());
        assertEquals("{}", minimalTool.get().inputSchema());
    }

    @Test
    void testRegisterToolsHandlesEmptyOrNonArrayPayloads() throws Exception {
        Method registerToolsMethod = PodmanMcpTransport.class.getDeclaredMethod(
                "registerTools", McpServerConfig.class, JsonNode.class, String.class);
        registerToolsMethod.setAccessible(true);

        McpServerConfig config = new McpServerConfig(
                "empty-server", "image:latest", List.of(), Map.of(), ToolCategory.COMPUTE, "user-1");

        // Missing tools node
        JsonNode missingTools = transport.objectMapper.createObjectNode();
        assertDoesNotThrow(() -> registerToolsMethod.invoke(transport, config, missingTools, "user-1"));

        // Non-array tools node
        ObjectNode nonArray = transport.objectMapper.createObjectNode();
        nonArray.put("tools", "not-an-array");
        assertDoesNotThrow(() -> registerToolsMethod.invoke(transport, config, nonArray, "user-1"));

        assertTrue(transport.registry.getAll("user-1").isEmpty());
    }

    // ==================== JSON-RPC Request, Response & Invocation Tests ====================

    @Test
    void testInvokeSuccessWithParamsJson() throws Exception {
        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        String serverResponse =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"computed-42\"}]}}\n";
        BufferedReader reader = new BufferedReader(new StringReader(serverResponse));

        getWriters().put("srv-1", writer);
        getReaders().put("srv-1", reader);

        String resultJson = transport
                .invoke("srv-1", "calculate", "{\"expr\":\"40+2\"}")
                .await()
                .indefinitely();
        assertNotNull(resultJson);
        assertTrue(resultJson.contains("computed-42"));

        String writtenRpc = writer.toString(StandardCharsets.UTF_8);
        JsonNode rpcNode = transport.objectMapper.readTree(writtenRpc.trim());
        assertEquals("2.0", rpcNode.get("jsonrpc").asText());
        assertEquals(1, rpcNode.get("id").asInt());
        assertEquals("tools/call", rpcNode.get("method").asText());
        assertEquals("calculate", rpcNode.get("params").get("name").asText());
        assertEquals("40+2", rpcNode.get("params").get("arguments").get("expr").asText());
    }

    @Test
    void testInvokeSuccessWithNullOrBlankParamsJson() throws Exception {
        ByteArrayOutputStream writer1 = new ByteArrayOutputStream();
        String response1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}\n";
        BufferedReader reader1 = new BufferedReader(new StringReader(response1));

        getWriters().put("srv-null", writer1);
        getReaders().put("srv-null", reader1);

        String res1 = transport.invoke("srv-null", "ping", null).await().indefinitely();
        assertTrue(res1.contains("ok"));

        JsonNode node1 = transport.objectMapper.readTree(
                writer1.toString(StandardCharsets.UTF_8).trim());
        assertTrue(node1.get("params").get("arguments").isObject());
        assertTrue(node1.get("params").get("arguments").isEmpty());

        ByteArrayOutputStream writer2 = new ByteArrayOutputStream();
        String response2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"status\":\"pong\"}}\n";
        BufferedReader reader2 = new BufferedReader(new StringReader(response2));

        getWriters().put("srv-blank", writer2);
        getReaders().put("srv-blank", reader2);

        String res2 = transport.invoke("srv-blank", "ping", "   ").await().indefinitely();
        assertTrue(res2.contains("pong"));

        JsonNode node2 = transport.objectMapper.readTree(
                writer2.toString(StandardCharsets.UTF_8).trim());
        assertTrue(node2.get("params").get("arguments").isObject());
        assertTrue(node2.get("params").get("arguments").isEmpty());
    }

    @Test
    void testInvokeServerErrorPayloadThrowsException() throws Exception {
        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        String errorResponse =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid tool parameters\"}}\n";
        BufferedReader reader = new BufferedReader(new StringReader(errorResponse));

        getWriters().put("srv-err", writer);
        getReaders().put("srv-err", reader);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> transport.invoke("srv-err", "broken_tool", "{}").await().indefinitely());
        assertTrue(ex.getMessage().contains("MCP server error:"));
        assertTrue(ex.getMessage().contains("Invalid tool parameters"));
    }

    @Test
    void testInvokeEofFromReaderThrowsException() throws Exception {
        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        BufferedReader emptyReader = new BufferedReader(new StringReader(""));

        getWriters().put("srv-eof", writer);
        getReaders().put("srv-eof", emptyReader);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> transport.invoke("srv-eof", "tool", "{}").await().indefinitely());
        assertTrue(ex.getMessage().contains("Failed to invoke dynamic tool: tool on server: srv-eof"));
        assertTrue(ex.getCause().getMessage().contains("MCP server closed connection unexpectedly"));
    }

    @Test
    void testInvokeMissingWriterThrowsException() throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader(""));
        getReaders().put("srv-no-writer", reader);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> transport.invoke("srv-no-writer", "tool", "{}").await().indefinitely());
        assertTrue(ex.getCause().getMessage().contains("No writer for server: srv-no-writer"));
    }

    @Test
    void testInvokeMissingReaderThrowsException() throws Exception {
        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        getWriters().put("srv-no-reader", writer);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> transport.invoke("srv-no-reader", "tool", "{}").await().indefinitely());
        assertTrue(ex.getCause().getMessage().contains("No reader for server: srv-no-reader"));
    }

    @Test
    void testSendNotificationConstructsValidJsonRpcNotification() throws Exception {
        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        getWriters().put("notif-server", writer);

        Method sendNotificationMethod = PodmanMcpTransport.class.getDeclaredMethod(
                "sendNotification", String.class, String.class, ObjectNode.class);
        sendNotificationMethod.setAccessible(true);

        ObjectNode params = transport.objectMapper.createObjectNode();
        params.put("eventType", "ready");
        sendNotificationMethod.invoke(transport, "notif-server", "notifications/custom", params);

        String written = writer.toString(StandardCharsets.UTF_8).trim();
        JsonNode node = transport.objectMapper.readTree(written);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals("notifications/custom", node.get("method").asText());
        assertEquals("ready", node.get("params").get("eventType").asText());
        assertFalse(node.has("id"));
    }

    // ==================== Disconnect Tests ====================

    @Test
    void testDisconnectWithActiveProcessAndStreams() throws Exception {
        Process mockProcess = mock(Process.class);
        ByteArrayOutputStream writer = spy(new ByteArrayOutputStream());
        BufferedReader reader = spy(new BufferedReader(new StringReader("")));

        getActiveProcesses().put("srv-dc", mockProcess);
        getWriters().put("srv-dc", writer);
        getReaders().put("srv-dc", reader);

        transport.disconnect("srv-dc").await().indefinitely();

        verify(mockProcess).destroy();
        verify(writer).close();
        verify(reader).close();

        assertFalse(getActiveProcesses().containsKey("srv-dc"));
        assertFalse(getWriters().containsKey("srv-dc"));
        assertFalse(getReaders().containsKey("srv-dc"));
        assertFalse(transport.hasServer("srv-dc"));
    }

    @Test
    void testDisconnectHandlesStreamCloseExceptionsGracefully() throws Exception {
        Process mockProcess = mock(Process.class);
        OutputStream failingWriter = mock(OutputStream.class);
        doThrow(new IOException("close failed")).when(failingWriter).close();

        BufferedReader failingReader = mock(BufferedReader.class);
        doThrow(new IOException("close failed")).when(failingReader).close();

        getActiveProcesses().put("srv-dc-err", mockProcess);
        getWriters().put("srv-dc-err", failingWriter);
        getReaders().put("srv-dc-err", failingReader);

        assertDoesNotThrow(() -> transport.disconnect("srv-dc-err").await().indefinitely());
        verify(mockProcess).destroy();
        assertFalse(getActiveProcesses().containsKey("srv-dc-err"));
    }

    @Test
    void testDisconnectNonExistentServerDoesNotThrow() {
        assertDoesNotThrow(() -> transport.disconnect("ghost-server").await().indefinitely());
    }

    // ==================== Cleanup Handlers Tests ====================

    @Test
    void testCleanupWithProcessExitingNormally() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(3, TimeUnit.SECONDS)).thenReturn(true);

        getActiveProcesses().put("proc-1", process);
        transport.cleanup();

        verify(process).destroy();
        verify(process, never()).destroyForcibly();
        assertTrue(getActiveProcesses().isEmpty());
    }

    @Test
    void testCleanupWithProcessNotExitingForciblyKills() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(3, TimeUnit.SECONDS)).thenReturn(false);

        getActiveProcesses().put("proc-2", process);
        transport.cleanup();

        verify(process).destroy();
        verify(process).destroyForcibly();
        assertTrue(getActiveProcesses().isEmpty());
    }

    @Test
    void testCleanupWithProcessWaitForInterrupted() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(3, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        getActiveProcesses().put("proc-3", process);
        transport.cleanup();

        verify(process).destroyForcibly();
        assertTrue(Thread.interrupted()); // Verify interrupted flag was set and clear it
        assertTrue(getActiveProcesses().isEmpty());
    }

    @Test
    void testCleanupHealthCheckExecutorTerminationTimeout() throws Exception {
        ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
        when(mockExecutor.awaitTermination(2, TimeUnit.SECONDS)).thenReturn(false);

        setField("healthCheckExecutor", mockExecutor);
        transport.cleanup();

        verify(mockExecutor).shutdown();
        verify(mockExecutor).shutdownNow();
    }

    @Test
    void testCleanupHealthCheckExecutorInterrupted() throws Exception {
        ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
        when(mockExecutor.awaitTermination(2, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        setField("healthCheckExecutor", mockExecutor);
        transport.cleanup();

        verify(mockExecutor).shutdown();
        verify(mockExecutor).shutdownNow();
        assertTrue(Thread.interrupted()); // Verify interrupted flag was set and clear it
    }
}
