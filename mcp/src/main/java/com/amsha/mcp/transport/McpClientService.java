package com.amsha.mcp.transport;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;

@ApplicationScoped
@Unremovable
@SuppressWarnings("PMD.ExcessiveClassLength") // Transport coordinator keeps HTTP and STDIO handshake state together.
public class McpClientService {

    private static final Logger log = Logger.getLogger(McpClientService.class);
    private static final String METRIC_MCP_LOAD_FAILURE = "amsha.mcp.tool.load.failure";
    private static final String TAG_SERVER = "server";
    private static final String TAG_PHASE = "phase";

    private final ConcurrentHashMap<String, ReentrantLock> stdioLocks = new ConcurrentHashMap<>();

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    Instance<ManagedExecutor> managedExecutors;

    @Inject
    McpConnectionCredentialPort credentialPort;

    @Inject
    OutboundHttpPolicyService outboundPolicyService;

    @Inject
    SecretVault secretVault;

    private ManagedExecutor getExecutor() {
        return managedExecutors.select(Identifier.Literal.of("external-tool-executor")).stream()
                .findFirst()
                .orElseGet(
                        () -> managedExecutors.select(Default.Literal.INSTANCE).get());
    }

    private final ConcurrentHashMap<String, StdioConfig> stdioConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> serverUrls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> serverCredentialIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OutputStream> processWriters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedReader> processReaders = new ConcurrentHashMap<>();

    private final AtomicInteger requestIdGen = new AtomicInteger(1);
    private ScheduledExecutorService healthCheckExecutor;

    @ConfigProperty(name = "amsha.mcp.health-check-interval-ms", defaultValue = "15000")
    long healthCheckIntervalMs;

    private record StdioConfig(String command, List<String> args, Map<String, String> env, ToolCategory category) {}

    @PostConstruct
    void init() {
        startHealthCheck();
    }

