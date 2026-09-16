package com.amsha.mcp.transport;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.DynamicToolOperationDefaults;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.OutboundApprovalReceipt;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/**
 * Stable CDI-facing facade for Model Context Protocol (MCP) clients.
 * Coordinates protocol handshakes, approval enforcement, dynamic tool registration,
 * and delegates transport operations to {@link HttpMcpTransportClient} and {@link StdioMcpTransportClient}.
 */
@ApplicationScoped
@Unremovable
public class McpClientService {

    /** Non-secret tool metadata returned by an MCP tools/list handshake. */
    public record DiscoveredTool(String name, String description, String inputSchema) {}

    private static final Logger log = Logger.getLogger(McpClientService.class);
    private static final String METRIC_MCP_LOAD_FAILURE = "amsha.mcp.tool.load.failure";
    private static final String TAG_SERVER = "server";
    private static final String TAG_PHASE = "phase";

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
    ApprovalReceiptIssuer approvalReceiptIssuer;

    @Inject
    SecretVault secretVault;

    @ConfigProperty(name = "amsha.mcp.health-check-interval-ms", defaultValue = "15000")
    long healthCheckIntervalMs;

    private HttpMcpTransportClient httpTransport;
    private StdioMcpTransportClient stdioTransport;

    @PostConstruct
    void init() {
        var jsonRpcCodec = new McpJsonRpcCodec(objectMapper);
        httpTransport = new HttpMcpTransportClient(
                objectMapper, meterRegistry, credentialPort, outboundPolicyService, secretVault, jsonRpcCodec);
        stdioTransport = new StdioMcpTransportClient(
                objectMapper,
                meterRegistry,
                this::getExecutor,
                (srv, tools, cat, owner) -> registerTools(srv, tools, McpTransportType.STDIO, cat, owner, false, null),
                jsonRpcCodec);
        stdioTransport.init(healthCheckIntervalMs);
    }

    HttpMcpTransportClient httpTransport() {
        return httpTransport;
    }

    StdioMcpTransportClient stdioTransport() {
        return stdioTransport;
    }

    private ManagedExecutor getExecutor() {
        if (managedExecutors == null) return null;
        return managedExecutors.select(Identifier.Literal.of("external-tool-executor")).stream()
                .findFirst()
                .orElseGet(
                        () -> managedExecutors.select(Default.Literal.INSTANCE).get());
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
        connectInternal(serverName, serverName, serverUrl, category, handshakeHeaders, false, currentOwner());
    }

    /** Performs the unapproved handshake and returns tool metadata without registering executable tools. */
    public List<DiscoveredTool> discoverForUser(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders) {
        return discoverForUser(serverName, userId, serverUrl, category, handshakeHeaders, (String) null);
    }

    /** Performs discovery with a single-use checkpoint approval receipt. */
    public List<DiscoveredTool> discoverForUser(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            String approvalToken) {
        return connectInternal(
                serverName,
                HttpMcpTransportClient.runtimeKey(serverName, userId),
                serverUrl,
                category,
                handshakeHeaders,
                verifyApproval(userId, serverUrl, approvalToken),
                userId,
                Set.of());
    }

    /** Performs the unapproved handshake and registers only the explicitly enabled tool names. */
    public List<DiscoveredTool> connectForUserWithEnabledTools(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            Set<String> enabledToolNames) {
        return connectForUserWithEnabledTools(
                serverName, userId, serverUrl, category, handshakeHeaders, (String) null, enabledToolNames);
    }

    /** Performs activation with a single-use checkpoint approval receipt. */
    public List<DiscoveredTool> connectForUserWithEnabledTools(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            String approvalToken,
            Set<String> enabledToolNames) {
        return connectInternal(
                serverName,
                HttpMcpTransportClient.runtimeKey(serverName, userId),
                serverUrl,
                category,
                handshakeHeaders,
                verifyApproval(userId, serverUrl, approvalToken),
                userId,
                enabledToolNames == null ? Set.of() : Set.copyOf(enabledToolNames));
    }

    /** Performs activation after a receipt scoped to MCP_TOOL_ENABLE. */
    public List<DiscoveredTool> connectForUserWithEnabledTools(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            String approvalToken,
            OutboundPurpose approvalPurpose,
            Set<String> enabledToolNames) {
        return connectInternal(
                serverName,
                HttpMcpTransportClient.runtimeKey(serverName, userId),
                serverUrl,
                category,
                handshakeHeaders,
                verifyApproval(userId, serverUrl, approvalToken, approvalPurpose),
                userId,
                enabledToolNames == null ? Set.of() : Set.copyOf(enabledToolNames));
    }

    /**
     * Reconnects an already-persisted durable connection on startup. Since the connection was
     * authorized, committed, and persisted in the durable store prior to restart, it is restored
     * with {@code preApproved = true} to survive process restarts without redundant HITL prompts.
     */
    public List<DiscoveredTool> reconnectDurableForUser(
            String serverName,
            String userId,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            Set<String> enabledToolNames) {
        return connectInternal(
                serverName,
                HttpMcpTransportClient.runtimeKey(serverName, userId),
                serverUrl,
                category,
                handshakeHeaders,
                true,
                userId,
                enabledToolNames == null ? Set.of() : Set.copyOf(enabledToolNames));
    }

    private boolean verifyApproval(String userId, String serverUrl, String approvalToken) {
        return verifyApproval(userId, serverUrl, approvalToken, OutboundPurpose.MCP_CONNECT);
    }

