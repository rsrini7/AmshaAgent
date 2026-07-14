package com.amsha.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(IdentityUserDto.class)
@RegisterConstructorMapper(ExternalIdentityDto.class)
@RegisterConstructorMapper(IdentityWorkspaceDto.class)
@RegisterConstructorMapper(WorkspaceMembershipDto.class)
public interface IdentityJdbiDao {

    // IdentityUser
    @SqlUpdate(
            "INSERT INTO identity_user (user_id, display_name, status, created_at, updated_at) "
                    + "VALUES (:userId, :displayName, :status, :createdAt, :updatedAt) "
                    + "ON CONFLICT(user_id) DO UPDATE SET display_name = EXCLUDED.display_name, status = EXCLUDED.status, updated_at = EXCLUDED.updated_at")
    void saveUser(@BindMethods IdentityUserDto user);

    @SqlQuery(
            "SELECT user_id AS \"userId\", display_name AS \"displayName\", status, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_user WHERE user_id = :userId")
    Optional<IdentityUserDto> findUserById(@Bind("userId") String userId);

    @SqlQuery(
            "SELECT user_id AS \"userId\", display_name AS \"displayName\", status, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_user ORDER BY user_id ASC")
    List<IdentityUserDto> listUsers();

    // ExternalIdentity
    @SqlUpdate(
            "INSERT INTO external_identity (provider_id, issuer, subject, user_id, safe_claims, created_at, updated_at) "
                    + "VALUES (:providerId, :issuer, :subject, :userId, :safeClaims, :createdAt, :updatedAt) "
                    + "ON CONFLICT(provider_id, issuer, subject) DO UPDATE SET user_id = EXCLUDED.user_id, safe_claims = EXCLUDED.safe_claims, updated_at = EXCLUDED.updated_at")
    void saveExternalIdentity(@BindMethods ExternalIdentityDto identity);

    @SqlQuery(
            "SELECT provider_id AS \"providerId\", issuer, subject, user_id AS \"userId\", safe_claims AS \"safeClaims\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM external_identity WHERE provider_id = :providerId AND issuer = :issuer AND subject = :subject")
    Optional<ExternalIdentityDto> findExternalIdentity(
            @Bind("providerId") String providerId, @Bind("issuer") String issuer, @Bind("subject") String subject);

    @SqlQuery(
            "SELECT provider_id AS \"providerId\", issuer, subject, user_id AS \"userId\", safe_claims AS \"safeClaims\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM external_identity WHERE provider_id = :providerId AND user_id = :userId")
    Optional<ExternalIdentityDto> findExternalIdentityByUserId(
            @Bind("providerId") String providerId, @Bind("userId") String userId);

    @SqlQuery(
            "SELECT provider_id AS \"providerId\", issuer, subject, user_id AS \"userId\", safe_claims AS \"safeClaims\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM external_identity WHERE user_id = :userId ORDER BY provider_id ASC")
    List<ExternalIdentityDto> listExternalIdentitiesByUserId(@Bind("userId") String userId);

    @SqlUpdate(
            "DELETE FROM external_identity WHERE provider_id = :providerId AND issuer = :issuer AND subject = :subject")
    void deleteExternalIdentity(
            @Bind("providerId") String providerId, @Bind("issuer") String issuer, @Bind("subject") String subject);

    // IdentityWorkspace
    @SqlUpdate(
            "INSERT INTO identity_workspace (workspace_id, display_name, created_by, created_at, updated_at) "
                    + "VALUES (:workspaceId, :displayName, :createdBy, :createdAt, :updatedAt) "
                    + "ON CONFLICT(workspace_id) DO UPDATE SET display_name = EXCLUDED.display_name, created_by = EXCLUDED.created_by, updated_at = EXCLUDED.updated_at")
    void saveWorkspace(@BindMethods IdentityWorkspaceDto workspace);

    @SqlQuery(
            "SELECT workspace_id AS \"workspaceId\", display_name AS \"displayName\", created_by AS \"createdBy\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_workspace WHERE workspace_id = :workspaceId")
    Optional<IdentityWorkspaceDto> findWorkspaceById(@Bind("workspaceId") String workspaceId);

    @SqlQuery(
            "SELECT workspace_id AS \"workspaceId\", display_name AS \"displayName\", created_by AS \"createdBy\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_workspace ORDER BY workspace_id ASC")
    List<IdentityWorkspaceDto> listWorkspaces();

    // WorkspaceMembership
    @SqlUpdate(
            "INSERT INTO workspace_membership (workspace_id, user_id, role, status, revision, created_at, updated_at) "
                    + "VALUES (:workspaceId, :userId, :role, :status, 0, :createdAt, :updatedAt) "
                    + "ON CONFLICT(workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role, status = EXCLUDED.status, "
                    + "revision = workspace_membership.revision + 1, updated_at = EXCLUDED.updated_at "
                    + "WHERE workspace_membership.revision = EXCLUDED.revision")
    int saveMembership(@BindMethods WorkspaceMembershipDto membership);

    @SqlQuery(
            "SELECT workspace_id AS \"workspaceId\", user_id AS \"userId\", role, status, revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM workspace_membership WHERE workspace_id = :workspaceId AND user_id = :userId")
    Optional<WorkspaceMembershipDto> findMembership(
            @Bind("workspaceId") String workspaceId, @Bind("userId") String userId);

    @SqlQuery(
            "SELECT workspace_id AS \"workspaceId\", user_id AS \"userId\", role, status, revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM workspace_membership WHERE workspace_id = :workspaceId ORDER BY user_id ASC")
    List<WorkspaceMembershipDto> listMembershipsByWorkspaceId(@Bind("workspaceId") String workspaceId);

    @SqlQuery(
            "SELECT workspace_id AS \"workspaceId\", user_id AS \"userId\", role, status, revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM workspace_membership WHERE user_id = :userId ORDER BY workspace_id ASC")
    List<WorkspaceMembershipDto> listMembershipsByUserId(@Bind("userId") String userId);

    @SqlUpdate("DELETE FROM workspace_membership WHERE workspace_id = :workspaceId AND user_id = :userId")
    int deleteMembership(@Bind("workspaceId") String workspaceId, @Bind("userId") String userId);
}
