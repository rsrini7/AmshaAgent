package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
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
    private final McpManagementConnectionSetup connectionSetup;
    private final McpManagementConnectionOperations connectionOperations;

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
        this.connectionSetup = new McpManagementConnectionSetup(clientService, lifecycleService, support);
        this.connectionOperations = new McpManagementConnectionOperations(clientService, lifecycleService, support);
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

    public McpApprovalService.PendingApproval requestReconfigure(
            String userId, String name, String endpoint, Map<String, String> headers, Long expectedRevision) {
        McpConnectionDto connection = support.findConnection(userId, name);
        McpManagementSupport.checkRevision(connection, expectedRevision);
        String proposedEndpoint = McpManagementSupport.isBlank(endpoint) ? connection.endpoint() : endpoint;
        Map<String, String> proposedHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        support.validateRequest(name, proposedEndpoint, proposedHeaders);
        McpApprovalService.PendingApproval approval = approvalService.request(
                userId,
                McpApprovalService.Operation.RECONFIGURE,
                name,
                proposedEndpoint,
                proposedHeaders,
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
                        connectionSetup.discoverWithApproval(
                                userId,
                                operation.name(),
                                operation.endpoint(),
                                operation.headers(),
                                operation.receiptToken());
                    case CONNECT ->
                        connectionSetup.connectNewWithApproval(
                                userId,
                                operation.name(),
                                operation.endpoint(),
                                operation.headers(),
                                operation.receiptToken());
                    case RECONNECT ->
                        connectWithApproval(
                                userId,
                                operation.name(),
                                operation.receiptToken(),
                                operation.expectedRevision(),
                                operation.taskId());
                    case RECONFIGURE ->
                        connectionSetup.reconfigureWithApproval(
                                userId,
                                operation.name(),
                                operation.endpoint(),
                                operation.headers(),
                                operation.receiptToken(),
                                operation.expectedRevision(),
                                operation.taskId());
                    case ROTATE_HEADERS ->
                        rotateHeadersWithApproval(
                                userId,
                                operation.name(),
                                operation.headers(),
                                operation.receiptToken(),
                                operation.expectedRevision(),
                                operation.taskId());
                    case ENABLE_TOOL ->
                        enableToolWithApproval(
                                userId,
                                operation.name(),
                                operation.toolName(),
                                operation.receiptToken(),
                                operation.expectedRevision(),
                                operation.taskId());
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
        return connectionSetup.discover(userId, name, endpoint, headers, preApproved);
    }

    public DiscoveryPreview discoverWithApproval(
            String userId, String name, String endpoint, Map<String, String> headers, String approvalToken) {
        return connectionSetup.discoverWithApproval(userId, name, endpoint, headers, approvalToken);
    }

    /** Creates a durable connection and stores every discovered tool disabled. */
    public ConnectionSnapshot connectNew(
            String userId, String name, String endpoint, Map<String, String> headers, boolean preApproved) {
        return connectionSetup.connectNew(userId, name, endpoint, headers, preApproved);
    }

    public ConnectionSnapshot connectNewWithApproval(
            String userId, String name, String endpoint, Map<String, String> headers, String approvalToken) {
        return connectionSetup.connectNewWithApproval(userId, name, endpoint, headers, approvalToken);
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
        return connectWithApproval(userId, name, approvalToken, expectedRevision, null);
    }

    public ConnectionSnapshot reconfigureWithApproval(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            String approvalToken,
            Long expectedRevision) {
        return connectionSetup.reconfigureWithApproval(
                userId, name, endpoint, headers, approvalToken, expectedRevision, null);
    }

    private ConnectionSnapshot connectWithApproval(
            String userId, String name, String approvalToken, Long expectedRevision, String approvalTaskId) {
        return connectInternal(
                userId, name, false, approvalToken, OutboundPurpose.MCP_CONNECT, expectedRevision, approvalTaskId);
    }

    private ConnectionSnapshot connectInternal(
            String userId,
            String name,
            boolean preApproved,
            String approvalToken,
            OutboundPurpose approvalPurpose,
            Long expectedRevision) {
        return connectInternal(userId, name, preApproved, approvalToken, approvalPurpose, expectedRevision, null);
    }

    private ConnectionSnapshot connectInternal(
            String userId,
            String name,
            boolean preApproved,
            String approvalToken,
            OutboundPurpose approvalPurpose,
            Long expectedRevision,
            String approvalTaskId) {
        return connectionOperations.connect(
                userId, name, preApproved, approvalToken, approvalPurpose, expectedRevision, approvalTaskId);
    }

    /** Agent registration uses the same disabled policy model as every other surface. */
    public ConnectionSnapshot registerFromApprovedCheckpoint(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            String approvalToken,
            ToolCategory category) {
        return connectionSetup.registerFromApprovedCheckpoint(userId, name, endpoint, headers, approvalToken, category);
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
        return enableToolWithApproval(userId, name, toolName, approvalToken, expectedRevision, null);
    }

    private ConnectionSnapshot enableToolWithApproval(
            String userId,
            String name,
            String toolName,
            String approvalToken,
            Long expectedRevision,
            String approvalTaskId) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        long currentRevision = support.checkApprovalRevision(connection, expectedRevision, approvalTaskId);
        support.audit(userId, SecurityEventType.MCP_TOOL_POLICY_ENABLED, "server=" + name + " tool=" + toolName);
        if (lifecycleService.setToolEnabled(connection.id(), userId, toolName, true) == 0) {
            throw new McpConnectionException("MCP tool was not discovered: " + toolName);
        }
        try {
            return connectInternal(
                    userId, name, false, approvalToken, OutboundPurpose.MCP_TOOL_ENABLE, currentRevision);
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
        return rotateHeadersWithApproval(userId, name, headers, approvalToken, expectedRevision, null);
    }

    private ConnectionSnapshot rotateHeadersWithApproval(
            String userId,
            String name,
            Map<String, String> headers,
            String approvalToken,
            Long expectedRevision,
            String approvalTaskId) {
        McpConnectionDto connection = support.findConnection(userId, name);
        long currentRevision = support.checkApprovalRevision(connection, expectedRevision, approvalTaskId);
        McpConnectionLifecycleService.PersistedConnection persisted = lifecycleService.persistSuccessfulHttp(
                name, connection.endpoint(), ToolCategory.valueOf(connection.category()), userId, headers);
        clientService.configureHeaderSecretRefs(name, userId, persisted.headerSecretRefs());
        return connectWithApproval(userId, name, approvalToken, currentRevision);
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
                .filter(McpConnectionToolPolicyDto::available)
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
