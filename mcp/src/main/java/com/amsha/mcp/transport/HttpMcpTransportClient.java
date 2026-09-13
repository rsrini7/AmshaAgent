package com.amsha.mcp.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.http.GovernedHttpClient;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Transport client for HTTP/SSE-based Model Context Protocol (MCP) servers.
 * Encapsulates URL mappings, session state, credential resolution, and governed HTTP dispatch.
 */
class HttpMcpTransportClient {

    private static final Logger log = Logger.getLogger(HttpMcpTransportClient.class);
    private static final String METRIC_MCP_LOAD_FAILURE = "amsha.mcp.tool.load.failure";
    private static final String TAG_SERVER = "server";
    private static final String TAG_PHASE = "phase";

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final McpConnectionCredentialPort credentialPort;
    private final OutboundHttpPolicyService outboundPolicyService;
    private final SecretVault secretVault;
    private final McpJsonRpcCodec jsonRpcCodec;

    private final ConcurrentHashMap<String, String> serverUrls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> serverCredentialIds = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdGen = new AtomicInteger(1);

    HttpMcpTransportClient(
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            McpConnectionCredentialPort credentialPort,
            OutboundHttpPolicyService outboundPolicyService,
            SecretVault secretVault,
            McpJsonRpcCodec jsonRpcCodec) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.credentialPort = credentialPort;
        this.outboundPolicyService = outboundPolicyService;
        this.secretVault = secretVault;
        this.jsonRpcCodec = jsonRpcCodec;
    }

    JsonNode connect(
            String serverName,
            String runtimeKey,
            String serverUrl,
            Map<String, String> handshakeHeaders,
            boolean preApproved)
            throws Exception {
        serverUrls.put(runtimeKey, serverUrl);
        try {
            ObjectNode initParams = jsonRpcCodec.createInitParams();
            HttpResponse<String> initResp = postJsonRpc(
                    runtimeKey,
                    serverUrl,
                    "initialize",
                    initParams,
                    null,
                    handshakeHeaders,
                    OutboundPurpose.MCP_CONNECT,
                    preApproved);
            if (initResp.statusCode() != 200) {
                recordFailure(serverName, "initialize");
                throw new McpConnectionException(
                        "MCP initialize failed [" + initResp.statusCode() + "] for: " + serverName);
            }
            initResp.headers().firstValue("Mcp-Session-Id").ifPresent(sid -> sessionIds.put(runtimeKey, sid));

            String sessionId = sessionIds.get(runtimeKey);
            postNotification(
                    runtimeKey,
                    serverUrl,
                    "notifications/initialized",
                    sessionId,
                    handshakeHeaders,
                    OutboundPurpose.MCP_CONNECT,
                    preApproved);

            HttpResponse<String> toolsResp = postJsonRpc(
                    runtimeKey,
                    serverUrl,
                    "tools/list",
                    objectMapper.createObjectNode(),
                    sessionId,
                    handshakeHeaders,
                    OutboundPurpose.MCP_CONNECT,
                    preApproved);
            if (toolsResp.statusCode() != 200) {
                recordFailure(serverName, "list");
                throw new McpConnectionException(
                        "MCP tools/list failed [" + toolsResp.statusCode() + "] for: " + serverName);
            }
            JsonNode payload = objectMapper.readTree(toolsResp.body());
            return payload.path("result");

        } catch (McpConnectionException e) {
            throw e;
        } catch (Exception e) {
            recordFailure(serverName, "connect_exception");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException("Failed to connect to HTTP MCP server: " + serverName, e);
        }
    }

    String invoke(String serverName, String toolName, String paramsJson, String ownerUserId) throws Exception {
        String runtimeKey = resolveHttpRuntimeKey(serverName, ownerUserId);
        String url = serverUrls.get(runtimeKey);
        if (url == null) {
            throw new McpConnectionException("No URL registered for HTTP MCP server: " + serverName);
        }

        ObjectNode callParams = jsonRpcCodec.createCallParams(toolName, paramsJson);

        HttpResponse<String> resp = postJsonRpc(
                runtimeKey,
                url,
                "tools/call",
                callParams,
                sessionIds.get(runtimeKey),
                Map.of(),
                OutboundPurpose.MCP_TOOL_CALL,
                false);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("tools/call failed [" + resp.statusCode() + "] for: " + toolName);
        }
        return jsonRpcCodec.extractTextResult(resp.body());
    }

    void configureHeaderSecretRefs(String serverName, String userId, List<McpConnectionHeaderSecretRefDto> refs) {
        if (credentialPort == null) return;
        String runtimeKey = runtimeKey(serverName, userId);
        if (refs == null || refs.isEmpty()) {
            String previousConnectionId = serverCredentialIds.remove(runtimeKey);
            if (previousConnectionId != null) credentialPort.deleteHeaders(previousConnectionId);
            return;
        }
        if (refs.stream().anyMatch(ref -> !userId.equals(ref.userId()))) {
            throw new McpConnectionException("MCP credential owner mismatch for server: " + serverName);
        }
        String connectionId = refs.getFirst().connectionId();
        migratePendingHttpState(serverName, runtimeKey);
        serverCredentialIds.put(runtimeKey, connectionId);
        credentialPort.replaceHeaders(
                connectionId,
                userId,
                refs.stream().map(HeaderSecretReference::from).toList());
    }

    void clearConnectionState(String serverName) {
        clearConnectionStateForKey(serverName);
    }

    void clearConnectionState(String serverName, String userId) {
        clearConnectionStateForKey(runtimeKey(serverName, userId));
    }

    private void clearConnectionStateForKey(String runtimeKey) {
        serverUrls.remove(runtimeKey);
        sessionIds.remove(runtimeKey);
        String connectionId = serverCredentialIds.remove(runtimeKey);
        if (credentialPort != null && connectionId != null) credentialPort.deleteHeaders(connectionId);
    }

    Optional<String> serverUrl(String serverName) {
        return Optional.ofNullable(serverUrls.get(resolveHttpRuntimeKey(serverName)));
    }

    Optional<String> serverUrl(String serverName, String userId) {
        return Optional.ofNullable(serverUrls.get(resolveHttpRuntimeKey(serverName, userId)));
    }

    boolean isSelfConnectUrl(String serverUrl) {
        try {
            URI uri = URI.create(serverUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null
                    && ("localhost".equalsIgnoreCase(host)
                            || "127.0.0.1".equals(host)
                            || "[::1]".equals(host)
                            || "::1".equals(host))
                    && ("/mcp".equals(path) || "/mcp/".equals(path));
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    String extractTextResult(String body) throws Exception {
        return jsonRpcCodec.extractTextResult(body);
    }

    private HttpResponse<String> postJsonRpc(
            String runtimeKey,
            String baseUrl,
            String method,
            ObjectNode params,
            String sessionId,
            Map<String, String> directHeaders,
            OutboundPurpose purpose,
            boolean preApproved)
            throws Exception {
        ObjectNode rpc = objectMapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", requestIdGen.getAndIncrement());
        rpc.put("method", method);
        rpc.set("params", params);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(rpc)));
        if (sessionId != null) req.header("Mcp-Session-Id", sessionId);
        applyDirectHeaders(req, directHeaders);
        try (ResolvedMcpHeaders resolvedHeaders = resolveConfiguredHeaders(runtimeKey, directHeaders)) {
            resolvedHeaders.applyTo(req);
            return governedSend(req.build(), purpose, preApproved);
        }
    }

    private void postNotification(
            String runtimeKey,
            String baseUrl,
            String method,
            String sessionId,
            Map<String, String> directHeaders,
            OutboundPurpose purpose,
            boolean preApproved)
            throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", objectMapper.createObjectNode());

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(notification)));
        if (sessionId != null) req.header("Mcp-Session-Id", sessionId);
        applyDirectHeaders(req, directHeaders);
        try (ResolvedMcpHeaders resolvedHeaders = resolveConfiguredHeaders(runtimeKey, directHeaders)) {
            resolvedHeaders.applyTo(req);
            governedSend(req.build(), purpose, preApproved);
        }
    }

    private HttpResponse<String> governedSend(HttpRequest request, OutboundPurpose purpose, boolean preApproved) {
        if (outboundPolicyService == null) {
            try {
                return HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
                        .send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new McpConnectionException(
                        "HTTP MCP request failed: " + request.uri().getHost(), e);
            }
        }
        GovernedHttpClient client = GovernedHttpClient.builder(outboundPolicyService)
                .vault(secretVault)
                .purpose(purpose)
                .actor(WorkspaceContext.getUserIdOrUnknown())
                .preApproved(preApproved)
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (OutboundHttpException e) {
            throw new McpConnectionException(
                    "Governed outbound request to " + request.uri().getHost() + " rejected: " + e.category(), e);
        }
    }

    private void applyDirectHeaders(HttpRequest.Builder req, Map<String, String> directHeaders) {
        if (directHeaders == null || directHeaders.isEmpty()) return;
        directHeaders.forEach(req::header);
    }

    private ResolvedMcpHeaders resolveConfiguredHeaders(String runtimeKey, Map<String, String> directHeaders) {
        if (directHeaders != null && !directHeaders.isEmpty()) return ResolvedMcpHeaders.empty();
        if (credentialPort == null) return ResolvedMcpHeaders.empty();
        String connectionId = serverCredentialIds.get(runtimeKey);
        if (connectionId == null) return ResolvedMcpHeaders.empty();
        return credentialPort.resolveHeaders(connectionId);
    }

    private void migratePendingHttpState(String serverName, String runtimeKey) {
        if (runtimeKey.equals(serverName)) return;
        String pendingUrl = serverUrls.remove(serverName);
        if (pendingUrl != null) serverUrls.put(runtimeKey, pendingUrl);
        String pendingSessionId = sessionIds.remove(serverName);
        if (pendingSessionId != null) sessionIds.put(runtimeKey, pendingSessionId);
    }

    private String resolveHttpRuntimeKey(String serverName) {
        return resolveHttpRuntimeKey(serverName, WorkspaceContext.getUserIdOrUnknown());
    }

    String resolveHttpRuntimeKey(String serverName, String userId) {
        if (isKnownUser(userId)) {
            String ownerKey = runtimeKey(serverName, userId);
            if (serverUrls.containsKey(ownerKey) || serverCredentialIds.containsKey(ownerKey)) return ownerKey;
            if (!hasOwnerScopedHttpState(serverName) && serverUrls.containsKey(serverName)) return serverName;
            return ownerKey;
        }
        return serverName;
    }

    private boolean hasOwnerScopedHttpState(String serverName) {
        String suffix = "@" + serverName;
        return serverUrls.keySet().stream().anyMatch(key -> key.endsWith(suffix))
                || serverCredentialIds.keySet().stream().anyMatch(key -> key.endsWith(suffix));
    }

    static String runtimeKey(String serverName, String userId) {
        if (!isKnownUser(userId)) return serverName;
        return userId + "@" + serverName;
    }

    static boolean isKnownUser(String userId) {
        return userId != null && !userId.isBlank() && !"unknown".equals(userId);
    }

    private void recordFailure(String serverName, String phase) {
        if (meterRegistry != null) {
            meterRegistry
                    .counter(METRIC_MCP_LOAD_FAILURE, TAG_SERVER, serverName, TAG_PHASE, phase)
                    .increment();
        }
    }
}
