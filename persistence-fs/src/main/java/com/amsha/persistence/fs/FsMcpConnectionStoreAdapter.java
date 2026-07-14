package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/** Filesystem implementation of durable, non-secret MCP connection metadata. */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsMcpConnectionStoreAdapter implements McpConnectionStorePort {

    private static final String DOMAIN = "mcp-connections";
    private static final String CONNECTIONS = "connections";
    private static final String HEADERS = "headers";
    private static final String CLEANUP_FAILURES = "cleanup-failures";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(McpConnectionDto connection) {
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
