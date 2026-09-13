package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class McpStdioReconnectTest {

    private McpClientService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new McpClientService();
        service.registry = new DynamicToolCapabilityRegistry();
        service.objectMapper = mapper;
        service.meterRegistry = new SimpleMeterRegistry();

        service.managedExecutors = mock(Instance.class);
        ManagedExecutor mockExec = mock(ManagedExecutor.class);
        when(service.managedExecutors.select(any(Class.class))).thenReturn(service.managedExecutors);
        when(service.managedExecutors.select(any(AnnotationLiteral.class))).thenReturn(service.managedExecutors);
        when(service.managedExecutors.get()).thenReturn(mockExec);
        when(service.managedExecutors.stream()).thenReturn(Stream.of(mockExec));

        service.init();
    }

    @Test
    void testF19_CleanupAndReconnectOnDeadProcess() throws Exception {
        // 1. Setup mock dead state
        String serverName = "dead-server";

        Process mockDeadProcess = mock(Process.class);
        OutputStream mockDeadOut = mock(OutputStream.class);
        // This reader will return null (EOF) immediately
        BufferedReader mockDeadReader = new BufferedReader(new StringReader(""));

        // Register into stdio transport via package-private test seam
        var config = service.stdioTransport()
                .createConfig("cmd", Collections.emptyList(), Collections.emptyMap(), ToolCategory.NETWORK, "owner-1");
        service.stdioTransport()
                .registerProcessForTesting(serverName, mockDeadProcess, mockDeadOut, mockDeadReader, config);

        // Pre-register old tool in registry to verify metadata refresh upon reconnect
        service.registry.registerForUser(
                "owner-1",
                new DynamicToolCapability(
                        "tool", "old desc", serverName, McpTransportType.STDIO, false, "{}", ToolCategory.NETWORK));

        // Setup replacement process for reconnection via transport process starter seam
        Process mockReplacementProcess = mock(Process.class);
        when(mockReplacementProcess.isAlive()).thenReturn(true);
        when(mockReplacementProcess.destroyForcibly()).thenReturn(mockReplacementProcess);
        when(mockReplacementProcess.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        String ndjsonResponses = """
                {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"replacement-server","version":"1.0"}}}
                {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"tool","description":"reconnected desc","inputSchema":{"type":"object"}}]}}
                {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"invoked reconnected tool"}]}}
                """;
        when(mockReplacementProcess.getInputStream())
                .thenReturn(new ByteArrayInputStream(ndjsonResponses.getBytes(StandardCharsets.UTF_8)));

        service.stdioTransport().setProcessStarterForTesting(pb -> mockReplacementProcess);

        // 2. First invoke: fails due to EOF on the dead process
        try {
            service.invoke(serverName, "tool", "{}");
        } catch (Exception _) {
            // expected on first try because dead process closed connection
        }

        // 3. Verify dead process was cleaned up and destroyed
        Map<String, Process> processes = service.stdioTransport().getProcessesForTesting();
        assertEquals(0, processes.size(), "Dead process should have been removed from map after EOF");
        verify(mockDeadProcess).destroyForcibly();

        // 4. Second invoke: triggers reconnection directly through transport's reconnect seam
        String result = service.invoke(serverName, "tool", "{}");

        // 5. Assert replacement process is started and active
        assertEquals(1, processes.size(), "Replacement process should be registered in active processes");
        assertEquals(mockReplacementProcess, processes.get(serverName), "Active process should be replacement process");

        // 6. Assert tool metadata is re-registered with updated description
        var toolCap = service.registry.find("owner-1", "tool").orElseThrow();
        assertEquals("reconnected desc", toolCap.description(), "Tool metadata should be re-registered on reconnect");

        // 7. Assert tool invocation succeeded on the reconnected process
        assertEquals("invoked reconnected tool", result);
    }

    @Test
    void reconnectCanRefreshChangedToolMetadata() throws Exception {
        service.registry = new DynamicToolCapabilityRegistry();

        JsonNode initial = mapper.readTree("{\"tools\":[{\"name\":\"search\",\"description\":\"old\","
                + "\"inputSchema\":{\"type\":\"object\"}}]}");
        JsonNode refreshed = mapper.readTree("{\"tools\":[{\"name\":\"search\",\"description\":\"new\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\"}}}}]}");

        service.registerTools(
                "stdio-server",
                initial,
                McpTransportType.STDIO,
                ToolCategory.NETWORK,
                "owner",
                false,
                Set.of("search"));
        service.registerTools(
                "stdio-server",
                refreshed,
                McpTransportType.STDIO,
                ToolCategory.NETWORK,
                "owner",
                false,
                Set.of("search"));

        assertEquals(
                "new", service.registry.find("owner", "search").orElseThrow().description());
    }
}
