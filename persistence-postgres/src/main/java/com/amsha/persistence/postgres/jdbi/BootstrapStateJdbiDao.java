package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(BootstrapStateDto.class)
public interface BootstrapStateJdbiDao {

    @SqlUpdate(
            "INSERT INTO bootstrap_state (id, status, owner_id, workspace_id, credential_digest, issued_at, claimed_at, revision, updated_at) "
                    + "VALUES (:id, :status, :ownerId, :workspaceId, :credentialDigest, :issuedAt, :claimedAt, :revision, :updatedAt) "
                    + "ON CONFLICT(id) DO NOTHING")
    void save(@BindMethods BootstrapStateDto state);

    @SqlUpdate("INSERT INTO bootstrap_state "
            + "(id, status, owner_id, workspace_id, credential_digest, issued_at, claimed_at, revision, updated_at) "
            + "VALUES ('bootstrap', 'CLAIMING', :ownerId, :workspaceId, NULL, NULL, NULL, 1, :reservedAt) "
            + "ON CONFLICT(id) DO UPDATE SET status = 'CLAIMING', owner_id = :ownerId, "
            + "workspace_id = :workspaceId, credential_digest = NULL, issued_at = NULL, claimed_at = NULL, "
            + "revision = bootstrap_state.revision + 1, updated_at = :reservedAt "
            + "WHERE bootstrap_state.status = 'UNCLAIMED' "
            + "AND (bootstrap_state.owner_id IS NULL OR bootstrap_state.owner_id = '')")
    int reserveFirstOwner(
            @Bind("ownerId") String ownerId,
            @Bind("workspaceId") String workspaceId,
            @Bind("reservedAt") Instant reservedAt);

    @SqlUpdate("UPDATE bootstrap_state SET status = 'CREDENTIAL_ISSUED', credential_digest = :credentialDigest, "
            + "issued_at = :issuedAt, revision = bootstrap_state.revision + 1, updated_at = :issuedAt "
            + "WHERE id = 'bootstrap' AND status = 'CLAIMING' AND revision = :expectedRevision")
    int recordCredentialIssued(
            @Bind("expectedRevision") int expectedRevision,
            @Bind("credentialDigest") String credentialDigest,
            @Bind("issuedAt") Instant issuedAt);

    @SqlUpdate("UPDATE bootstrap_state SET status = 'CLAIMED', claimed_at = :claimedAt, "
            + "revision = bootstrap_state.revision + 1, updated_at = :claimedAt "
            + "WHERE id = 'bootstrap' AND status = 'CREDENTIAL_ISSUED' AND revision = :expectedRevision "
            + "AND owner_id = :authenticatedOwnerId")
    int confirmClaim(
            @Bind("expectedRevision") int expectedRevision,
            @Bind("authenticatedOwnerId") String authenticatedOwnerId,
            @Bind("claimedAt") Instant claimedAt);

    @SqlUpdate("UPDATE bootstrap_state SET status = 'UNCLAIMED', owner_id = NULL, workspace_id = NULL, "
            + "credential_digest = NULL, issued_at = NULL, claimed_at = NULL, "
            + "revision = bootstrap_state.revision + 1, updated_at = :rolledBackAt "
            + "WHERE id = 'bootstrap' AND status = 'CLAIMING' AND revision = :expectedRevision")
    int rollbackClaiming(@Bind("expectedRevision") int expectedRevision, @Bind("rolledBackAt") Instant rolledBackAt);

    @SqlUpdate("UPDATE bootstrap_state SET credential_digest = :newDigest, issued_at = :issuedAt, "
            + "revision = bootstrap_state.revision + 1, updated_at = :updatedAt "
            + "WHERE id = 'bootstrap' AND status IN ('CREDENTIAL_ISSUED', 'CLAIMING') AND revision = :expectedRevision")
    int replaceCredential(
            @Bind("expectedRevision") int expectedRevision,
            @Bind("newDigest") String newDigest,
            @Bind("issuedAt") Instant issuedAt,
            @Bind("updatedAt") Instant updatedAt);

    @SqlQuery("SELECT id, status, owner_id AS \"ownerId\", workspace_id AS \"workspaceId\", "
            + "credential_digest AS \"credentialDigest\", "
            + "issued_at AS \"issuedAt\", claimed_at AS \"claimedAt\", revision, updated_at AS \"updatedAt\" "
            + "FROM bootstrap_state WHERE id = 'bootstrap'")
    Optional<BootstrapStateDto> getState();
}
