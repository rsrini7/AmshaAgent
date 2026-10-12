package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/** Filesystem implementation of durable, non-secret MCP connection metadata. */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsMcpConnectionStoreAdapter implements McpConnectionStorePort {

    private static final String DOMAIN = "mcp-connections";
    private static final String CONNECTIONS = "connections";
    private static final String HEADERS = "headers";
    private static final String CLEANUP_FAILURES = "cleanup-failures";

    @Inject
    FilesystemFileStore store;

    @Override
    public synchronized void save(McpConnectionDto connection) {
        store.write(List.of(DOMAIN, CONNECTIONS, connection.id() + ".json"), connection);
    }

    @Override
    public Optional<McpConnectionDto> findByIdAndUserId(String id, String userId) {
        return store.readOptional(List.of(DOMAIN, CONNECTIONS, id + ".json"), McpConnectionDto.class)
                .filter(connection -> userId.equals(connection.userId()));
    }

    @Override
    public Optional<McpConnectionDto> findByNameAndUserId(String name, String userId) {
        return listByUserId(userId).stream()
                .filter(connection -> name.equals(connection.name()))
                .findFirst();
    }

    @Override
    public List<McpConnectionDto> listByUserId(String userId) {
        return store.list(List.of(DOMAIN, CONNECTIONS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, CONNECTIONS, file), McpConnectionDto.class))
                .flatMap(Optional::stream)
                .filter(connection -> userId.equals(connection.userId()))
                .toList();
    }

    @Override
    public List<McpConnectionDto> listEnabled() {
        return store.list(List.of(DOMAIN, CONNECTIONS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, CONNECTIONS, file), McpConnectionDto.class))
                .flatMap(Optional::stream)
                .filter(McpConnectionDto::enabled)
                .toList();
    }

    @Override
    public List<McpConnectionDto> listPendingApprovals() {
        return store.list(List.of(DOMAIN, CONNECTIONS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, CONNECTIONS, file), McpConnectionDto.class))
                .flatMap(Optional::stream)
                .filter(connection -> "PENDING_APPROVAL".equals(connection.state()))
                .toList();
    }

    @Override
    public synchronized boolean updateStateIfRevision(McpConnectionDto connection, long expectedRevision) {
        Optional<McpConnectionDto> current = findByIdAndUserId(connection.id(), connection.userId());
        if (current.isEmpty() || current.get().revision() != expectedRevision) return false;
        save(connection);
        return true;
    }

    @Override
    public List<McpConnectionToolPolicyDto> listToolPolicies(String connectionId, String userId) {
        if (connectionId == null || connectionId.isBlank() || userId == null || userId.isBlank()) {
            return List.of();
        }
        return store.list(List.of(DOMAIN, "tool-policies", connectionId), ".json").stream()
                .map(file -> store.readOptional(
                        List.of(DOMAIN, "tool-policies", connectionId, file), McpConnectionToolPolicyDto.class))
                .flatMap(Optional::stream)
                .filter(policy -> userId.equals(policy.userId()))
                .toList();
    }

    @Override
    public void replaceToolPolicies(String connectionId, String userId, List<McpConnectionToolPolicyDto> policies) {
        if (connectionId == null || connectionId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("connectionId/userId required");
        }
        if (findByIdAndUserId(connectionId, userId).isEmpty()) {
            throw new IllegalArgumentException("MCP connection not found");
        }
        List<McpConnectionToolPolicyDto> normalized = normalizeAndValidatePolicies(connectionId, userId, policies);
        store.deleteDirectory(List.of(DOMAIN, "tool-policies", connectionId));
        normalized.forEach(policy ->
                store.write(List.of(DOMAIN, "tool-policies", connectionId, policyFileName(policy.toolName())), policy));
    }

    @Override
    public int setToolEnabled(String connectionId, String userId, String toolName, boolean enabled) {
        if (connectionId == null
                || connectionId.isBlank()
                || userId == null
                || userId.isBlank()
                || toolName == null
                || toolName.isBlank()) {
            return 0;
        }
        Optional<McpConnectionToolPolicyDto> existing = listToolPolicies(connectionId, userId).stream()
                .filter(policy -> toolName.equals(policy.toolName()))
                .findFirst();
        if (existing.isEmpty() || !existing.get().available()) return 0;
        McpConnectionToolPolicyDto policy = existing.get();
        store.write(
                List.of(DOMAIN, "tool-policies", connectionId, policyFileName(toolName)),
                new McpConnectionToolPolicyDto(
                        policy.id(),
                        policy.connectionId(),
                        policy.userId(),
                        policy.toolName(),
                        policy.available(),
                        enabled,
                        policy.description(),
                        policy.inputSchema(),
                        policy.schemaHash(),
                        policy.discoveredAt(),
                        policy.revision() + 1,
                        Instant.now()));
        return 1;
    }

    @Override
    public int deleteByIdAndUserId(String id, String userId) {
        if (findByIdAndUserId(id, userId).isEmpty()) return 0;
        store.deleteDirectory(List.of(DOMAIN, HEADERS, id));
        store.delete(List.of(DOMAIN, CONNECTIONS, id + ".json"));
        return 1;
    }

    @Override
    public void replaceHeaderSecretRefs(
            String connectionId, String userId, List<McpConnectionHeaderSecretRefDto> references) {
        if (findByIdAndUserId(connectionId, userId).isEmpty())
            throw new IllegalArgumentException("MCP connection not found");
        List<McpConnectionHeaderSecretRefDto> normalized = normalizeAndValidate(connectionId, userId, references);
        store.deleteDirectory(List.of(DOMAIN, HEADERS, connectionId));
        for (McpConnectionHeaderSecretRefDto reference : normalized) {
            store.write(List.of(DOMAIN, HEADERS, connectionId, reference.headerName() + ".json"), reference);
        }
    }

    @Override
    public List<McpConnectionHeaderSecretRefDto> listHeaderSecretRefs(String connectionId, String userId) {
        if (findByIdAndUserId(connectionId, userId).isEmpty()) return List.of();
        return store.list(List.of(DOMAIN, HEADERS, connectionId), ".json").stream()
                .map(file -> store.readOptional(
                        List.of(DOMAIN, HEADERS, connectionId, file), McpConnectionHeaderSecretRefDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void saveCleanupFailure(McpConnectionCleanupFailureDto failure) {
        store.write(List.of(DOMAIN, CLEANUP_FAILURES, failure.id() + ".json"), failure);
    }

    @Override
    public List<McpConnectionCleanupFailureDto> listCleanupFailures() {
        return store.list(List.of(DOMAIN, CLEANUP_FAILURES), ".json").stream()
                .map(file -> store.readOptional(
                        List.of(DOMAIN, CLEANUP_FAILURES, file), McpConnectionCleanupFailureDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public int deleteCleanupFailure(String id, String userId) {
        if (id == null || id.isBlank() || userId == null || userId.isBlank()) return 0;
        Optional<McpConnectionCleanupFailureDto> failure = store.readOptional(
                List.of(DOMAIN, CLEANUP_FAILURES, id + ".json"), McpConnectionCleanupFailureDto.class);
        if (failure.isEmpty() || !userId.equals(failure.get().userId())) return 0;
        boolean deleted = store.delete(List.of(DOMAIN, CLEANUP_FAILURES, id + ".json"));
        return deleted ? 1 : 0;
    }

    private static List<McpConnectionHeaderSecretRefDto> normalizeAndValidate(
            String connectionId, String userId, List<McpConnectionHeaderSecretRefDto> references) {
        if (references == null || references.isEmpty()) return List.of();
        Set<String> names = new HashSet<>();
        return references.stream()
                .map(reference -> normalizeReference(connectionId, userId, reference))
                .peek(reference -> {
                    if (!names.add(reference.headerName())) {
                        throw new IllegalArgumentException("Duplicate MCP header name: " + reference.headerName());
                    }
                })
                .toList();
    }

    private static List<McpConnectionToolPolicyDto> normalizeAndValidatePolicies(
            String connectionId, String userId, List<McpConnectionToolPolicyDto> policies) {
        if (policies == null || policies.isEmpty()) return List.of();
        Set<String> names = new HashSet<>();
        return policies.stream()
                .map(policy -> {
                    if (!connectionId.equals(policy.connectionId()) || !userId.equals(policy.userId())) {
                        throw new IllegalArgumentException("MCP tool policy ownership mismatch");
                    }
                    if (!names.add(policy.toolName())) {
                        throw new IllegalArgumentException("Duplicate MCP tool policy: " + policy.toolName());
                    }
                    return policy;
                })
                .toList();
    }

    private static String policyFileName(String toolName) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(toolName.getBytes(StandardCharsets.UTF_8))
                + ".json";
    }

    private static McpConnectionHeaderSecretRefDto normalizeReference(
            String connectionId, String userId, McpConnectionHeaderSecretRefDto reference) {
        if (!connectionId.equals(reference.connectionId()) || !userId.equals(reference.userId())) {
            throw new IllegalArgumentException("Header reference ownership mismatch");
        }
        return new McpConnectionHeaderSecretRefDto(
                reference.id(),
                reference.connectionId(),
                reference.userId(),
                reference.headerName().toLowerCase(Locale.ROOT),
                reference.vaultKeyRef(),
                reference.createdAt(),
                reference.updatedAt());
    }
}