    private boolean verifyApproval(
            String userId, String serverUrl, String approvalToken, OutboundPurpose approvalPurpose) {
        if (approvalToken == null || approvalToken.isBlank() || approvalReceiptIssuer == null) return false;
        OutboundApprovalReceipt receipt = approvalReceiptIssuer.verifyAndConsume(
                approvalToken, approvalPurpose, URI.create(serverUrl).getHost());
        if (receipt == null || !userId.equals(receipt.approvedByUserId())) {
            throw new McpConnectionException("MCP connection requires approval for requesting user");
        }
        return true;
    }

    private List<DiscoveredTool> connectInternal(
            String serverName,
            String runtimeKey,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            boolean preApproved,
            String ownerUserId) {
        return connectInternal(
                serverName, runtimeKey, serverUrl, category, handshakeHeaders, preApproved, ownerUserId, null);
    }

    private List<DiscoveredTool> connectInternal(
            String serverName,
            String runtimeKey,
            String serverUrl,
            ToolCategory category,
            Map<String, String> handshakeHeaders,
            boolean preApproved,
            String ownerUserId,
            Set<String> toolNamesToRegister) {
        DynamicToolOperationDefaults.requireFor(category);
        try {
            JsonNode toolsResult =
                    httpTransport.connect(serverName, runtimeKey, serverUrl, handshakeHeaders, preApproved);
            List<DiscoveredTool> discoveredTools = registerTools(
                    serverName,
                    toolsResult,
                    McpTransportType.HTTP_SSE,
                    category,
                    ownerUserId,
                    httpTransport.isSelfConnectUrl(serverUrl),
                    toolNamesToRegister);

            log.infof(
                    "Connected to HTTP/SSE MCP server '%s', registered tools: %d",
                    serverName,
                    registry.getAll().stream()
                            .filter(t -> t.mcpServerName().equals(serverName))
                            .count());
            return discoveredTools;
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
        httpTransport.configureHeaderSecretRefs(serverName, userId, refs);
    }

    public void clearHttpConnectionState(String serverName) {
        httpTransport.clearConnectionState(serverName);
    }

    public void clearHttpConnectionState(String serverName, String userId) {
        httpTransport.clearConnectionState(serverName, userId);
    }

    public Optional<String> httpServerUrl(String serverName) {
        return httpTransport.serverUrl(serverName);
    }

    public boolean hasStdioServer(String serverName) {
        return stdioTransport.hasServer(serverName);
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
        connectStdio(serverName, command, args, env, category, requireCurrentOwner());
    }

    public void connectStdio(
            String serverName,
            String command,
            List<String> args,
            Map<String, String> env,
            ToolCategory category,
            String ownerUserId) {
        ownerUserId = requireOwner(ownerUserId);
        DynamicToolOperationDefaults.requireFor(category);
        stdioTransport.connect(serverName, command, args, env, category, ownerUserId);
        log.infof(
                "Connected to STDIO MCP server '%s', registered tools: %d",
                serverName,
                registry.getAll().stream()
                        .filter(t -> t.mcpServerName().equals(serverName))
                        .count());
    }

    @Timeout(10000)
    @Retry(
            maxRetries = 2,
            delay = 1000,
            retryOn = {IOException.class})
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    public String invoke(String serverName, String toolName, String paramsJson) {
        return invoke(serverName, toolName, paramsJson, currentOwner());
    }

    public String invoke(String serverName, String toolName, String paramsJson, String ownerUserId) {
        var timer = Timer.builder("amsha.mcp.tool.invocation")
                .tag(TAG_SERVER, serverName)
                .tag("tool", toolName);
        var sample = Timer.start(meterRegistry);
        String exceptionClass = "none";

        try {
            if (stdioTransport.hasServer(serverName) || stdioTransport.hasConfig(serverName)) {
                return stdioTransport.invoke(serverName, toolName, paramsJson);
            }
            return httpTransport.invoke(serverName, toolName, paramsJson, ownerUserId);

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

    String extractTextResult(String body) throws Exception {
        return httpTransport.extractTextResult(body);
    }

    void healthCheck() {
        stdioTransport.healthCheck();
    }

    List<DiscoveredTool> registerTools(
            String serverName,
            JsonNode result,
            McpTransportType transport,
            ToolCategory category,
            String ownerUserId,
            boolean skipInternalNames,
            Set<String> toolNamesToRegister) {
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) return List.of();
        List<DiscoveredTool> discovered = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            if (name.isBlank()) continue;
            if (skipInternalNames && registry.isInternalToolName(name)) {
                log.warnf("Skipping MCP tool '%s' from server '%s': internal tool name", name, serverName);
                continue;
            }
            String desc = tool.path("description").asText("");
            String schema = tool.has("inputSchema") ? tool.path("inputSchema").toString() : "{}";
            discovered.add(new DiscoveredTool(name, desc, schema));
            if (toolNamesToRegister != null && !toolNamesToRegister.contains(name)) continue;
            registry.registerForUser(
                    ownerUserId, new DynamicToolCapability(name, desc, serverName, transport, false, schema, category));
        }
        return List.copyOf(discovered);
    }

    private static String currentOwner() {
        try {
            return WorkspaceContext.getUserIdOrUnknown();
        } catch (RuntimeException _) {
            return "unknown";
        }
    }

    private static String requireCurrentOwner() {
        return requireOwner(currentOwner());
    }

    private static String requireOwner(String ownerUserId) {
        if (!HttpMcpTransportClient.isKnownUser(ownerUserId)) {
            throw new IllegalArgumentException("MCP STDIO registration requires a bound user owner");
        }
        return ownerUserId;
    }

    @PreDestroy
    void cleanup() {
        log.info("McpClientService shutting down - terminating local MCP processes");
        if (stdioTransport != null) {
            stdioTransport.close();
        }
    }
}
