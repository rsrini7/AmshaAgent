package com.amsha.persistence.sqlite.jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(LlmConnectionDto.class)
@RegisterConstructorMapper(LlmConnectionSecretRefDto.class)
@RegisterConstructorMapper(ModelProfileDto.class)
@RegisterConstructorMapper(WorkspaceConnectionGrantDto.class)
public interface LlmProfileJdbiDao {

    String CONNECTION_COLUMNS =
            "id, scope_type AS \"scopeType\", scope_id AS \"scopeId\", created_by_user_id AS \"createdByUserId\", "
                    + "display_name AS \"displayName\", provider_adapter_id AS \"providerAdapterId\", "
                    + "endpoint_profile_id AS \"endpointProfileId\", custom_base_url AS \"customBaseUrl\", "
                    + "auth_scheme AS \"authScheme\", network_profile_json AS \"networkProfileJson\", "
                    + "state, revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\"";

    String CONNECTION_INSERT_COLUMNS =
            "id, scope_type, scope_id, created_by_user_id, display_name, provider_adapter_id, "
                    + "endpoint_profile_id, custom_base_url, auth_scheme, network_profile_json, state, revision, created_at, updated_at";

    String SECRET_REF_COLUMNS = "connection_id AS \"connectionId\", slot_id AS \"slotId\", vault_ref AS \"vaultRef\", "
            + "revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\"";

    String SECRET_REF_INSERT_COLUMNS = "connection_id, slot_id, vault_ref, revision, created_at, updated_at";

    String PROFILE_COLUMNS = "id, connection_id AS \"connectionId\", model_id AS \"modelId\", "
            + "structured_output_mode AS \"structuredOutputMode\", capability_json AS \"capabilityJson\", "
            + "safe_limits_json AS \"safeLimitsJson\", enabled, revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\"";

    String PROFILE_INSERT_COLUMNS =
            "id, connection_id, model_id, structured_output_mode, capability_json, safe_limits_json, enabled, revision, created_at, updated_at";

    String ROUTE_COLUMNS = "id, scope_type, scope_id, target_kind, target_id, ordered_profile_ids, enabled, revision, "
            + "created_at, updated_at";

    String ROUTE_INSERT_COLUMNS =
            "id, scope_type, scope_id, target_kind, target_id, ordered_profile_ids, enabled, revision, created_at, updated_at";

    String GRANT_COLUMNS = "workspace_id AS \"workspaceId\", connection_id AS \"connectionId\", "
            + "granted_by_user_id AS \"grantedByUserId\", grant_scope AS \"grantScope\", state, "
            + "created_at AS \"createdAt\", updated_at AS \"updatedAt\"";

    String GRANT_INSERT_COLUMNS =
            "workspace_id, connection_id, granted_by_user_id, grant_scope, state, created_at, updated_at";

    @SqlUpdate("INSERT INTO llm_connection (" + CONNECTION_INSERT_COLUMNS + ") "
            + "VALUES (:id, :scopeType, :scopeId, :createdByUserId, :displayName, :providerAdapterId, "
            + ":endpointProfileId, :customBaseUrl, :authScheme, :networkProfileJson, :state, 1, :createdAt, :updatedAt) "
            + "ON CONFLICT(id) DO NOTHING")
    int insertConnection(@BindMethods LlmConnectionDto connection);

    @SqlUpdate("UPDATE llm_connection SET scope_type = :scopeType, scope_id = :scopeId, "
            + "created_by_user_id = :createdByUserId, display_name = :displayName, "
            + "provider_adapter_id = :providerAdapterId, endpoint_profile_id = :endpointProfileId, "
            + "custom_base_url = :customBaseUrl, auth_scheme = :authScheme, "
            + "network_profile_json = :networkProfileJson, state = :state, "
            + "revision = llm_connection.revision + 1, updated_at = :updatedAt "
            + "WHERE id = :id AND revision = :expectedRevision")
    int updateConnection(@BindMethods LlmConnectionDto connection, @Bind("expectedRevision") int expectedRevision);

