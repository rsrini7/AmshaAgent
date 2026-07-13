package com.amsha.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(McpConnectionDto.class)
@RegisterConstructorMapper(McpConnectionHeaderSecretRefDto.class)
@RegisterConstructorMapper(McpConnectionCleanupFailureDto.class)
public interface McpConnectionJdbiDao {

    String CONNECTION_COLUMNS =
            "id, user_id AS \"userId\", name, transport, endpoint, category, enabled, created_at AS \"createdAt\", updated_at AS \"updatedAt\"";

    String HEADER_COLUMNS =
            "id, connection_id AS \"connectionId\", user_id AS \"userId\", normalized_header_name AS \"headerName\", vault_key_ref AS \"vaultKeyRef\", created_at AS \"createdAt\", updated_at AS \"updatedAt\"";

    @SqlUpdate(
            "INSERT INTO mcp_connection (id, user_id, name, transport, endpoint, category, enabled, created_at, updated_at) "
                    + "VALUES (:id, :userId, :name, :transport, :endpoint, :category, :enabled, :createdAt, :updatedAt) "
                    + "ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, name = EXCLUDED.name, transport = EXCLUDED.transport, endpoint = EXCLUDED.endpoint, category = EXCLUDED.category, enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at")
    void saveConnection(@BindMethods McpConnectionDto connection);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE id = :id AND user_id = :userId")
    Optional<McpConnectionDto> findByIdAndUserId(@Bind("id") String id, @Bind("userId") String userId);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE name = :name AND user_id = :userId")
    Optional<McpConnectionDto> findByNameAndUserId(@Bind("name") String name, @Bind("userId") String userId);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE user_id = :userId ORDER BY name ASC")
    List<McpConnectionDto> listByUserId(@Bind("userId") String userId);

    @SqlQuery(
            "SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE enabled = TRUE ORDER BY user_id ASC, name ASC")
    List<McpConnectionDto> listEnabled();

    @SqlUpdate("DELETE FROM mcp_connection_header_secret_ref WHERE connection_id = :connectionId AND user_id = :userId")
    int deleteHeaderRefs(@Bind("connectionId") String connectionId, @Bind("userId") String userId);

    @SqlUpdate("DELETE FROM mcp_connection WHERE id = :id AND user_id = :userId")
    int deleteConnection(@Bind("id") String id, @Bind("userId") String userId);

    @SqlUpdate(
            "INSERT INTO mcp_connection_header_secret_ref (id, connection_id, user_id, normalized_header_name, vault_key_ref, created_at, updated_at) "
                    + "VALUES (:id, :connectionId, :userId, :headerName, :vaultKeyRef, :createdAt, :updatedAt) "
                    + "ON CONFLICT (id) DO UPDATE SET connection_id = EXCLUDED.connection_id, user_id = EXCLUDED.user_id, normalized_header_name = EXCLUDED.normalized_header_name, vault_key_ref = EXCLUDED.vault_key_ref, updated_at = EXCLUDED.updated_at")
    void saveHeaderRef(@BindMethods McpConnectionHeaderSecretRefDto reference);

    @SqlQuery(
            "SELECT " + HEADER_COLUMNS
                    + " FROM mcp_connection_header_secret_ref WHERE connection_id = :connectionId AND user_id = :userId ORDER BY normalized_header_name ASC")
    List<McpConnectionHeaderSecretRefDto> listHeaderRefs(
            @Bind("connectionId") String connectionId, @Bind("userId") String userId);

    @SqlUpdate(
            "INSERT INTO mcp_connection_cleanup_failure (id, connection_id, user_id, normalized_header_name, vault_key_ref, reason, attempts, created_at, updated_at) "
                    + "VALUES (:id, :connectionId, :userId, :headerName, :vaultKeyRef, :reason, :attempts, :createdAt, :updatedAt) "
                    + "ON CONFLICT(id) DO UPDATE SET connection_id = excluded.connection_id, user_id = excluded.user_id, "
                    + "normalized_header_name = excluded.normalized_header_name, vault_key_ref = excluded.vault_key_ref, "
                    + "reason = excluded.reason, attempts = excluded.attempts, updated_at = excluded.updated_at")
    void saveCleanupFailure(@BindMethods McpConnectionCleanupFailureDto failure);

    @SqlQuery("SELECT id, connection_id AS connectionId, user_id AS userId, normalized_header_name AS headerName, "
            + "vault_key_ref AS vaultKeyRef, reason, attempts, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM mcp_connection_cleanup_failure ORDER BY created_at ASC")
    List<McpConnectionCleanupFailureDto> listCleanupFailures();

    @SqlUpdate("DELETE FROM mcp_connection_cleanup_failure WHERE id = :id AND user_id = :userId")
    int deleteCleanupFailure(@Bind("id") String id, @Bind("userId") String userId);
}
