package com.arcclaw.mcp.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.mcp.tool.DynamicToolCapability;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.tool.McpTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class McpClientService {

    private static final Logger log = Logger.getLogger(McpClientService.class);

    private final ConcurrentHashMap<String, ReentrantLock> stdioLocks = new ConcurrentHashMap<>();

    @Inject DynamicToolCapabilityRegistry registry;
    @Inject ObjectMapper objectMapper;

    @Inject Instance<ManagedExecutor> managedExecutors;

    private ManagedExecutor getExecutor() {
        return managedExecutors.select(Identifier.Literal.of("external-tool-executor"))
                .stream().findFirst()
                .orElseGet(() -> managedExecutors.select(Default.Literal.INSTANCE).get());
    }

    private final ConcurrentHashMap<String, StdioConfig> stdioConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> serverUrls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OutputStream> processWriters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedReader> processReaders = new ConcurrentHashMap<>();
    
    private final AtomicInteger requestIdGen = new AtomicInteger(1);
    private HttpClient httpClient;

    private record StdioConfig(String command, List<String> args, Map<String, String> env) {}

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
            .executor(getExecutor())
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Timeout(10000)
    @Retry(maxRetries = 2, delay = 500)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 30000)
    public void connect(String serverName, String serverUrl) {
        serverUrls.put(serverName, serverUrl);
        try {
            ObjectNode initParams = createInitParams();
            HttpResponse<String> initResp = postJsonRpc(serverUrl, "initialize", initParams, null);
            if (initResp.statusCode() != 200) {
                throw new McpConnectionException(
                    "MCP initialize failed [" + initResp.statusCode() + "] for: " + serverName);
            }
            initResp.headers().firstValue("Mcp-Session-Id")
                .ifPresent(sid -> sessionIds.put(serverName, sid));

            String sessionId = sessionIds.get(serverName);
            postNotification(serverUrl, "notifications/initialized", sessionId);

            HttpResponse<String> toolsResp = postJsonRpc(
                serverUrl, "tools/list", objectMapper.createObjectNode(), sessionId);
            if (toolsResp.statusCode() != 200) {
                throw new McpConnectionException(
                    "MCP tools/list failed [" + toolsResp.statusCode() + "] for: " + serverName);
            }
            JsonNode payload = objectMapper.readTree(toolsResp.body());
            registerTools(serverName, payload.path("result"), McpTransportType.HTTP_SSE);

            log.infof("Connected to HTTP/SSE MCP server '%s', registered tools: %d",
                serverName, registry.getAll().stream()
                    .filter(t -> t.mcpServerName().equals(serverName)).count());

        } catch (McpConnectionException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException(
                "Failed to connect to HTTP MCP server: " + serverName, e);
        }
    }

    /**
     * Connect to a local MCP server via STDIO.
     * Starts a sub-process and performs the MCP handshake over its stdin/stdout.
     */
    public void connectStdio(String serverName, String command, List<String> args, Map<String, String> env) {
        stdioConfigs.put(serverName, new StdioConfig(command, args, env));
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> fullCmd = new ArrayList<>();
            fullCmd.add(command);
            if (args != null) fullCmd.addAll(args);
            pb.command(fullCmd);
            if (env != null) pb.environment().putAll(env);
            
            log.infof("Starting local STDIO MCP server: %s (cmd: %s)", serverName, fullCmd);
            Process process = pb.start();
            processes.put(serverName, process);
            processWriters.put(serverName, process.getOutputStream());
            processReaders.put(serverName, new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));

            // 1. Initialize
            ObjectNode initParams = createInitParams();
            sendRequestStdio(serverName, "initialize", initParams); // wait for response
            
            // 2. Initialized
            sendNotificationStdio(serverName, "notifications/initialized", objectMapper.createObjectNode());

            // 3. List tools
            JsonNode toolsResult = sendRequestStdio(serverName, "tools/list", objectMapper.createObjectNode());
            registerTools(serverName, toolsResult, McpTransportType.STDIO);

            log.infof("Connected to STDIO MCP server '%s', registered tools: %d",
                serverName, registry.getAll().stream()
                    .filter(t -> t.mcpServerName().equals(serverName)).count());

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException("Failed to connect to local MCP server via STDIO: " + serverName, e);
        }
    }

    private ObjectNode createInitParams() {
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "arcclaw");
        clientInfo.put("version", "1.0");
        initParams.set("clientInfo", clientInfo);
        return initParams;
    }

    @Timeout(10000)
    @Retry(maxRetries = 2, delay = 1000, retryOn = {IOException.class})
    public String invoke(String serverName, String toolName, String paramsJson) {
        // N3: Guard reconnection logic with per-server lock to prevent race where 
        // parallel calls strike a dead server and all try to connectStdio simultaneously.
        ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, k -> new ReentrantLock());
        lock.lock();
        try {
            if (!processes.containsKey(serverName) && stdioConfigs.containsKey(serverName)) {
                log.infof("reconnecting dead MCP server: %s", serverName);
                StdioConfig cfg = stdioConfigs.get(serverName);
                connectStdio(serverName, cfg.command(), cfg.args(), cfg.env());
            }
        } finally {
            lock.unlock();
        }

        if (processes.containsKey(serverName)) {
            return invokeStdio(serverName, toolName, paramsJson);
        }
        String url = serverUrls.get(serverName);
        if (url == null) {
            throw new McpConnectionException(
                "No URL registered for HTTP MCP server: " + serverName);
        }
        try {
            ObjectNode callParams = createCallParams(toolName, paramsJson);

            HttpResponse<String> resp = postJsonRpc(
                url, "tools/call", callParams, sessionIds.get(serverName));
            if (resp.statusCode() != 200) {
                throw new RuntimeException(
                    "tools/call failed [" + resp.statusCode() + "] for: " + toolName);
            }
            return extractTextResult(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(
                "Failed to invoke tool '" + toolName + "' on server: " + serverName, e);
        }
    }

    private String invokeStdio(String serverName, String toolName, String paramsJson) {
        try {
            ObjectNode callParams = createCallParams(toolName, paramsJson);
            JsonNode result = sendRequestStdio(serverName, "tools/call", callParams);
            return extractTextResult(objectMapper.writeValueAsString(objectMapper.createObjectNode().set("result", result)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke local tool via STDIO: " + toolName, e);
        }
    }

    private ObjectNode createCallParams(String toolName, String paramsJson) throws Exception {
        ObjectNode callParams = objectMapper.createObjectNode();
        callParams.put("name", toolName);
        callParams.set("arguments",
            (paramsJson != null && !paramsJson.isBlank())
                ? objectMapper.readTree(paramsJson)
                : objectMapper.createObjectNode());
        return callParams;
    }

    private String extractTextResult(String body) throws Exception {
        JsonNode result = objectMapper.readTree(body).path("result");
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText();
        }
        return result.toString();
    }

    private HttpResponse<String> postJsonRpc(String baseUrl, String method,
            ObjectNode params, String sessionId) throws Exception {
        ObjectNode rpc = objectMapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", requestIdGen.getAndIncrement());
        rpc.put("method", method);
        rpc.set("params", params);

        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(rpc)));
        if (sessionId != null) req.header("Mcp-Session-Id", sessionId);
        return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void postNotification(String baseUrl, String method,
            String sessionId) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", objectMapper.createObjectNode());

        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(notification)));
        if (sessionId != null) req.header("Mcp-Session-Id", sessionId);
        httpClient.send(req.build(), HttpResponse.BodyHandlers.discarding());
    }

    private void registerTools(String serverName, JsonNode result, McpTransportType transport) {
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) return;
        for (JsonNode tool : tools) {
            String name   = tool.path("name").asText();
            String desc   = tool.path("description").asText("");
            String schema = tool.has("inputSchema")
                ? tool.path("inputSchema").toString() : "{}";
            registry.register(new DynamicToolCapability(
                name, desc, serverName, transport, false, schema));
        }
    }

    private JsonNode sendRequestStdio(String serverName, String method, ObjectNode params) throws Exception {
        ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, k -> new ReentrantLock());
        lock.lock();
        try{
            int id = requestIdGen.getAndIncrement();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.set("params", params);

            OutputStream out = processWriters.get(serverName);
            if (out == null) throw new IOException("No writer for MCP server: " + serverName);
            
            try {
                NdjsonWriter.writeLine(out, objectMapper.writeValueAsString(request));

                BufferedReader reader = processReaders.get(serverName);
                if (reader == null) throw new IOException("No reader for MCP server: " + serverName);
                String line = reader.readLine();
                if (line == null) throw new IOException("MCP server closed connection unexpectedly");
                
                JsonNode response = objectMapper.readTree(line);
                if (response.has("error")) {
                    throw new RuntimeException("MCP server error: " + response.get("error").toString());
                }
                return response.get("result");
            } catch (IOException e) {
                // F19 Dead Process Guard: cleanup on any transport failure
                // next retry loop will call connectStdio via invoke() fallback
                log.warnf("MCP STDIO transport error for %s: %s - tearing down process state", serverName, e.getMessage());
                cleanupStdio(serverName);
                throw e;
            }
        } finally {
           lock.unlock();
        }
    }

    private void cleanupStdio(String serverName) {
        try {
            OutputStream out = processWriters.remove(serverName);
            if (out != null) out.close();
        } catch (IOException ignored) {}
        
        try {
            BufferedReader reader = processReaders.remove(serverName);
            if (reader != null) reader.close();
        } catch (IOException ignored) {}
        
        Process p = processes.remove(serverName);
        if (p != null) {
            p.destroyForcibly();
            try { p.waitFor(1, TimeUnit.SECONDS); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
    }

    private void sendNotificationStdio(String serverName, String method, ObjectNode params) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        
        OutputStream out = processWriters.get(serverName);
        if (out == null) return;
        NdjsonWriter.writeLine(out, objectMapper.writeValueAsString(notification));
    }

    @PreDestroy
    void cleanup() {
        log.info("McpClientService shutting down - terminating local MCP processes");
        processes.forEach((name, process) -> {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        });
        processes.clear();
    }
}
