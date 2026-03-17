package com.arcclaw.mcp.transport;

import com.arcclaw.mcp.tool.DynamicToolCapability;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.tool.McpTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.context.ManagedExecutor;

@ApplicationScoped
public class McpClientService {

    private static final Logger log = Logger.getLogger(McpClientService.class);

    @Inject DynamicToolCapabilityRegistry registry;
    @Inject ObjectMapper objectMapper;

    @Inject ManagedExecutor executor;

    private final ConcurrentHashMap<String, String> serverUrls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdGen = new AtomicInteger(1);
    private HttpClient httpClient;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Timeout(10000)
    @Retry(maxRetries = 2, delay = 500)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 30000)
    public void connect(String serverName, String serverUrl) {
        serverUrls.put(serverName, serverUrl);
        try {
            ObjectNode initParams = objectMapper.createObjectNode();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.set("capabilities", objectMapper.createObjectNode());
            ObjectNode clientInfo = objectMapper.createObjectNode();
            clientInfo.put("name", "arcclaw");
            clientInfo.put("version", "1.0");
            initParams.set("clientInfo", clientInfo);

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
            registerTools(serverName, payload.path("result"));

            log.infof("Connected to HTTP/SSE MCP server '%s', registered tools: %d",
                serverName, registry.getAll().stream()
                    .filter(t -> t.mcpServerName().equals(serverName)).count());

        } catch (McpConnectionException e) {
            throw e;
        } catch (Exception e) {
            // IMPORTANT: restore interrupt flag if this was an InterruptedException
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException(
                "Failed to connect to HTTP MCP server: " + serverName, e);
        }
    }

    @Timeout(5000)
    @Retry(maxRetries = 2, delay = 200)
    public String invoke(String serverName, String toolName, String paramsJson) {
        String url = serverUrls.get(serverName);
        if (url == null) {
            throw new McpConnectionException(
                "No URL registered for HTTP MCP server: " + serverName);
        }
        try {
            ObjectNode callParams = objectMapper.createObjectNode();
            callParams.put("name", toolName);
            callParams.set("arguments",
                (paramsJson != null && !paramsJson.isBlank())
                    ? objectMapper.readTree(paramsJson)
                    : objectMapper.createObjectNode());

            HttpResponse<String> resp = postJsonRpc(
                url, "tools/call", callParams, sessionIds.get(serverName));
            if (resp.statusCode() != 200) {
                throw new RuntimeException(
                    "tools/call failed [" + resp.statusCode() + "] for: " + toolName);
            }
            JsonNode result = objectMapper.readTree(resp.body()).path("result");
            JsonNode content = result.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText();
            }
            return result.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // IMPORTANT: restore interrupt flag if this was an InterruptedException
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(
                "Failed to invoke tool '" + toolName + "' on server: " + serverName, e);
        }
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

    private void registerTools(String serverName, JsonNode result) {
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) return;
        for (JsonNode tool : tools) {
            String name   = tool.path("name").asText();
            String desc   = tool.path("description").asText("");
            String schema = tool.has("inputSchema")
                ? tool.path("inputSchema").toString() : "{}";
            registry.register(new DynamicToolCapability(
                name, desc, serverName, McpTransportType.HTTP_SSE, false, schema));
        }
    }
}
