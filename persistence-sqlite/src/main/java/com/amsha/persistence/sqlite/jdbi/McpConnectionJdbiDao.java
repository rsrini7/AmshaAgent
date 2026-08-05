package com.amsha.persistence.sqlite.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(McpConnectionDto.class)
@RegisterConstructorMapper(McpConnectionHeaderSecretRefDto.class)
@RegisterConstructorMapper(McpConnectionCleanupFailureDto.class)
@RegisterConstructorMapper(McpConnectionToolPolicyDto.class)
public interface McpConnectionJdbiDao {
    String CONNECTION_COLUMNS = "id, user_id AS \"userId\", name, transport, endpoint, category, enabled, state, "
            + "approval_task_id AS \"approvalTaskId\", revision, "
            + "last_error_code AS \"lastErrorCode\", created_at AS \"createdAt\", "
            + "updated_at AS \"updatedAt\"";
    String HEADER_COLUMNS = "id, connection_id AS \"connectionId\", user_id AS \"userId\", "
            + "normalized_header_name AS \"headerName\", vault_key_ref AS \"vaultKeyRef\", "
            + "created_at AS \"createdAt\", updated_at AS \"updatedAt\"";
    String POLICY_COLUMNS = "id, connection_id AS \"connectionId\", user_id AS \"userId\", tool_name AS \"toolName\", "
            + "available, enabled, description, input_schema AS \"inputSchema\", "
            + "schema_hash AS \"schemaHash\", discovered_at AS \"discoveredAt\", revision, "
            + "updated_at AS \"updatedAt\"";

    @SqlUpdate("INSERT INTO mcp_connection (id, user_id, name, transport, endpoint, category, enabled, "
            + "state, approval_task_id, revision, last_error_code, created_at, updated_at) "
            + "VALUES (:id, :userId, :name, :transport, :endpoint, :category, :enabled, "
            + ":state, :approvalTaskId, :revision, :lastErrorCode, :createdAt, :updatedAt) "
            + "ON CONFLICT(id) DO UPDATE SET user_id = excluded.user_id, name = excluded.name, "
            + "transport = excluded.transport, endpoint = excluded.endpoint, "
            + "category = excluded.category, enabled = excluded.enabled, state = excluded.state, "
            + "approval_task_id = excluded.approval_task_id, revision = excluded.revision, "
            + "last_error_code = excluded.last_error_code, updated_at = excluded.updated_at")
    void saveConnection(@BindMethods McpConnectionDto connection);

    @SqlUpdate("UPDATE mcp_connection SET enabled = :enabled, state = :state, "
            + "approval_task_id = :approvalTaskId, revision = :revision, "
            + "last_error_code = :lastErrorCode, updated_at = :updatedAt "
            + "WHERE id = :id AND user_id = :userId AND revision = :expectedRevision")
    int updateStateIfRevision(
            @BindMethods McpConnectionDto connection, @Bind("expectedRevision") long expectedRevision);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE id = :id AND user_id = :userId")
    Optional<McpConnectionDto> findByIdAndUserId(@Bind("id") String id, @Bind("userId") String userId);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE name = :name AND user_id = :userId")
    Optional<McpConnectionDto> findByNameAndUserId(@Bind("name") String name, @Bind("userId") String userId);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE user_id = :userId ORDER BY name ASC")
    List<McpConnectionDto> listByUserId(@Bind("userId") String userId);

    @SqlQuery(
            "SELECT " + CONNECTION_COLUMNS + " FROM mcp_connection WHERE enabled = TRUE ORDER BY user_id ASC, name ASC")
    List<McpConnectionDto> listEnabled();

    @SqlQuery("SELECT " + CONNECTION_COLUMNS
            + " FROM mcp_connection WHERE state = 'PENDING_APPROVAL' ORDER BY user_id ASC, name ASC")
    List<McpConnectionDto> listPendingApprovals();

    @SqlUpdate("DELETE FROM mcp_connection_header_secret_ref WHERE connection_id = :id AND user_id = :userId")
    void deleteHeaderRefs(@Bind("id") String id, @Bind("userId") String userId);

    @SqlUpdate("DELETE FROM mcp_connection_tool_policy WHERE connection_id = :connectionId AND user_id = :userId")
    void deleteToolPolicies(@Bind("connectionId") String connectionId, @Bind("userId") String userId);

    @SqlUpdate("DELETE FROM mcp_connection WHERE id = :id AND user_id = :userId")
    int deleteConnection(@Bind("id") String id, @Bind("userId") String userId);

    @SqlUpdate("INSERT INTO mcp_connection_header_secret_ref "
            + "(id, connection_id, user_id, normalized_header_name, vault_key_ref, created_at, updated_at) "
            + "VALUES (:id, :connectionId, :userId, :headerName, :vaultKeyRef, :createdAt, :updatedAt) "
            + "ON CONFLICT(id) DO UPDATE SET connection_id = excluded.connection_id, "
            + "user_id = excluded.user_id, normalized_header_name = excluded.normalized_header_name, "
            + "vault_key_ref = excluded.vault_key_ref, updated_at = excluded.updated_at")
    void saveHeaderRef(@BindMethods McpConnectionHeaderSecretRefDto reference);

    @SqlQuery("SELECT "
            + HEADER_COLUMNS
            + " FROM mcp_connection_header_secret_ref WHERE connection_id = :connectionId "
            + "AND user_id = :userId ORDER BY normalized_header_name ASC")
    List<McpConnectionHeaderSecretRefDto> listHeaderRefs(
            @Bind("connectionId") String connectionId, @Bind("userId") String userId);

    @SqlUpdate("INSERT INTO mcp_connection_tool_policy "
            + "(id, connection_id, user_id, tool_name, available, enabled, description, input_schema, schema_hash, "
            + "discovered_at, revision, updated_at) "
            + "VALUES (:id, :connectionId, :userId, :toolName, :available, :enabled, :description, :inputSchema, "
            + ":schemaHash, :discoveredAt, :revision, :updatedAt) "
            + "ON CONFLICT(connection_id, user_id, tool_name) DO UPDATE SET available = excluded.available, "
            + "enabled = excluded.enabled, "
            + "description = excluded.description, input_schema = excluded.input_schema, "
            + "schema_hash = excluded.schema_hash, discovered_at = excluded.discovered_at, "
            + "revision = excluded.revision, updated_at = excluded.updated_at")
    void saveToolPolicy(@BindMethods McpConnectionToolPolicyDto policy);

    @SqlQuery("SELECT "
            + POLICY_COLUMNS
            + " FROM mcp_connection_tool_policy WHERE connection_id = :connectionId "
            + "AND user_id = :userId ORDER BY tool_name ASC")
    List<McpConnectionToolPolicyDto> listToolPolicies(
            @Bind("connectionId") String connectionId, @Bind("userId") String userId);

    @SqlUpdate("UPDATE mcp_connection_tool_policy SET enabled = :enabled, revision = revision + 1, "
            + "updated_at = :updatedAt WHERE connection_id = :connectionId AND user_id = :userId "
            + "AND tool_name = :toolName AND available = TRUE")
    int setToolEnabled(
            @Bind("connectionId") String connectionId,
            @Bind("userId") String userId,
            @Bind("toolName") String toolName,
            @Bind("enabled") boolean enabled,
            @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("INSERT INTO mcp_connection_cleanup_failure "
            + "(id, connection_id, user_id, normalized_header_name, vault_key_ref, reason, attempts, created_at, updated_at) "
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
