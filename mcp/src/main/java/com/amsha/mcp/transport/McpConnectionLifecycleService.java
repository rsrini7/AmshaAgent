package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.OutboundPolicyDecision;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
@Unremovable
public class McpConnectionLifecycleService {

    private static final Logger LOG = Logger.getLogger(McpConnectionLifecycleService.class);
    // Conservative RFC 7230 token subset; intentionally excludes ^, `, |, and ~ for broad client/proxy compatibility.
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[!#$%&'*+\\-.0-9A-Za-z]+$");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "accept",
            "connection",
            "content-length",
            "content-type",
            "host",
            "keep-alive",
            "mcp-session-id",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    @Inject
    Instance<McpConnectionStorePort> stores;

    @Inject
    SecretVault secretVault;

    @Inject
    AuditService auditService;

    @Inject
    McpClientService mcpClientService;

    @Inject
    OutboundHttpPolicyService outboundHttpPolicyService;

    /**
     * Delay before reconciling durable connections at boot. The MCP/HTTP server may
     * not be listening yet when {@link StartupEvent} fires, so reconnecting a
     * localhost/self durable connection immediately fails with connection-refused
     * and logs a misleading WARN. Waiting a moment lets the server come up so
     * self/local connections reconcile successfully.
     */
    private static final long STARTUP_RECONNECT_DELAY_MS = 2_000;

    void onStartup(@Observes StartupEvent event) {
        new Timer("mcp-startup-reconnect", true)
                .schedule(
                        new TimerTask() {
                            @Override
                            public void run() {
                                retryCleanupFailures();
                                reconnectEnabledConnections();
                            }
                        },
                        STARTUP_RECONNECT_DELAY_MS);
    }

    public int retryCleanupFailures() {
        Optional<McpConnectionStorePort> store = store();
        if (store.isEmpty()) return 0;
        int cleaned = 0;
        for (McpConnectionCleanupFailureDto failure : store.get().listCleanupFailures()) {
            try {
                runInUserVaultScope(
                        failure.userId(), () -> secretVault.remove(failure.vaultKeyRef(), failure.userId()));
                store.get().deleteCleanupFailure(failure.id(), failure.userId());
                auditService.recordMandatory(
                        AgentSentinels.SYSTEM_SESSION,
                        failure.userId(),
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.MCP_CREDENTIAL_REMOVED,
                        "connectionId=" + failure.connectionId() + " header=" + failure.headerName()
                                + " cleanupRetry=true");
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

    public int reconnectEnabledConnections() {
        Optional<McpConnectionStorePort> store = store();
        if (store.isEmpty()) return 0;
        int reconnected = 0;
        for (McpConnectionDto connection : store.get().listEnabled()) {
            if (!McpTransportType.HTTP_SSE.name().equals(connection.transport())) {
                LOG.debugf(
                        "Skipping durable MCP reconnect for non-HTTP transport connection=%s transport=%s",
                        connection.name(), connection.transport());
                continue;
            }
            try {
                List<McpConnectionHeaderSecretRefDto> refs =
                        store.get().listHeaderSecretRefs(connection.id(), connection.userId());
                runInUserVaultScope(
                        connection.userId(),
                        () -> mcpClientService.configureHeaderSecretRefs(connection.name(), connection.userId(), refs));
                mcpClientService.connectForUser(
                        connection.name(),
                        connection.userId(),
                        connection.endpoint(),
                        ToolCategory.valueOf(connection.category()),
                        Map.of(),
                        true);
                auditService.recordMandatory(
                        AgentSentinels.SYSTEM_SESSION,
                        connection.userId(),
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.MCP_SERVER_CONNECTED,
                        "server=" + connection.name() + " reconnect=true");
                reconnected++;
            } catch (RuntimeException e) {
                mcpClientService.clearHttpConnectionState(connection.name(), connection.userId());
                auditService.recordMandatory(
                        AgentSentinels.SYSTEM_SESSION,
                        connection.userId(),
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.MCP_CONNECTION_VALIDATION_FAILED,
                        "server=" + connection.name() + " reconnect=true reason=" + LogUtil.rootMessage(e));
                LOG.warnf(
                        "Failed to reconnect durable MCP connection %s for user=%s: %s",
                        connection.name(), connection.userId(), LogUtil.rootMessage(e));
                LOG.debug("Full durable MCP reconnect failure", e);
            }
        }
        return reconnected;
    }

    public PersistedConnection persistSuccessfulHttp(
            String name, String url, ToolCategory category, String userId, Map<String, String> headers) {
        validateEndpoint(url);
        return store().map(port -> {
                    List<ValidatedHeader> validatedHeaders = validateHeaders(headers);
                    McpConnectionDto connection = upsertConnection(port, name, url, category, userId);
                    List<McpConnectionHeaderSecretRefDto> oldRefs = port.listHeaderSecretRefs(connection.id(), userId);
                    List<McpConnectionHeaderSecretRefDto> refs =
                            storeHeaderSecrets(connection.id(), userId, validatedHeaders, oldRefs);
                    port.replaceHeaderSecretRefs(connection.id(), userId, refs);
                    removeReplacedSecrets(port, userId, oldRefs, refs);
                    return new PersistedConnection(connection, refs);
                })
                .orElseGet(() -> new PersistedConnection(null, List.of()));
    }

    public PersistedConnection persistSuccessfulHttp(String name, String url, ToolCategory category, String userId) {
        return persistSuccessfulHttp(name, url, category, userId, Map.of());
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
        validateEndpoint(url);
        validateHeaders(headers);
    }

    private McpConnectionDto upsertConnection(
            McpConnectionStorePort port, String name, String url, ToolCategory category, String userId) {
        Instant now = Instant.now();
        Optional<McpConnectionDto> existing = port.findByNameAndUserId(name, userId);
        McpConnectionDto connection = new McpConnectionDto(
                existing.map(McpConnectionDto::id)
                        .orElseGet(() -> UUID.randomUUID().toString()),
                userId,
                name,
                McpTransportType.HTTP_SSE.name(),
                url,
                category.name(),
                true,
                existing.map(McpConnectionDto::createdAt).orElse(now),
                now);
        port.save(connection);
        return connection;
    }

    private List<McpConnectionHeaderSecretRefDto> storeHeaderSecrets(
            String connectionId,
            String userId,
            List<ValidatedHeader> headers,
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

    private Optional<McpConnectionStorePort> store() {
        return stores.stream().findFirst();
    }

    private boolean deleteConnection(McpConnectionStorePort port, McpConnectionDto connection, String userId) {
        List<McpConnectionHeaderSecretRefDto> refs = port.listHeaderSecretRefs(connection.id(), userId);
        boolean deleted = port.deleteByIdAndUserId(connection.id(), userId) > 0;
        if (!deleted) return false;
        mcpClientService.clearHttpConnectionState(connection.name(), userId);
        for (McpConnectionHeaderSecretRefDto ref : refs) {
            removeVaultSecretOrRecordFailure(port, userId, ref);
        }
        return true;
    }

    private static void validateEndpoint(String url) {
        URI uri = URI.create(url);
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("MCP endpoint URL must not contain user-info credentials");
        }
    }

    private static List<ValidatedHeader> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return List.of();
        Set<String> names = new HashSet<>();
        return headers.entrySet().stream()
                .map(entry -> validateHeader(entry.getKey(), entry.getValue()))
                .peek(header -> {
                    if (!names.add(header.normalizedName())) {
                        throw new IllegalArgumentException("Duplicate MCP header name: " + header.normalizedName());
                    }
                })
                .toList();
    }

    private static ValidatedHeader validateHeader(String name, String value) {
        if (name == null || name.isBlank() || !HEADER_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid MCP header name: " + name);
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_HEADERS.contains(normalizedName)) {
            throw new IllegalArgumentException("MCP header is transport-owned or forbidden: " + normalizedName);
        }
        if (value == null || value.isBlank() || containsUnsafeHeaderValue(value)) {
            throw new IllegalArgumentException("Invalid MCP header value for: " + normalizedName);
        }
        return new ValidatedHeader(normalizedName, value);
    }

    private static boolean containsUnsafeHeaderValue(String value) {
        if (value.contains("${") || value.contains("$(") || value.contains("`")) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E || c == 0x7F) return true;
        }
        return false;
    }

    private static String vaultKeyRef(String connectionId, String normalizedHeaderName) {
        return "mcp:" + connectionId + ":header:" + normalizedHeaderName;
    }

    private void removeReplacedSecrets(
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

    private void removeVaultSecretOrRecordFailure(
            McpConnectionStorePort port, String userId, McpConnectionHeaderSecretRefDto ref) {
        try {
            runInUserVaultScope(userId, () -> secretVault.remove(ref.vaultKeyRef(), userId));
            auditService.recordMandatory(
                    AgentSentinels.SYSTEM_SESSION,
                    userId,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.MCP_CREDENTIAL_REMOVED,
                    "connectionId=" + ref.connectionId() + " header=" + ref.headerName());
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
        auditService.recordMandatory(
                AgentSentinels.SYSTEM_SESSION,
                failure.userId(),
                AgentSentinels.SYSTEM_ROLE,
                SecurityEventType.MCP_CREDENTIAL_CLEANUP_FAILED,
                "connectionId=" + failure.connectionId() + " header=" + failure.headerName() + " attempts="
                        + failure.attempts());
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
     * Runs vault operations inside an active workspace scope.
     *
     * <p>{@link SecretVault} derives and caches per-user keys through the {@code @WorkspaceScoped}
     * {@link com.amsha.core.security.VaultKeyCache}. REST/admin entry points and the startup
     * reconnect path execute outside any workspace context, so the scope must be activated
     * explicitly. The vault key is derived from the stable per-user scope id, keeping secrets
     * decryptable across restarts and requests.
     */
    private void runInUserVaultScope(String userId, Runnable action) {
        WorkspaceScopeHolder.run(VaultConstants.USER_VAULT_SCOPE_PREFIX + userId, userId, null, action);
    }

    public record PersistedConnection(
            McpConnectionDto connection, List<McpConnectionHeaderSecretRefDto> headerSecretRefs) {}

    private record ValidatedHeader(String normalizedName, String value) {}
}
