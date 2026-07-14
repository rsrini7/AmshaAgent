package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.IdentityInvitationDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(IdentityInvitationDto.class)
public interface IdentityInvitationJdbiDao {

    @SqlUpdate(
            "INSERT INTO identity_invitation (invitation_id, workspace_id, role, token_digest, optional_email_constraint, expires_at, accepted_by, revoked_at, revision, created_at, updated_at) "
                    + "VALUES (:invitationId, :workspaceId, :role, :tokenDigest, :optionalEmailConstraint, :expiresAt, :acceptedBy, :revokedAt, :revision, :createdAt, :updatedAt) "
                    + "ON CONFLICT(invitation_id) DO UPDATE SET workspace_id = excluded.workspace_id, role = excluded.role, token_digest = excluded.token_digest, "
                    + "optional_email_constraint = excluded.optional_email_constraint, expires_at = excluded.expires_at, accepted_by = excluded.accepted_by, "
                    + "revoked_at = excluded.revoked_at, revision = identity_invitation.revision + 1, updated_at = excluded.updated_at "
                    + "WHERE identity_invitation.revision = excluded.revision")
    int save(@BindMethods IdentityInvitationDto invitation);

    @SqlQuery(
            "SELECT invitation_id AS \"invitationId\", workspace_id AS \"workspaceId\", role, token_digest AS \"tokenDigest\", "
                    + "optional_email_constraint AS \"optionalEmailConstraint\", expires_at AS \"expiresAt\", accepted_by AS \"acceptedBy\", "
                    + "revoked_at AS \"revokedAt\", revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_invitation WHERE invitation_id = :invitationId")
    Optional<IdentityInvitationDto> findById(@Bind("invitationId") String invitationId);

    @SqlQuery(
            "SELECT invitation_id AS \"invitationId\", workspace_id AS \"workspaceId\", role, token_digest AS \"tokenDigest\", "
                    + "optional_email_constraint AS \"optionalEmailConstraint\", expires_at AS \"expiresAt\", accepted_by AS \"acceptedBy\", "
                    + "revoked_at AS \"revokedAt\", revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_invitation WHERE token_digest = :tokenDigest")
    Optional<IdentityInvitationDto> findByTokenDigest(@Bind("tokenDigest") String tokenDigest);

    @SqlQuery(
            "SELECT invitation_id AS \"invitationId\", workspace_id AS \"workspaceId\", role, token_digest AS \"tokenDigest\", "
                    + "optional_email_constraint AS \"optionalEmailConstraint\", expires_at AS \"expiresAt\", accepted_by AS \"acceptedBy\", "
                    + "revoked_at AS \"revokedAt\", revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM identity_invitation WHERE workspace_id = :workspaceId ORDER BY created_at ASC")
    List<IdentityInvitationDto> listByWorkspaceId(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM identity_invitation WHERE invitation_id = :invitationId")
    int deleteById(@Bind("invitationId") String invitationId);

    @SqlUpdate("DELETE FROM identity_invitation WHERE expires_at < :now")
    int deleteExpired(@Bind("now") Instant now);
}
