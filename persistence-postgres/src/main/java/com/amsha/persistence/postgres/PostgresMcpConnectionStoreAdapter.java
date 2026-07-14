package com.amsha.persistence.postgres;

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
import com.amsha.persistence.postgres.jdbi.McpConnectionJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresMcpConnectionStoreAdapter implements McpConnectionStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresMcpConnectionStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresMcpConnectionStoreAdapter() {
        this(null);
    }

    @Override
    public void save(McpConnectionDto connection) {
        jdbi.useExtension(McpConnectionJdbiDao.class, dao -> dao.saveConnection(connection));
    }

    @Override
    public Optional<McpConnectionDto> findByIdAndUserId(String id, String userId) {
        if (isBlank(id) || isBlank(userId)) return Optional.empty();
        return jdbi.withExtension(McpConnectionJdbiDao.class, dao -> dao.findByIdAndUserId(id, userId));
    }

    @Override
    public Optional<McpConnectionDto> findByNameAndUserId(String name, String userId) {
        if (isBlank(name) || isBlank(userId)) return Optional.empty();
        return jdbi.withExtension(McpConnectionJdbiDao.class, dao -> dao.findByNameAndUserId(name, userId));
    }

    @Override
    public List<McpConnectionDto> listByUserId(String userId) {
        if (isBlank(userId)) return List.of();
        return jdbi.withExtension(McpConnectionJdbiDao.class, dao -> dao.listByUserId(userId));
    }

    @Override
    public List<McpConnectionDto> listEnabled() {
        return jdbi.withExtension(McpConnectionJdbiDao.class, McpConnectionJdbiDao::listEnabled);
    }

    @Override
    public int deleteByIdAndUserId(String id, String userId) {
        if (isBlank(id) || isBlank(userId)) return 0;
        return jdbi.inTransaction(handle -> {
            McpConnectionJdbiDao dao = handle.attach(McpConnectionJdbiDao.class);
            dao.deleteHeaderRefs(id, userId);
            return dao.deleteConnection(id, userId);
        });
    }

    @Override
    public void replaceHeaderSecretRefs(
            String connectionId, String userId, List<McpConnectionHeaderSecretRefDto> references) {
        if (isBlank(connectionId) || isBlank(userId))
            throw new IllegalArgumentException("connectionId/userId required");
        List<McpConnectionHeaderSecretRefDto> normalized = normalizeAndValidate(connectionId, userId, references);
        jdbi.useTransaction(handle -> {
            McpConnectionJdbiDao dao = handle.attach(McpConnectionJdbiDao.class);
            if (dao.findByIdAndUserId(connectionId, userId).isEmpty()) {
                throw new IllegalArgumentException("MCP connection not found");
            }
            dao.deleteHeaderRefs(connectionId, userId);
            for (McpConnectionHeaderSecretRefDto reference : normalized) {
                dao.saveHeaderRef(reference);
            }
        });
    }

    @Override
    public List<McpConnectionHeaderSecretRefDto> listHeaderSecretRefs(String connectionId, String userId) {
        if (isBlank(connectionId) || isBlank(userId)) return List.of();
        if (findByIdAndUserId(connectionId, userId).isEmpty()) return List.of();
        return jdbi.withExtension(McpConnectionJdbiDao.class, dao -> dao.listHeaderRefs(connectionId, userId));
    }

    @Override
    public void saveCleanupFailure(McpConnectionCleanupFailureDto failure) {
        jdbi.useExtension(McpConnectionJdbiDao.class, dao -> dao.saveCleanupFailure(failure));
    }

    @Override
    public List<McpConnectionCleanupFailureDto> listCleanupFailures() {
        return jdbi.withExtension(McpConnectionJdbiDao.class, McpConnectionJdbiDao::listCleanupFailures);
    }

    @Override
    public int deleteCleanupFailure(String id, String userId) {
        if (isBlank(id) || isBlank(userId)) return 0;
        return jdbi.withExtension(McpConnectionJdbiDao.class, dao -> dao.deleteCleanupFailure(id, userId));
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