    @SqlUpdate("UPDATE llm_connection SET state = 'ACTIVE', revision = llm_connection.revision + 1, "
            + "updated_at = :updatedAt WHERE id = :id AND revision = :expectedRevision")
    int activateConnection(
            @Bind("id") String id,
            @Bind("expectedRevision") int expectedRevision,
            @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE model_profile SET enabled = 1, revision = model_profile.revision + 1, "
            + "updated_at = :updatedAt WHERE connection_id = :connectionId")
    int activateProfilesByConnection(@Bind("connectionId") String connectionId, @Bind("updatedAt") Instant updatedAt);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM llm_connection WHERE id = :id")
    Optional<LlmConnectionDto> findConnection(@Bind("id") String id);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS
            + " FROM llm_connection WHERE scope_type = :scopeType AND scope_id = :scopeId ORDER BY display_name ASC")
    List<LlmConnectionDto> listConnectionsByScope(@Bind("scopeType") String scopeType, @Bind("scopeId") String scopeId);

    @SqlQuery("SELECT " + CONNECTION_COLUMNS + " FROM llm_connection WHERE created_by_user_id = :createdByUserId")
    List<LlmConnectionDto> listConnectionsByCreatedBy(@Bind("createdByUserId") String createdByUserId);

    @SqlUpdate("DELETE FROM llm_connection WHERE id = :id")
    int deleteConnection(@Bind("id") String id);

    @SqlUpdate("INSERT INTO llm_connection_secret_ref (" + SECRET_REF_INSERT_COLUMNS + ") "
            + "VALUES (:connectionId, :slotId, :vaultRef, 1, :createdAt, :updatedAt) "
            + "ON CONFLICT(connection_id, slot_id) DO UPDATE SET vault_ref = excluded.vault_ref, "
            + "revision = llm_connection_secret_ref.revision + 1, updated_at = excluded.updated_at")
    int upsertSecretRef(@BindMethods LlmConnectionSecretRefDto ref);

    @SqlQuery("SELECT " + SECRET_REF_COLUMNS + " FROM llm_connection_secret_ref WHERE connection_id = :connectionId")
    List<LlmConnectionSecretRefDto> listSecretRefs(@Bind("connectionId") String connectionId);

    @SqlUpdate("DELETE FROM llm_connection_secret_ref WHERE connection_id = :connectionId")
    int deleteSecretRefs(@Bind("connectionId") String connectionId);

    @SqlUpdate("INSERT INTO model_profile (" + PROFILE_INSERT_COLUMNS + ") "
            + "VALUES (:id, :connectionId, :modelId, :structuredOutputMode, :capabilityJson, :safeLimitsJson, "
            + ":enabled, 1, :createdAt, :updatedAt) ON CONFLICT(id) DO NOTHING")
    int insertModelProfile(@BindMethods ModelProfileDto profile);

    @SqlUpdate("UPDATE model_profile SET connection_id = :connectionId, model_id = :modelId, "
            + "structured_output_mode = :structuredOutputMode, capability_json = :capabilityJson, "
            + "safe_limits_json = :safeLimitsJson, enabled = :enabled, revision = model_profile.revision + 1, "
            + "updated_at = :updatedAt WHERE id = :id AND revision = :expectedRevision")
    int updateModelProfile(@BindMethods ModelProfileDto profile, @Bind("expectedRevision") int expectedRevision);

    @SqlQuery("SELECT " + PROFILE_COLUMNS + " FROM model_profile WHERE id = :id")
    Optional<ModelProfileDto> findModelProfile(@Bind("id") String id);

    @SqlQuery("SELECT " + PROFILE_COLUMNS + " FROM model_profile WHERE connection_id = :connectionId")
    List<ModelProfileDto> listModelProfilesByConnection(@Bind("connectionId") String connectionId);

    @SqlUpdate("DELETE FROM model_profile WHERE id = :id")
    int deleteModelProfile(@Bind("id") String id);

    @SqlUpdate("DELETE FROM model_profile WHERE connection_id = :connectionId")
    int deleteModelProfileByConnection(@Bind("connectionId") String connectionId);

    @SqlUpdate("INSERT INTO model_route_assignment (" + ROUTE_INSERT_COLUMNS + ") "
            + "VALUES (:id, :scopeType, :scopeId, :targetKind, :targetId, :orderedProfileIds, :enabled, 1, "
            + ":createdAt, :updatedAt) ON CONFLICT(id) DO NOTHING")
    int insertRouteAssignment(
            @Bind("id") String id,
            @Bind("scopeType") String scopeType,
            @Bind("scopeId") String scopeId,
            @Bind("targetKind") String targetKind,
            @Bind("targetId") String targetId,
            @Bind("orderedProfileIds") String orderedProfileIds,
            @Bind("enabled") boolean enabled,
            @Bind("createdAt") Instant createdAt,
            @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE model_route_assignment SET scope_type = :scopeType, scope_id = :scopeId, "
            + "target_kind = :targetKind, target_id = :targetId, ordered_profile_ids = :orderedProfileIds, "
            + "enabled = :enabled, revision = model_route_assignment.revision + 1, updated_at = :updatedAt "
            + "WHERE id = :id AND revision = :expectedRevision")
    int updateRouteAssignment(
            @Bind("id") String id,
            @Bind("scopeType") String scopeType,
            @Bind("scopeId") String scopeId,
            @Bind("targetKind") String targetKind,
            @Bind("targetId") String targetId,
            @Bind("orderedProfileIds") String orderedProfileIds,
            @Bind("enabled") boolean enabled,
            @Bind("updatedAt") Instant updatedAt,
            @Bind("expectedRevision") int expectedRevision);

    @SqlQuery("SELECT " + ROUTE_COLUMNS + " FROM model_route_assignment WHERE id = :id")
    @RegisterRowMapper(RouteRowMapper.class)
    Optional<ModelRouteAssignmentDto> findRouteAssignment(@Bind("id") String id);

    @SqlQuery("SELECT " + ROUTE_COLUMNS
            + " FROM model_route_assignment WHERE scope_type = :scopeType AND scope_id = :scopeId")
    @RegisterRowMapper(RouteRowMapper.class)
    List<ModelRouteAssignmentDto> listRoutesByScope(
            @Bind("scopeType") String scopeType, @Bind("scopeId") String scopeId);

    @SqlQuery("SELECT " + ROUTE_COLUMNS
            + " FROM model_route_assignment WHERE target_kind = :targetKind "
            + "AND (target_id = :targetId OR (:targetId IS NULL AND target_id IS NULL))")
    @RegisterRowMapper(RouteRowMapper.class)
    List<ModelRouteAssignmentDto> listRoutesByTarget(
            @Bind("targetKind") String targetKind, @Bind("targetId") String targetId);

    @SqlUpdate("DELETE FROM model_route_assignment WHERE id = :id")
    int deleteRouteAssignment(@Bind("id") String id);

    @SqlUpdate("INSERT INTO workspace_connection_grant (" + GRANT_INSERT_COLUMNS + ") "
            + "VALUES (:workspaceId, :connectionId, :grantedByUserId, :grantScope, :state, :createdAt, :updatedAt) "
            + "ON CONFLICT(workspace_id, connection_id) DO UPDATE SET granted_by_user_id = excluded.granted_by_user_id, "
            + "grant_scope = excluded.grant_scope, state = excluded.state, updated_at = excluded.updated_at")
    int upsertGrant(@BindMethods WorkspaceConnectionGrantDto grant);

    @SqlQuery("SELECT " + GRANT_COLUMNS
            + " FROM workspace_connection_grant WHERE workspace_id = :workspaceId AND connection_id = :connectionId")
    Optional<WorkspaceConnectionGrantDto> findGrant(
            @Bind("workspaceId") String workspaceId, @Bind("connectionId") String connectionId);

    @SqlQuery("SELECT " + GRANT_COLUMNS + " FROM workspace_connection_grant WHERE workspace_id = :workspaceId")
    List<WorkspaceConnectionGrantDto> listGrantsByWorkspace(@Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT " + GRANT_COLUMNS + " FROM workspace_connection_grant WHERE connection_id = :connectionId")
    List<WorkspaceConnectionGrantDto> listGrantsByConnection(@Bind("connectionId") String connectionId);

    @SqlUpdate(
            "DELETE FROM workspace_connection_grant WHERE workspace_id = :workspaceId AND connection_id = :connectionId")
    int deleteGrant(@Bind("workspaceId") String workspaceId, @Bind("connectionId") String connectionId);

    class RouteRowMapper implements RowMapper<ModelRouteAssignmentDto> {
        private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

        private static List<String> parseList(String json) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return MAPPER.readValue(json, LIST);
            } catch (Exception e) {
                return List.of();
            }
        }

        @Override
        public ModelRouteAssignmentDto map(ResultSet rs, StatementContext ctx) throws SQLException {
            return new ModelRouteAssignmentDto(
                    rs.getString("id"),
                    rs.getString("scope_type"),
                    rs.getString("scope_id"),
                    rs.getString("target_kind"),
                    rs.getString("target_id"),
                    parseList(rs.getString("ordered_profile_ids")),
                    rs.getBoolean("enabled"),
                    rs.getInt("revision"),
                    toInstant(rs.getObject("created_at")),
                    toInstant(rs.getObject("updated_at")));
        }
    }

    ObjectMapper MAPPER = new ObjectMapper();

    static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        return Instant.parse(o.toString());
    }
}
