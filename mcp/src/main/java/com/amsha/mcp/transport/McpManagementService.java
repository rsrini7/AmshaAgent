package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;

/** Shared, user-scoped orchestration for MCP lifecycle surfaces. */
@ApplicationScoped
public class McpManagementService {
    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    public static final String STATE_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATE_CONNECTING = "CONNECTING";
    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_DEGRADED = "DEGRADED";
    public static final String STATE_FAILED = "FAILED";

    private final McpClientService clientService;
    private final McpConnectionLifecycleService lifecycleService;
    private final McpManagementSupport support;
    private final McpApprovalService approvalService;

    @Inject
    public McpManagementService(
            McpClientService clientService,
            McpConnectionLifecycleService lifecycleService,
            DynamicToolCapabilityRegistry registry,
            AuditService auditService,
            McpApprovalService approvalService) {
        this.clientService = clientService;
        this.lifecycleService = lifecycleService;
        this.support = new McpManagementSupport(clientService, lifecycleService, registry, auditService);
        this.approvalService = approvalService;
    }

    public McpApprovalService.PendingApproval requestDiscovery(
            String userId, String name, String endpoint, Map<String, String> headers) {
        support.validateRequest(name, endpoint, headers);
        return approvalService.request(userId, McpApprovalService.Operation.DISCOVER, name, endpoint, headers, null);
    }

    public McpApprovalService.PendingApproval requestConnect(
            String userId, String name, String endpoint, Map<String, String> headers) {
        support.validateRequest(name, endpoint, headers);
        McpApprovalService.PendingApproval approval =
                approvalService.request(userId, McpApprovalService.Operation.CONNECT, name, endpoint, headers, null);
        support.markPendingIfPresent(userId, name, approval.taskId());
        return approval;
    }

    public McpApprovalService.PendingApproval requestReconnect(String userId, String name) {
        return requestReconnect(userId, name, null);
    }

    public McpApprovalService.PendingApproval requestReconnect(String userId, String name, Long expectedRevision) {
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        McpApprovalService.PendingApproval approval = approvalService.request(
                userId,
                McpApprovalService.Operation.RECONNECT,
                name,
                connection.endpoint(),
                Map.of(),
                null,
                expectedRevision);
        support.markPending(connection, approval.taskId());
        return approval;
    }

    public McpApprovalService.PendingApproval requestRotateHeaders(
            String userId, String name, Map<String, String> headers) {
        return requestRotateHeaders(userId, name, headers, null);
    }

    public McpApprovalService.PendingApproval requestRotateHeaders(
            String userId, String name, Map<String, String> headers, Long expectedRevision) {
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        support.validateRequest(name, connection.endpoint(), headers);
        McpApprovalService.PendingApproval approval = approvalService.request(
                userId,
                McpApprovalService.Operation.ROTATE_HEADERS,
                name,
                connection.endpoint(),
                headers,
                null,
                expectedRevision);
        support.markPending(connection, approval.taskId());
        return approval;
    }

    public McpApprovalService.PendingApproval requestEnableTool(String userId, String name, String toolName) {
        return requestEnableTool(userId, name, toolName, null);
    }

    public McpApprovalService.PendingApproval requestEnableTool(
            String userId, String name, String toolName, Long expectedRevision) {
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        if (McpManagementSupport.isBlank(toolName)) throw new IllegalArgumentException("MCP tool name is required");
        McpApprovalService.PendingApproval approval = approvalService.request(
                userId,
                McpApprovalService.Operation.ENABLE_TOOL,
                name,
                connection.endpoint(),
                Map.of(),
                toolName,
                expectedRevision);
        support.markPending(connection, approval.taskId());
        return approval;
    }

    public ApprovalExecution approve(String userId, String taskId) {
        McpApprovalService.ApprovedOperation operation = approvalService.approve(userId, taskId);
        Object result =
                switch (operation.operation()) {
                    case DISCOVER ->
                        discoverWithApproval(
                                userId,
                                operation.name(),
                                operation.endpoint(),
                                operation.headers(),
                                operation.receiptToken());
                    case CONNECT ->
                        connectNewWithApproval(
                                userId,
                                operation.name(),
                                operation.endpoint(),
                                operation.headers(),
                                operation.receiptToken());
                    case RECONNECT ->
                        connectWithApproval(
                                userId, operation.name(), operation.receiptToken(), operation.expectedRevision());
                    case ROTATE_HEADERS ->
                        rotateHeadersWithApproval(
                                userId,
                                operation.name(),
                                operation.headers(),
                                operation.receiptToken(),
                                operation.expectedRevision());
                    case ENABLE_TOOL ->
                        enableToolWithApproval(
                                userId,
                                operation.name(),
                                operation.toolName(),
                                operation.receiptToken(),
                                operation.expectedRevision());
                };
        return new ApprovalExecution(operation.taskId(), operation.operation().name(), result);
    }

