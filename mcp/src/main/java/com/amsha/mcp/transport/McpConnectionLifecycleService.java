package com.amsha.mcp.transport;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCategory;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
@Unremovable
public class McpConnectionLifecycleService {

    private final McpConnectionRequestValidator requestValidator = new McpConnectionRequestValidator();

    @Inject
    Instance<McpConnectionStorePort> stores;

    @Inject
    SecretVault secretVault;

    @Inject
    AuditService auditService;

    @Inject
    McpClientService mcpClientService;

    @Inject
    DynamicToolCapabilityRegistry dynamicToolRegistry;

    @Inject
    OutboundHttpPolicyService outboundHttpPolicyService;

    private final McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
            this::store,
            () -> this.secretVault,
            () -> this.auditService,
            () -> this.mcpClientService,
            () -> this.dynamicToolRegistry);

    void onStartup(@Observes StartupEvent event) {
        orchestrator.onStartup();
    }

    @PreDestroy
    void onShutdown() {
        orchestrator.onShutdown();
    }

    void runStartupReconciliation() {
        orchestrator.runStartupReconciliation();
    }

    public int retryCleanupFailures() {
        return orchestrator.retryCleanupFailures();
    }

    /** Clears approval tasks that cannot survive a process restart before reconnect begins. */
    public int reconcilePendingApprovals() {
        return orchestrator.reconcilePendingApprovals();
    }

    public int reconnectEnabledConnections() {
        return orchestrator.reconnectEnabledConnections();
    }

    static String classifyReconnectFailure(RuntimeException e) {
        return McpConnectionLifecycleOrchestrator.classifyReconnectFailure(e);
    }

    public PersistedConnection persistSuccessfulHttp(
            String name, String url, ToolCategory category, String userId, Map<String, String> headers) {
        requestValidator.validateEndpoint(url);
        return store().map(port -> {
                    List<McpConnectionRequestValidator.ValidatedHeader> validatedHeaders =
                            requestValidator.validateHeaders(headers);
                    McpConnectionDto connection = upsertConnection(port, name, url, category, userId);
                    List<McpConnectionHeaderSecretRefDto> oldRefs = port.listHeaderSecretRefs(connection.id(), userId);
                    List<McpConnectionHeaderSecretRefDto> refs =
                            storeHeaderSecrets(connection.id(), userId, validatedHeaders, oldRefs);
                    port.replaceHeaderSecretRefs(connection.id(), userId, refs);
                    orchestrator.removeReplacedSecrets(port, userId, oldRefs, refs);
                    return new PersistedConnection(connection, refs);
                })
                .orElseGet(() -> new PersistedConnection(null, List.of()));
    }

    public PersistedConnection persistSuccessfulHttp(String name, String url, ToolCategory category, String userId) {
        return persistSuccessfulHttp(name, url, category, userId, Map.of());
    }

    public PersistedConnection persistSuccessfulHttpKeepingHeaders(
            String name, String url, ToolCategory category, String userId) {
        requestValidator.validateEndpoint(url);
        return store().map(port -> {
                    McpConnectionDto connection = upsertConnection(port, name, url, category, userId);
                    return new PersistedConnection(connection, port.listHeaderSecretRefs(connection.id(), userId));
                })
                .orElseGet(() -> new PersistedConnection(null, List.of()));
    }

    public void validateHttpRegistration(String url, Map<String, String> headers) {
        if (outboundHttpPolicyService != null) {
            OutboundPolicyDecision decision =
                    outboundHttpPolicyService.evaluate(url, OutboundPurpose.MCP_TOOL_CALL, null, null);
            if (decision.isDenied()) {
                throw new IllegalArgumentException("MCP endpoint rejected by outbound policy: "
                        + decision.reason().orElse("POLICY_DENIED"));
            }
        }
        requestValidator.validateEndpoint(url);
        requestValidator.validateHeaders(headers);
    }

    private McpConnectionDto upsertConnection(
            McpConnectionStorePort port, String name, String url, ToolCategory category, String userId) {
        Instant now = Instant.now();
        Optional<McpConnectionDto> existing = port.findByNameAndUserId(name, userId);
        McpConnectionDto connection = existing.map(previous -> new McpConnectionDto(
                        previous.id(),
                        userId,
                        name,
                        McpTransportType.HTTP_SSE.name(),
                        url,
                        category.name(),
                        previous.enabled(),
                        previous.state(),
                        previous.approvalTaskId(),
                        previous.revision(),
                        previous.lastErrorCode(),
                        previous.createdAt(),
                        now))
                .orElseGet(() -> new McpConnectionDto(
                        UUID.randomUUID().toString(),
                        userId,
                        name,
                        McpTransportType.HTTP_SSE.name(),
                        url,
                        category.name(),
                        true,
                        now,
                        now));
        port.save(connection);
        return connection;
    }

    private List<McpConnectionHeaderSecretRefDto> storeHeaderSecrets(
            String connectionId,
            String userId,
            List<McpConnectionRequestValidator.ValidatedHeader> headers,
            List<McpConnectionHeaderSecretRefDto> oldRefs) {
        if (headers.isEmpty()) return List.of();
        Instant now = Instant.now();
        Set<String> oldNames = oldRefs.stream()
                .map(McpConnectionHeaderSecretRefDto::headerName)
                .collect(Collectors.toSet());
        return headers.stream()
                .map(header -> {
                    String vaultKey = vaultKeyRef(connectionId, header.normalizedName());
                    runInUserVaultScope(
                            userId,
                            () -> secretVault.store(vaultKey, header.value().getBytes(StandardCharsets.UTF_8), userId));
                    auditService.recordMandatory(
                            AgentSentinels.SYSTEM_SESSION,
                            userId,
                            AgentSentinels.SYSTEM_ROLE,
                            oldNames.contains(header.normalizedName())
                                    ? SecurityEventType.MCP_CREDENTIAL_ROTATED
                                    : SecurityEventType.MCP_CREDENTIAL_CONFIGURED,
                            "connectionId=" + connectionId + " header=" + header.normalizedName());
                    return new McpConnectionHeaderSecretRefDto(
                            UUID.randomUUID().toString(),
                            connectionId,
                            userId,
                            header.normalizedName(),
                            vaultKey,
                            now,
                            now);
                })
                .toList();
    }

    public boolean deleteByNameAndUserId(String name, String userId) {
        return store().flatMap(port -> port.findByNameAndUserId(name, userId)
                        .map(connection -> deleteConnection(port, connection, userId)))
                .orElse(false);
    }

    public boolean deleteByIdAndUserId(String id, String userId) {
        return store().flatMap(port -> port.findByIdAndUserId(id, userId)
                        .map(connection -> deleteConnection(port, connection, userId)))
                .orElse(false);
    }

    public int deleteAllByUserId(String userId) {
        Optional<McpConnectionStorePort> store = store();
        if (store.isEmpty() || userId == null || userId.isBlank()) return 0;
        int deleted = 0;
        for (McpConnectionDto connection : store.get().listByUserId(userId)) {
            if (deleteConnection(store.get(), connection, userId)) deleted++;
        }
        return deleted;
    }

    public List<McpConnectionDto> listByUserId(String userId) {
        return store().map(port -> port.listByUserId(userId)).orElseGet(List::of);
    }

    public Optional<McpConnectionDto> findByNameAndUserId(String name, String userId) {
        return store().flatMap(port -> port.findByNameAndUserId(name, userId));
    }

    public List<McpConnectionToolPolicyDto> listToolPolicies(String connectionId, String userId) {
        return store().map(port -> port.listToolPolicies(connectionId, userId)).orElseGet(List::of);
    }

    public List<McpConnectionHeaderSecretRefDto> headerSecretRefs(String connectionId, String userId) {
        return store().map(port -> port.listHeaderSecretRefs(connectionId, userId))
                .orElseGet(List::of);
    }

    public void replaceToolPolicies(String connectionId, String userId, List<McpConnectionToolPolicyDto> policies) {
        store().ifPresent(port -> port.replaceToolPolicies(connectionId, userId, policies));
    }

    public int setToolEnabled(String connectionId, String userId, String toolName, boolean enabled) {
        return store().map(port -> port.setToolEnabled(connectionId, userId, toolName, enabled))
                .orElse(0);
    }

    public Optional<McpConnectionDto> updateState(
            String connectionId,
            String userId,
            String state,
            boolean enabled,
            String approvalTaskId,
            String lastErrorCode) {
        return store().flatMap(port -> port.findByIdAndUserId(connectionId, userId)
                .flatMap(current -> {
                    McpConnectionDto updated = new McpConnectionDto(
                            current.id(),
                            current.userId(),
                            current.name(),
                            current.transport(),
                            current.endpoint(),
                            current.category(),
                            enabled,
                            state,
                            approvalTaskId,
                            current.revision() + 1,
                            lastErrorCode,
                            current.createdAt(),
                            Instant.now());
                    return port.updateStateIfRevision(updated, current.revision())
                            ? Optional.of(updated)
                            : Optional.empty();
                }));
    }

    private Optional<McpConnectionStorePort> store() {
        return stores.stream().findFirst();
    }

    private boolean deleteConnection(McpConnectionStorePort port, McpConnectionDto connection, String userId) {
        List<McpConnectionHeaderSecretRefDto> refs = port.listHeaderSecretRefs(connection.id(), userId);
        boolean deleted = port.deleteByIdAndUserId(connection.id(), userId) > 0;
        if (!deleted) return false;
        mcpClientService.clearHttpConnectionState(connection.name(), userId);
        for (McpConnectionHeaderSecretRefDto ref : refs) {
            orchestrator.removeVaultSecretOrRecordFailure(port, userId, ref);
        }
        return true;
    }

    private static String vaultKeyRef(String connectionId, String normalizedHeaderName) {
        return "mcp:" + connectionId + ":header:" + normalizedHeaderName;
    }

    void runInUserVaultScope(String userId, Runnable action) {
        McpConnectionLifecycleOrchestrator.runInUserVaultScope(userId, action);
    }

    public record PersistedConnection(
            McpConnectionDto connection, List<McpConnectionHeaderSecretRefDto> headerSecretRefs) {}
}
