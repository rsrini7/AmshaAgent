package com.amsha.mcp.transport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCategory;

import org.jboss.logging.Logger;

/**
 * Orchestrates startup reconciliation, periodic/startup reconnect, and deferred credential
 * cleanup for durable MCP connections.
 */
class McpConnectionLifecycleOrchestrator {

    private static final Logger LOG = Logger.getLogger(McpConnectionLifecycleOrchestrator.class);

    /**
     * Delay before reconciling durable connections at boot. The MCP/HTTP server may
     * not be listening yet when StartupEvent fires, so reconnecting a localhost/self
     * durable connection immediately fails with connection-refused and logs a misleading
     * WARN. Waiting a moment lets the server come up so self/local connections reconcile
     * successfully.
     */
    private static final long STARTUP_RECONNECT_DELAY_MS = 2_000;

    private final Supplier<Optional<McpConnectionStorePort>> storeSupplier;
    private final Supplier<SecretVault> secretVaultSupplier;
    private final Supplier<AuditService> auditServiceSupplier;
    private final Supplier<McpClientService> mcpClientServiceSupplier;
    private final Supplier<DynamicToolCapabilityRegistry> dynamicToolRegistrySupplier;

    private final Object startupReconnectLock = new Object();
    private volatile Timer startupReconnectTimer;
    private volatile boolean shuttingDown;

    McpConnectionLifecycleOrchestrator(
            Supplier<Optional<McpConnectionStorePort>> storeSupplier,
            Supplier<SecretVault> secretVaultSupplier,
            Supplier<AuditService> auditServiceSupplier,
            Supplier<McpClientService> mcpClientServiceSupplier,
            Supplier<DynamicToolCapabilityRegistry> dynamicToolRegistrySupplier) {
        this.storeSupplier = storeSupplier;
        this.secretVaultSupplier = secretVaultSupplier;
        this.auditServiceSupplier = auditServiceSupplier;
        this.mcpClientServiceSupplier = mcpClientServiceSupplier;
        this.dynamicToolRegistrySupplier = dynamicToolRegistrySupplier;
    }

    void onStartup() {
        synchronized (startupReconnectLock) {
            if (shuttingDown || startupReconnectTimer != null) {
                return;
            }
            startupReconnectTimer = new Timer("mcp-startup-reconnect", true);
            startupReconnectTimer.schedule(
                    new TimerTask() {
                        @Override
                        public void run() {
                            runStartupReconciliation();
                        }
                    },
                    STARTUP_RECONNECT_DELAY_MS);
        }
    }

    void onShutdown() {
        synchronized (startupReconnectLock) {
            shuttingDown = true;
            if (startupReconnectTimer != null) {
                startupReconnectTimer.cancel();
                startupReconnectTimer = null;
            }
        }
    }

    void runStartupReconciliation() {
        if (shuttingDown) {
            return;
        }
        try {
            retryCleanupFailures();
            if (shuttingDown) {
                return;
            }
            reconcilePendingApprovals();
            if (shuttingDown) {
                return;
            }
            reconnectEnabledConnections();
        } catch (RuntimeException e) {
            if (!shuttingDown) {
                LOG.warnf("MCP startup reconciliation failed: %s", LogUtil.rootMessage(e));
                LOG.debug("Full MCP startup reconciliation failure", e);
            }
        }
    }

    int retryCleanupFailures() {
        Optional<McpConnectionStorePort> store = store();
        if (store.isEmpty()) return 0;
        int cleaned = 0;
        SecretVault vault = secretVault();
        AuditService audit = auditService();
        for (McpConnectionCleanupFailureDto failure : store.get().listCleanupFailures()) {
            try {
                runInUserVaultScope(failure.userId(), () -> {
                    if (vault != null) vault.remove(failure.vaultKeyRef(), failure.userId());
                });
                store.get().deleteCleanupFailure(failure.id(), failure.userId());
                if (audit != null) {
                    audit.recordMandatory(
                            AgentSentinels.SYSTEM_SESSION,
                            failure.userId(),
                            AgentSentinels.SYSTEM_ROLE,
                            SecurityEventType.MCP_CREDENTIAL_REMOVED,
                            "connectionId=" + failure.connectionId() + " header=" + failure.headerName()
                                    + " cleanupRetry=true");
                }
                cleaned++;
            } catch (RuntimeException e) {
                McpConnectionCleanupFailureDto updated = withCleanupRetry(failure, LogUtil.rootMessage(e));
                store.get().saveCleanupFailure(updated);
                auditCleanupFailure(updated);
                LOG.warnf(
                        "Failed MCP credential cleanup retry connectionId=%s user=%s header=%s attempts=%d: %s",
                        updated.connectionId(),
                        updated.userId(),
                        updated.headerName(),
                        updated.attempts(),
                        LogUtil.rootMessage(e));
                LOG.debug("Full MCP credential cleanup retry failure", e);
            }
        }
        return cleaned;
    }