    public boolean rejectApproval(String userId, String taskId) {
        var pending = approvalService.find(userId, taskId);
        if (!approvalService.reject(userId, taskId)) {
            return false;
        }
        pending.ifPresent(operation -> lifecycleService
                .findByNameAndUserId(operation.name(), userId)
                .ifPresent(connection -> clearRejectedApproval(userId, connection)));
        support.audit(userId, SecurityEventType.APPROVAL_DENIED, "task=" + taskId);
        return true;
    }

    private void clearRejectedApproval(String userId, McpConnectionDto connection) {
        String state = connection.enabled() ? STATE_DISCONNECTED : STATE_PAUSED;
        lifecycleService.updateState(connection.id(), userId, state, connection.enabled(), null, "APPROVAL_DENIED");
    }

    public List<ConnectionSnapshot> list(String userId) {
        if (McpManagementSupport.isBlank(userId)) return List.of();
        return lifecycleService.listByUserId(userId).stream()
                .map(support::snapshot)
                .toList();
    }

    /** Throwaway preview: no durable connection, policy row, vault ref, or runtime session remains. */
    public DiscoveryPreview discover(
            String userId, String name, String endpoint, Map<String, String> headers, boolean preApproved) {
        return discoverInternal(userId, name, endpoint, headers, preApproved, null);
    }

    public DiscoveryPreview discoverWithApproval(
            String userId, String name, String endpoint, Map<String, String> headers, String approvalToken) {
        return discoverInternal(userId, name, endpoint, headers, false, approvalToken);
    }

    private DiscoveryPreview discoverInternal(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            boolean preApproved,
            String approvalToken) {
        McpManagementSupport.requireUser(userId);
        McpManagementSupport.requireName(name);
        Map<String, String> safeHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        if (approvalToken == null) lifecycleService.validateHttpRegistration(endpoint, safeHeaders);
        try {
            List<McpClientService.DiscoveredTool> tools = approvalToken == null
                    ? clientService.discoverForUser(
                            name, userId, endpoint, ToolCategory.NETWORK, safeHeaders, preApproved)
                    : clientService.discoverForUser(
                            name, userId, endpoint, ToolCategory.NETWORK, safeHeaders, approvalToken);
            support.audit(userId, SecurityEventType.MCP_CONNECTION_REQUESTED, "server=" + name + " operation=discover");
            return new DiscoveryPreview(
                    name,
                    endpoint,
                    McpTransportType.HTTP_SSE.name(),
                    tools.stream()
                            .map(tool -> new ToolPreview(
                                    tool.name(),
                                    tool.description(),
                                    tool.inputSchema(),
                                    McpManagementSupport.sha256(tool.inputSchema())))
                            .toList());
        } finally {
            clientService.clearHttpConnectionState(name, userId);
        }
    }

    /** Creates a durable connection and stores every discovered tool disabled. */
    public ConnectionSnapshot connectNew(
            String userId, String name, String endpoint, Map<String, String> headers, boolean preApproved) {
        return connectNewInternal(userId, name, endpoint, headers, ToolCategory.NETWORK, preApproved, null);
    }

    public ConnectionSnapshot connectNewWithApproval(
            String userId, String name, String endpoint, Map<String, String> headers, String approvalToken) {
        return connectNewInternal(userId, name, endpoint, headers, ToolCategory.NETWORK, false, approvalToken);
    }

    private ConnectionSnapshot connectNewInternal(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            ToolCategory category,
            boolean preApproved,
            String approvalToken) {
        McpManagementSupport.requireUser(userId);
        McpManagementSupport.requireName(name);
        Map<String, String> safeHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        if (approvalToken == null) lifecycleService.validateHttpRegistration(endpoint, safeHeaders);
        List<McpClientService.DiscoveredTool> tools = approvalToken == null
                ? clientService.discoverForUser(name, userId, endpoint, category, safeHeaders, preApproved)
                : clientService.discoverForUser(name, userId, endpoint, category, safeHeaders, approvalToken);
        McpConnectionLifecycleService.PersistedConnection persisted =
                lifecycleService.persistSuccessfulHttp(name, endpoint, category, userId, safeHeaders);
        if (persisted.connection() == null) {
            clientService.clearHttpConnectionState(name, userId);
            throw new McpConnectionException("MCP persistence backend is unavailable");
        }
        clientService.configureHeaderSecretRefs(name, userId, persisted.headerSecretRefs());
        support.replacePolicies(persisted.connection().id(), userId, tools);
        lifecycleService.updateState(persisted.connection().id(), userId, STATE_CONNECTED, true, null, null);
        support.audit(userId, SecurityEventType.MCP_SERVER_CONNECTED, "server=" + name + " tools=0");
        return lifecycleService
                .findByNameAndUserId(name, userId)
                .map(support::snapshot)
                .orElseThrow();
    }