    private void startHealthCheck() {
        if (healthCheckIntervalMs <= 0) return;
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-health-check");
            t.setDaemon(true);
            return t;
        });
        var _ = healthCheckExecutor.scheduleWithFixedDelay(
                this::healthCheck, healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void healthCheck() {
        for (String serverName : processes.keySet()) {
            Process p = processes.get(serverName);
            if (p != null && !p.isAlive()) {
                log.warnf("Health check: MCP STDIO server '%s' process is dead, initiating reconnect", serverName);
                StdioConfig cfg = stdioConfigs.get(serverName);
                if (cfg != null) {
                    ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, k -> new ReentrantLock());
                    lock.lock();
                    try {
                        cleanupStdio(serverName);
                        connectStdio(serverName, cfg.command(), cfg.args(), cfg.env(), cfg.category());
                    } catch (Exception e) {
                        log.errorf("Health check reconnect failed for %s: %s", serverName, LogUtil.rootMessage(e));
                    } finally {
                        lock.unlock();
                    }
                } else {
                    cleanupStdio(serverName);
                }
            }
        }
    }

    @Timeout(10000)
    @Retry(maxRetries = 2, delay = 500)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 30000)
    public void connect(String serverName, String serverUrl) {
        connect(serverName, serverUrl, ToolCategory.NETWORK);
    }

    public void connect(String serverName, String serverUrl, ToolCategory category) {
        connect(serverName, serverUrl, category, Map.of());
    }

    public void connect(
            String serverName, String serverUrl, ToolCategory category, Map<String, String> handshakeHeaders) {
        connectInternal(serverName, serverName, serverUrl, category, handshakeHeaders, false);
    }

    public void connectForUser(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders) {
        connectForUser(serverName, userId, serverUrl, category, handshakeHeaders, false);
    }

    /** Connects after the caller has completed the required MCP-connect approval lifecycle. */
    public void connectForUser(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            boolean preApproved) {
        connectInternal(serverName, runtimeKey(serverName, userId), serverUrl, category, handshakeHeaders, preApproved);
    }

    private void connectInternal(
            String serverName,
            String runtimeKey,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            boolean preApproved) {
        serverUrls.put(runtimeKey, serverUrl);
        try {
            ObjectNode initParams = createInitParams();
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
                if (meterRegistry != null) {
                    meterRegistry
                            .counter(METRIC_MCP_LOAD_FAILURE, TAG_SERVER, serverName, TAG_PHASE, "initialize")
                            .increment();
                }
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
                if (meterRegistry != null) {
                    meterRegistry
                            .counter(METRIC_MCP_LOAD_FAILURE, TAG_SERVER, serverName, TAG_PHASE, "list")
                            .increment();
                }
                throw new McpConnectionException(
                        "MCP tools/list failed [" + toolsResp.statusCode() + "] for: " + serverName);
            }
            JsonNode payload = objectMapper.readTree(toolsResp.body());
            registerTools(serverName, payload.path("result"), McpTransportType.HTTP_SSE, category);

            log.infof(
                    "Connected to HTTP/SSE MCP server '%s', registered tools: %d",
                    serverName,
                    registry.getAll().stream()
                            .filter(t -> t.mcpServerName().equals(serverName))
                            .count());

        } catch (McpConnectionException e) {
            throw e;
        } catch (Exception e) {
            if (meterRegistry != null) {
                meterRegistry
                        .counter(METRIC_MCP_LOAD_FAILURE, TAG_SERVER, serverName, TAG_PHASE, "connect_exception")
                        .increment();
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException("Failed to connect to HTTP MCP server: " + serverName, e);
        }
    }

    public void configureHeaderSecretRefs(
            String serverName, String userId, List<McpConnectionHeaderSecretRefDto> refs) {
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

    public void clearHttpConnectionState(String serverName) {
        clearHttpConnectionStateForKey(serverName);
    }

    public void clearHttpConnectionState(String serverName, String userId) {
        clearHttpConnectionStateForKey(runtimeKey(serverName, userId));
    }

    private void clearHttpConnectionStateForKey(String runtimeKey) {
        serverUrls.remove(runtimeKey);
        sessionIds.remove(runtimeKey);
        String connectionId = serverCredentialIds.remove(runtimeKey);
        if (credentialPort != null && connectionId != null) credentialPort.deleteHeaders(connectionId);
    }

    public Optional<String> httpServerUrl(String serverName) {
        return Optional.ofNullable(serverUrls.get(resolveHttpRuntimeKey(serverName)));
    }

    /**
     * Connect to a local MCP server via STDIO.
     * Starts a sub-process and performs the MCP handshake over its stdin/stdout.
     */
    public void connectStdio(String serverName, String command, List<String> args, Map<String, String> env) {
        connectStdio(serverName, command, args, env, ToolCategory.NETWORK);
    }

    public void connectStdio(
            String serverName, String command, List<String> args, Map<String, String> env, ToolCategory category) {
        stdioConfigs.put(serverName, new StdioConfig(command, args, env, category));
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
            processReaders.put(
                    serverName,
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));

            // 1. Initialize
            ObjectNode initParams = createInitParams();
            sendRequestStdio(serverName, "initialize", initParams); // wait for response

            // 2. Initialized
            sendNotificationStdio(serverName, "notifications/initialized", objectMapper.createObjectNode());

            // 3. List tools
            JsonNode toolsResult = sendRequestStdio(serverName, "tools/list", objectMapper.createObjectNode());
            registerTools(serverName, toolsResult, McpTransportType.STDIO, category);

            log.infof(
                    "Connected to STDIO MCP server '%s', registered tools: %d",
                    serverName,
                    registry.getAll().stream()
                            .filter(t -> t.mcpServerName().equals(serverName))
                            .count());

        } catch (Exception e) {
            if (meterRegistry != null) {
                meterRegistry
                        .counter(METRIC_MCP_LOAD_FAILURE, TAG_SERVER, serverName, TAG_PHASE, "stdio_connect")
                        .increment();
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException("Failed to connect to local MCP server via STDIO: " + serverName, e);
        }
    }

    private ObjectNode createInitParams() {
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "amsha");
        clientInfo.put("version", "1.0");
        initParams.set("clientInfo", clientInfo);
        return initParams;
    }

    @Timeout(10000)
    @Retry(
            maxRetries = 2,
            delay = 1000,
            retryOn = {IOException.class})
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    public String invoke(String serverName, String toolName, String paramsJson) {
        var timer = Timer.builder("amsha.mcp.tool.invocation")
                .tag(TAG_SERVER, serverName)
                .tag("tool", toolName);
        var sample = Timer.start(meterRegistry);
        String exceptionClass = "none";

        try {
            // N3: Guard reconnection logic with per-server lock to prevent race where
            // parallel calls strike a dead server and all try to connectStdio simultaneously.
            ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, k -> new ReentrantLock());
            lock.lock();
            try {
                if (!processes.containsKey(serverName) && stdioConfigs.containsKey(serverName)) {
                    log.infof("reconnecting dead MCP server: %s", serverName);
                    StdioConfig cfg = stdioConfigs.get(serverName);
                    connectStdio(serverName, cfg.command(), cfg.args(), cfg.env(), cfg.category());
                }
            } finally {
                lock.unlock();
            }

            if (processes.containsKey(serverName)) {
                return invokeStdio(serverName, toolName, paramsJson);
            }
            String runtimeKey = resolveHttpRuntimeKey(serverName);
            String url = serverUrls.get(runtimeKey);
            if (url == null) {
                throw new McpConnectionException("No URL registered for HTTP MCP server: " + serverName);
            }

            ObjectNode callParams = createCallParams(toolName, paramsJson);

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
            return extractTextResult(resp.body());

        } catch (RuntimeException e) {
            exceptionClass = e.getClass().getSimpleName();
            throw e;
        } catch (Exception e) {
            exceptionClass = e.getClass().getSimpleName();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to invoke tool '" + toolName + "' on server: " + serverName, e);
        } finally {
            sample.stop(timer.tag("exception", exceptionClass).register(meterRegistry));
        }
    }

    private String invokeStdio(String serverName, String toolName, String paramsJson) {
        try {
            ObjectNode callParams = createCallParams(toolName, paramsJson);
            JsonNode result = sendRequestStdio(serverName, "tools/call", callParams);
            return extractTextResult(objectMapper.writeValueAsString(
                    objectMapper.createObjectNode().set("result", result)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke local tool via STDIO: " + toolName, e);
        }
    }

    private ObjectNode createCallParams(String toolName, String paramsJson) throws Exception {
        ObjectNode callParams = objectMapper.createObjectNode();
        callParams.put("name", toolName);
        callParams.set(
                "arguments",
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

    /**
     * Executes an MCP HTTP request through the governed outbound transport (SSRF/TLS/proxy/DNS-
     * rebinding/redirect enforcement). The MCP commit saga already clears HITL for committed
     * connections must carry an explicit approval decision from their caller; this transport never
     * infers approval merely because a connection is already persisted.
     */
    private HttpResponse<String> governedSend(HttpRequest request, OutboundPurpose purpose, boolean preApproved) {
        // When the governed policy service is not wired (e.g. a manually-constructed test instance
        // without CDI injection), fall back to a direct send so legacy call paths keep working.
        // Production CDI instances always have outboundPolicyService injected.
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
        // Direct headers are accepted only from validated registration/handshake paths.
        // Agent-controlled tool invocation always passes Map.of() and resolves stored vault refs instead.
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
        String userId = WorkspaceContext.getUserIdOrUnknown();
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

    private static String runtimeKey(String serverName, String userId) {
        if (!isKnownUser(userId)) return serverName;
        return userId + "@" + serverName;
    }

    private static boolean isKnownUser(String userId) {
        return userId != null && !userId.isBlank() && !"unknown".equals(userId);
    }

    private void registerTools(String serverName, JsonNode result, McpTransportType transport, ToolCategory category) {
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) return;
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            String desc = tool.path("description").asText("");
            String schema = tool.has("inputSchema") ? tool.path("inputSchema").toString() : "{}";
            registry.register(new DynamicToolCapability(name, desc, serverName, transport, false, schema, category));
        }
    }

    private JsonNode sendRequestStdio(String serverName, String method, ObjectNode params) throws Exception {
        ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, k -> new ReentrantLock());
        lock.lock();
        try {
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
                    throw new RuntimeException(
                            "MCP server error: " + response.get("error").toString());
                }
                return response.get("result");
            } catch (IOException e) {
                // F19 Dead Process Guard: cleanup on any transport failure
                // next retry loop will call connectStdio via invoke() fallback
                log.warnf(
                        "MCP STDIO transport error for %s: %s - tearing down process state",
                        serverName, LogUtil.rootMessage(e));
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
        } catch (IOException e) {
            log.debugf("Failed to close MCP writer for %s: %s", serverName, LogUtil.rootMessage(e));
        }

        try {
            BufferedReader reader = processReaders.remove(serverName);
            if (reader != null) reader.close();
        } catch (IOException e) {
            log.debugf("Failed to close MCP reader for %s: %s", serverName, LogUtil.rootMessage(e));
        }

        Process p = processes.remove(serverName);
        if (p != null) {
            p.destroyForcibly();
            try {
                p.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
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
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                healthCheckExecutor.shutdownNow();
            }
        }
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