    /** Clears approval tasks that cannot survive a process restart before reconnect begins. */
    int reconcilePendingApprovals() {
        return store().map(store -> McpConnectionStartupSupport.reconcilePendingApprovals(store, auditService()))
                .orElse(0);
    }

    int reconnectEnabledConnections() {
        Optional<McpConnectionStorePort> store = store();
        if (store.isEmpty()) return 0;
        int reconnected = 0;
        McpClientService clientService = mcpClientService();
        AuditService audit = auditService();
        DynamicToolCapabilityRegistry toolRegistry = dynamicToolRegistry();

        for (McpConnectionDto connection : store.get().listEnabled()) {
            if (!McpTransportType.HTTP_SSE.name().equals(connection.transport())) {
                LOG.debugf(
                        "Skipping durable MCP reconnect for non-HTTP transport connection=%s transport=%s",
                        connection.name(), connection.transport());
                continue;
            }
            try {
                Set<String> enabledToolNames =
                        store.get().listToolPolicies(connection.id(), connection.userId()).stream()
                                .filter(McpConnectionToolPolicyDto::enabled)
                                .map(McpConnectionToolPolicyDto::toolName)
                                .collect(Collectors.toSet());
                McpConnectionStartupSupport.clearRegisteredTools(toolRegistry, connection.userId(), connection.name());
                List<McpConnectionHeaderSecretRefDto> refs =
                        store.get().listHeaderSecretRefs(connection.id(), connection.userId());
                runInUserVaultScope(connection.userId(), () -> {
                    if (clientService != null) {
                        clientService.configureHeaderSecretRefs(connection.name(), connection.userId(), refs);
                        clientService.reconnectDurableForUser(
                                connection.name(),
                                connection.userId(),
                                connection.endpoint(),
                                ToolCategory.valueOf(connection.category()),
                                Map.of(),
                                enabledToolNames);
                    }
                });
                McpConnectionStartupSupport.reconcileToolPolicies(
                        toolRegistry, store.get(), connection, enabledToolNames);
                store.get()
                        .save(new McpConnectionDto(
                                connection.id(),
                                connection.userId(),
                                connection.name(),
                                connection.transport(),
                                connection.endpoint(),
                                connection.category(),
                                connection.enabled(),
                                "CONNECTED",
                                null,
                                connection.revision() + 1,
                                null,
                                connection.createdAt(),
                                Instant.now()));
                if (audit != null) {
                    audit.recordMandatory(
                            AgentSentinels.SYSTEM_SESSION,
                            connection.userId(),
                            AgentSentinels.SYSTEM_ROLE,
                            SecurityEventType.MCP_SERVER_CONNECTED,
                            "server=" + connection.name() + " reconnect=true");
                }
                reconnected++;
            } catch (RuntimeException e) {
                if (clientService != null) {
                    clientService.clearHttpConnectionState(connection.name(), connection.userId());
                }
                store.get()
                        .save(new McpConnectionDto(
                                connection.id(),
                                connection.userId(),
                                connection.name(),
                                connection.transport(),
                                connection.endpoint(),
                                connection.category(),
                                connection.enabled(),
                                "DEGRADED",
                                connection.approvalTaskId(),
                                connection.revision() + 1,
                                classifyReconnectFailure(e),
                                connection.createdAt(),
                                Instant.now()));
                if (audit != null) {
                    audit.recordMandatory(
                            AgentSentinels.SYSTEM_SESSION,
                            connection.userId(),
                            AgentSentinels.SYSTEM_ROLE,
                            SecurityEventType.MCP_CONNECTION_VALIDATION_FAILED,
                            "server=" + connection.name() + " reconnect=true reason=" + LogUtil.rootMessage(e));
                }
                LOG.warnf(
                        "Failed to reconnect durable MCP connection %s for user=%s: %s",
                        connection.name(), connection.userId(), LogUtil.rootMessage(e));
                LOG.debug("Full durable MCP reconnect failure", e);
            }
        }
        return reconnected;
    }

    static String classifyReconnectFailure(RuntimeException e) {
        return e instanceof McpConnectionException ? "HANDSHAKE_FAILED" : "MCP_RECONNECT_FAILED";
    }