    /** Activation/reconnect of an existing durable connection. */
    public ConnectionSnapshot connect(String userId, String name, boolean preApproved) {
        return connectInternal(userId, name, preApproved, null, OutboundPurpose.MCP_CONNECT, null);
    }

    public ConnectionSnapshot connectWithApproval(String userId, String name, String approvalToken) {
        return connectWithApproval(userId, name, approvalToken, null);
    }

    public ConnectionSnapshot connectWithApproval(
            String userId, String name, String approvalToken, Long expectedRevision) {
        return connectInternal(userId, name, false, approvalToken, OutboundPurpose.MCP_CONNECT, expectedRevision);
    }

    private ConnectionSnapshot connectInternal(
            String userId,
            String name,
            boolean preApproved,
            String approvalToken,
            OutboundPurpose approvalPurpose,
            Long expectedRevision) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        Set<String> enabledTools = support.enabledToolNames(connection, userId);
        lifecycleService.updateState(
                connection.id(), userId, STATE_CONNECTING, true, connection.approvalTaskId(), null);
        support.clearRegisteredTools(userId, name);
        support.configureStoredHeaders(connection, userId);
        try {
            if (approvalToken == null) {
                clientService.connectForUserWithEnabledTools(
                        name,
                        userId,
                        connection.endpoint(),
                        ToolCategory.valueOf(connection.category()),
                        Map.of(),
                        preApproved,
                        enabledTools);
            } else {
                clientService.connectForUserWithEnabledTools(
                        name,
                        userId,
                        connection.endpoint(),
                        ToolCategory.valueOf(connection.category()),
                        Map.of(),
                        approvalToken,
                        approvalPurpose,
                        enabledTools);
            }
            lifecycleService.updateState(connection.id(), userId, STATE_CONNECTED, true, null, null);
            support.audit(
                    userId, SecurityEventType.MCP_SERVER_CONNECTED, "server=" + name + " tools=" + enabledTools.size());
            return lifecycleService
                    .findByNameAndUserId(name, userId)
                    .map(support::snapshot)
                    .orElseThrow();
        } catch (RuntimeException e) {
            lifecycleService.updateState(
                    connection.id(), userId, STATE_DEGRADED, true, null, McpManagementSupport.classify(e));
            throw e;
        }
    }

    /** Agent registration uses the same disabled policy model as every other surface. */
    public ConnectionSnapshot registerFromApprovedCheckpoint(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            String approvalToken,
            ToolCategory category) {
        return connectNewInternal(userId, name, endpoint, headers, category, false, approvalToken);
    }

    public ConnectionSnapshot enableTool(String userId, String name, String toolName, boolean preApproved) {
        return enableTool(userId, name, toolName, preApproved, null);
    }

    public ConnectionSnapshot enableTool(
            String userId, String name, String toolName, boolean preApproved, Long expectedRevision) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        support.audit(userId, SecurityEventType.MCP_TOOL_POLICY_ENABLED, "server=" + name + " tool=" + toolName);
        if (lifecycleService.setToolEnabled(connection.id(), userId, toolName, true) == 0) {
            throw new McpConnectionException("MCP tool was not discovered: " + toolName);
        }
        try {
            return connectInternal(userId, name, preApproved, null, OutboundPurpose.MCP_CONNECT, expectedRevision);
        } catch (RuntimeException e) {
            lifecycleService.setToolEnabled(connection.id(), userId, toolName, false);
            throw e;
        }
    }

    public ConnectionSnapshot enableToolWithApproval(
            String userId, String name, String toolName, String approvalToken) {
        return enableToolWithApproval(userId, name, toolName, approvalToken, null);
    }

    public ConnectionSnapshot enableToolWithApproval(
            String userId, String name, String toolName, String approvalToken, Long expectedRevision) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        support.audit(userId, SecurityEventType.MCP_TOOL_POLICY_ENABLED, "server=" + name + " tool=" + toolName);
        if (lifecycleService.setToolEnabled(connection.id(), userId, toolName, true) == 0) {
            throw new McpConnectionException("MCP tool was not discovered: " + toolName);
        }
        try {
            return connectInternal(
                    userId, name, false, approvalToken, OutboundPurpose.MCP_TOOL_ENABLE, expectedRevision);
        } catch (RuntimeException e) {
            lifecycleService.setToolEnabled(connection.id(), userId, toolName, false);
            throw e;
        }
    }

    public ConnectionSnapshot disableTool(String userId, String name, String toolName) {
        return disableTool(userId, name, toolName, null);
    }

    public ConnectionSnapshot disableTool(String userId, String name, String toolName, Long expectedRevision) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        if (lifecycleService.setToolEnabled(connection.id(), userId, toolName, false) == 0) {
            throw new McpConnectionException("MCP tool was not discovered: " + toolName);
        }
        support.deregisterTool(userId, toolName);
        return lifecycleService
                .findByNameAndUserId(name, userId)
                .map(support::snapshot)
                .orElseThrow();
    }

    public ConnectionSnapshot rotateHeaders(
            String userId, String name, Map<String, String> headers, boolean preApproved) {
        return rotateHeaders(userId, name, headers, preApproved, null);
    }

    public ConnectionSnapshot rotateHeaders(
            String userId, String name, Map<String, String> headers, boolean preApproved, Long expectedRevision) {
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        McpConnectionLifecycleService.PersistedConnection persisted = lifecycleService.persistSuccessfulHttp(
                name, connection.endpoint(), ToolCategory.valueOf(connection.category()), userId, headers);
        clientService.configureHeaderSecretRefs(name, userId, persisted.headerSecretRefs());
        return connect(userId, name, preApproved);
    }

    public ConnectionSnapshot rotateHeadersWithApproval(
            String userId, String name, Map<String, String> headers, String approvalToken) {
        return rotateHeadersWithApproval(userId, name, headers, approvalToken, null);
    }

    public ConnectionSnapshot rotateHeadersWithApproval(
            String userId, String name, Map<String, String> headers, String approvalToken, Long expectedRevision) {
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        McpConnectionLifecycleService.PersistedConnection persisted = lifecycleService.persistSuccessfulHttp(
                name, connection.endpoint(), ToolCategory.valueOf(connection.category()), userId, headers);
        clientService.configureHeaderSecretRefs(name, userId, persisted.headerSecretRefs());
        return connectWithApproval(userId, name, approvalToken);
    }

    public ConnectionSnapshot pause(String userId, String name) {
        return pause(userId, name, null);
    }

    public ConnectionSnapshot pause(String userId, String name, Long expectedRevision) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        support.clearRegisteredTools(userId, name);
        lifecycleService.updateState(connection.id(), userId, STATE_PAUSED, false, null, null);
        return lifecycleService
                .findByNameAndUserId(name, userId)
                .map(support::snapshot)
                .orElseThrow();
    }

    public ConnectionSnapshot resume(String userId, String name, boolean preApproved) {
        return resume(userId, name, preApproved, null);
    }

    public ConnectionSnapshot resume(String userId, String name, boolean preApproved, Long expectedRevision) {
        return connectInternal(userId, name, preApproved, null, OutboundPurpose.MCP_CONNECT, expectedRevision);
    }

    public ConnectionSnapshot reconnect(String userId, String name, boolean preApproved) {
        return reconnect(userId, name, preApproved, null);
    }

    public ConnectionSnapshot reconnect(String userId, String name, boolean preApproved, Long expectedRevision) {
        return connectInternal(userId, name, preApproved, null, OutboundPurpose.MCP_CONNECT, expectedRevision);
    }

    public boolean disconnect(String userId, String name) {
        return disconnectWithResult(userId, name).deregistered();
    }

    public DisconnectResult disconnectWithResult(String userId, String name) {
        return disconnectWithResult(userId, name, null);
    }

    public DisconnectResult disconnectWithResult(String userId, String name, Long expectedRevision) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        int removedTools = support.clearRegisteredTools(userId, name);
        boolean deleted = lifecycleService.deleteByNameAndUserId(name, userId);
        if (deleted) support.audit(userId, SecurityEventType.MCP_SERVER_DISCONNECTED, "server=" + name);
        return new DisconnectResult(deleted, removedTools);
    }

    public List<ToolPreview> listTools(String userId, String name) {
        McpConnectionDto connection = support.findConnection(userId, name);
        return lifecycleService.listToolPolicies(connection.id(), userId).stream()
                .map(policy -> new ToolPreview(
                        policy.toolName(), policy.description(), policy.inputSchema(), policy.schemaHash()))
                .toList();
    }

    public record ConnectionSnapshot(
            String name,
            String endpoint,
            String state,
            String transport,
            long enabledToolCount,
            long discoveredToolCount,
            long revision,
            List<McpConnectionToolPolicyDto> tools) {}

    public record DiscoveryPreview(String name, String endpoint, String transport, List<ToolPreview> tools) {}

    public record ToolPreview(String name, String description, String inputSchema, String schemaHash) {}

    public record DisconnectResult(boolean deregistered, int toolsRemoved) {}

    public record ApprovalExecution(String taskId, String operation, Object result) {}
}
