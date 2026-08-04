package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.http.ApprovalReceiptIssuer;

/**
 * User-scoped approval queue for MCP operations initiated outside an agent
 * checkpoint, such as REST, the MCP panel, or a channel reflex.
 *
 * <p>Only operation metadata and transient headers are retained until the
 * short-lived approval is resolved. Credentials are never included in the
 * approval response, audit detail, or chat output.</p>
 */
@ApplicationScoped
public class McpApprovalService {
    private static final Duration TTL = Duration.ofMinutes(10);

    private final ApprovalReceiptIssuer receiptIssuer;
    private final Map<String, PendingOperation> pending = new ConcurrentHashMap<>();

    @Inject
    public McpApprovalService(ApprovalReceiptIssuer receiptIssuer) {
        this.receiptIssuer = receiptIssuer;
    }

    public PendingApproval request(
            String userId,
            Operation operation,
            String name,
            String endpoint,
            Map<String, String> headers,
            String toolName) {
        return request(userId, operation, name, endpoint, headers, toolName, null, false);
    }

    public PendingApproval request(
            String userId,
            Operation operation,
            String name,
            String endpoint,
            Map<String, String> headers,
            String toolName,
            Long expectedRevision) {
        return request(userId, operation, name, endpoint, headers, toolName, expectedRevision, false);
    }

    public PendingApproval request(
            String userId,
            Operation operation,
            String name,
            String endpoint,
            Map<String, String> headers,
            String toolName,
            Long expectedRevision,
            boolean clearCredentials) {
        requireUser(userId);
        if (operation == null || isBlank(name) || isBlank(endpoint)) {
            throw new IllegalArgumentException("MCP approval request is incomplete");
        }
        String taskId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(TTL);
        pending.put(
                taskId,
                new PendingOperation(
                        taskId,
                        userId,
                        operation,
                        name,
                        endpoint,
                        headers == null ? Map.of() : Map.copyOf(headers),
                        toolName,
                        expiresAt,
                        expectedRevision,
                        clearCredentials));
        return new PendingApproval(taskId, operation.name(), name, endpoint, toolName, expiresAt, expectedRevision);
    }

    public ApprovedOperation approve(String userId, String taskId) {
        requireUser(userId);
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("MCP approval task is required");
        }
        PendingOperation operation = pending.get(taskId);
        if (operation == null || !userId.equals(operation.userId())) {
            throw new IllegalArgumentException("MCP approval task is unknown or belongs to another user");
        }
        if (operation.expiresAt().isBefore(Instant.now()) || !pending.remove(taskId, operation)) {
            throw new IllegalArgumentException("MCP approval task is unknown or expired");
        }
        String targetHost = URI.create(operation.endpoint()).getHost();
        if (isBlank(targetHost)) {
            throw new IllegalArgumentException("MCP approval endpoint host is invalid");
        }
        String receipt = receiptIssuer.issueForExplicitApproval(
                userId, operation.taskId(), operation.operation().purpose(), targetHost);
        return new ApprovedOperation(
                operation.taskId(),
                operation.operation(),
                operation.name(),
                operation.endpoint(),
                operation.headers(),
                operation.toolName(),
                receipt,
                operation.expectedRevision(),
                operation.clearCredentials());
    }

    public boolean reject(String userId, String taskId) {
        requireUser(userId);
        PendingOperation operation = pending.get(taskId);
        if (operation == null || !userId.equals(operation.userId())) {
            return false;
        }
        return pending.remove(taskId, operation);
    }

    public Optional<PendingApproval> find(String userId, String taskId) {
        requireUser(userId);
        PendingOperation operation = pending.get(taskId);
        if (operation == null
                || !userId.equals(operation.userId())
                || operation.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(operation.publicView());
    }

    private static void requireUser(String userId) {
        if (isBlank(userId) || "unknown".equals(userId)) {
            throw new IllegalArgumentException("MCP user is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Operation {
        DISCOVER(OutboundPurpose.MCP_CONNECT),
        CONNECT(OutboundPurpose.MCP_CONNECT),
        RECONNECT(OutboundPurpose.MCP_CONNECT),
        RECONFIGURE(OutboundPurpose.MCP_CONNECT),
        ROTATE_HEADERS(OutboundPurpose.MCP_CONNECT),
        ENABLE_TOOL(OutboundPurpose.MCP_TOOL_ENABLE);

        private final OutboundPurpose purpose;

        Operation(OutboundPurpose purpose) {
            this.purpose = purpose;
        }

        public OutboundPurpose purpose() {
            return purpose;
        }
    }

    private record PendingOperation(
            String taskId,
            String userId,
            Operation operation,
            String name,
            String endpoint,
            Map<String, String> headers,
            String toolName,
            Instant expiresAt,
            Long expectedRevision,
            boolean clearCredentials) {
        PendingApproval publicView() {
            return new PendingApproval(taskId, operation.name(), name, endpoint, toolName, expiresAt, expectedRevision);
        }
    }

    public record PendingApproval(
            String taskId,
            String operation,
            String name,
            String endpoint,
            String toolName,
            Instant expiresAt,
            Long expectedRevision) {
        public PendingApproval(
                String taskId, String operation, String name, String endpoint, String toolName, Instant expiresAt) {
            this(taskId, operation, name, endpoint, toolName, expiresAt, null);
        }
    }

    public record ApprovedOperation(
            String taskId,
            Operation operation,
            String name,
            String endpoint,
            Map<String, String> headers,
            String toolName,
            String receiptToken,
            Long expectedRevision,
            boolean clearCredentials) {}
}