    void removeReplacedSecrets(
            McpConnectionStorePort port,
            String userId,
            List<McpConnectionHeaderSecretRefDto> oldRefs,
            List<McpConnectionHeaderSecretRefDto> newRefs) {
        Set<String> newKeys = newRefs.stream()
                .map(McpConnectionHeaderSecretRefDto::vaultKeyRef)
                .collect(Collectors.toSet());
        for (McpConnectionHeaderSecretRefDto oldRef : oldRefs) {
            if (!newKeys.contains(oldRef.vaultKeyRef())) {
                removeVaultSecretOrRecordFailure(port, userId, oldRef);
            }
        }
    }

    void removeVaultSecretOrRecordFailure(
            McpConnectionStorePort port, String userId, McpConnectionHeaderSecretRefDto ref) {
        try {
            SecretVault vault = secretVault();
            runInUserVaultScope(userId, () -> {
                if (vault != null) vault.remove(ref.vaultKeyRef(), userId);
            });
            AuditService audit = auditService();
            if (audit != null) {
                audit.recordMandatory(
                        AgentSentinels.SYSTEM_SESSION,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.MCP_CREDENTIAL_REMOVED,
                        "connectionId=" + ref.connectionId() + " header=" + ref.headerName());
            }
        } catch (RuntimeException e) {
            McpConnectionCleanupFailureDto failure = newCleanupFailure(userId, ref, LogUtil.rootMessage(e));
            port.saveCleanupFailure(failure);
            auditCleanupFailure(failure);
            LOG.warnf(
                    "Queued MCP credential cleanup retry connectionId=%s user=%s header=%s: %s",
                    ref.connectionId(), userId, ref.headerName(), LogUtil.rootMessage(e));
            LOG.debug("Full MCP credential cleanup failure", e);
        }
    }

    private void auditCleanupFailure(McpConnectionCleanupFailureDto failure) {
        AuditService audit = auditService();
        if (audit != null) {
            audit.recordMandatory(
                    AgentSentinels.SYSTEM_SESSION,
                    failure.userId(),
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.MCP_CREDENTIAL_CLEANUP_FAILED,
                    "connectionId=" + failure.connectionId() + " header=" + failure.headerName() + " attempts="
                            + failure.attempts());
        }
    }

    private static McpConnectionCleanupFailureDto newCleanupFailure(
            String userId, McpConnectionHeaderSecretRefDto ref, String reason) {
        Instant now = Instant.now();
        return new McpConnectionCleanupFailureDto(
                UUID.randomUUID().toString(),
                ref.connectionId(),
                userId,
                ref.headerName(),
                ref.vaultKeyRef(),
                safeReason(reason),
                1,
                now,
                now);
    }

    private static McpConnectionCleanupFailureDto withCleanupRetry(
            McpConnectionCleanupFailureDto failure, String reason) {
        return new McpConnectionCleanupFailureDto(
                failure.id(),
                failure.connectionId(),
                failure.userId(),
                failure.headerName(),
                failure.vaultKeyRef(),
                safeReason(reason),
                failure.attempts() + 1,
                failure.createdAt(),
                Instant.now());
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "cleanup failed" : reason;
    }

    /**
     * Runs vault and connection operations inside an active user workspace and context scope.
     *
     * <p>{@link SecretVault} derives and caches per-user keys through the {@code @WorkspaceScoped}
     * {@link com.amsha.security.VaultKeyCache}, while outbound governed transport resolves actor identity
     * via {@link WorkspaceContext#getUserIdOrUnknown()}. REST/admin entry points and the startup
     * reconnect path execute outside any workspace context, so both scopes must be activated
     * explicitly.
     */
    static void runInUserVaultScope(String userId, Runnable action) {
        WorkspaceContext.run(
                AgentSentinels.SYSTEM_SESSION,
                userId,
                null,
                AutonomyLevel.FULL,
                AgentSentinels.SYSTEM_ROLE,
                () -> WorkspaceScopeHolder.run(VaultConstants.USER_VAULT_SCOPE_PREFIX + userId, userId, null, action));
    }

    private Optional<McpConnectionStorePort> store() {
        return storeSupplier.get();
    }

    private SecretVault secretVault() {
        return secretVaultSupplier.get();
    }

    private AuditService auditService() {
        return auditServiceSupplier.get();
    }

    private McpClientService mcpClientService() {
        return mcpClientServiceSupplier.get();
    }

    private DynamicToolCapabilityRegistry dynamicToolRegistry() {
        return dynamicToolRegistrySupplier.get();
    }
}
