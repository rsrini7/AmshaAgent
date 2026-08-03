package com.amsha.mcp.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;

/** Cohesive persistence, registry, and response helpers for MCP management. */
final class McpManagementSupport {
    private static final Duration TOOL_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final McpClientService clientService;
    private final McpConnectionLifecycleService lifecycleService;
    private final DynamicToolCapabilityRegistry registry;
    private final AuditService auditService;

    McpManagementSupport(
            McpClientService clientService,
            McpConnectionLifecycleService lifecycleService,
            DynamicToolCapabilityRegistry registry,
            AuditService auditService) {
        this.clientService = clientService;
        this.lifecycleService = lifecycleService;
        this.registry = registry;
        this.auditService = auditService;
    }

    void replacePolicies(String connectionId, String userId, List<McpClientService.DiscoveredTool> tools) {
        Instant now = Instant.now();
        lifecycleService.replaceToolPolicies(
                connectionId,
                userId,
                tools.stream()
                        .map(tool -> new McpConnectionToolPolicyDto(
                                UUID.randomUUID().toString(),
                                connectionId,
                                userId,
                                tool.name(),
                                false,
                                tool.description(),
                                tool.inputSchema(),
                                sha256(tool.inputSchema()),
                                now,
                                0,
                                now))
                        .toList());
    }

    Set<String> enabledToolNames(McpConnectionDto connection, String userId) {
        return lifecycleService.listToolPolicies(connection.id(), userId).stream()
                .filter(McpConnectionToolPolicyDto::enabled)
                .map(McpConnectionToolPolicyDto::toolName)
                .collect(Collectors.toSet());
    }

    void configureStoredHeaders(McpConnectionDto connection, String userId) {
        List<McpConnectionHeaderSecretRefDto> refs = lifecycleService.headerSecretRefs(connection.id(), userId);
        clientService.configureHeaderSecretRefs(connection.name(), userId, refs);
    }

    McpManagementService.ConnectionSnapshot snapshot(McpConnectionDto connection) {
        List<McpConnectionToolPolicyDto> policies =
                lifecycleService.listToolPolicies(connection.id(), connection.userId());
        long enabled =
                policies.stream().filter(McpConnectionToolPolicyDto::enabled).count();
        return new McpManagementService.ConnectionSnapshot(
                connection.name(),
                connection.endpoint(),
                connection.state(),
                connection.transport(),
                enabled,
                policies.size(),
                connection.revision(),
                policies);
    }

    int clearRegisteredTools(String userId, String serverName) {
        List<String> toolNames = registry.getAll(userId).stream()
                .filter(tool -> serverName.equals(tool.mcpServerName()))
                .map(tool -> tool.toolName())
                .toList();
        toolNames.forEach(toolName -> registry.drainAndDeregister(userId, toolName, TOOL_DRAIN_TIMEOUT));
        return toolNames.size();
    }

    void deregisterTool(String userId, String toolName) {
        registry.drainAndDeregister(userId, toolName, TOOL_DRAIN_TIMEOUT);
    }

    McpConnectionDto findConnection(String userId, String name) {
        return lifecycleService
                .findByNameAndUserId(name, userId)
                .orElseThrow(() -> new McpConnectionException("MCP connection not found: " + name));
    }

    static void checkRevision(McpConnectionDto connection, Long expectedRevision) {
        if (expectedRevision != null && expectedRevision.longValue() != connection.revision()) {
            throw new McpRevisionConflictException(expectedRevision, connection.revision());
        }
    }

    void audit(String userId, SecurityEventType type, String details) {
        auditService.recordMandatory("mcp-management", userId, "system", type, details);
    }

    static String classify(RuntimeException e) {
        return e instanceof McpConnectionException ? "HANDSHAKE_FAILED" : "MCP_OPERATION_FAILED";
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    void validateRequest(String name, String endpoint, Map<String, String> headers) {
        requireName(name);
        lifecycleService.validateHttpRegistration(endpoint, headers == null ? Map.of() : headers);
    }

    void markPendingIfPresent(String userId, String name, String taskId) {
        lifecycleService.findByNameAndUserId(name, userId).ifPresent(connection -> markPending(connection, taskId));
    }

    void markPending(McpConnectionDto connection, String taskId) {
        lifecycleService.updateState(
                connection.id(),
                connection.userId(),
                McpManagementService.STATE_PENDING_APPROVAL,
                connection.enabled(),
                taskId,
                null);
    }

    static void requireUser(String userId) {
        if (isBlank(userId) || "unknown".equals(userId)) {
            throw new IllegalArgumentException("MCP user is required");
        }
    }

    static void requireName(String name) {
        if (isBlank(name) || name.length() > 128) {
            throw new IllegalArgumentException("MCP name is invalid");
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
