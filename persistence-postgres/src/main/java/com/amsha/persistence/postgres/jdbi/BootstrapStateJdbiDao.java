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
            "INSERT INTO bootstrap_state (id, status, owner_id, credential_digest, issued_at, claimed_at, revision, updated_at) "
                    + "VALUES (:id, :status, :ownerId, :credentialDigest, :issuedAt, :claimedAt, :revision, :updatedAt) "
                    + "ON CONFLICT(id) DO UPDATE SET status = EXCLUDED.status, owner_id = EXCLUDED.owner_id, credential_digest = EXCLUDED.credential_digest, "
                    + "issued_at = EXCLUDED.issued_at, claimed_at = EXCLUDED.claimed_at, revision = EXCLUDED.revision, updated_at = EXCLUDED.updated_at")
    void save(@BindMethods BootstrapStateDto state);

    @SqlUpdate(
            "INSERT INTO bootstrap_state (id, status, owner_id, credential_digest, issued_at, claimed_at, revision, updated_at) "
                    + "VALUES ('bootstrap', 'CREDENTIAL_ISSUED', :ownerId, :credentialDigest, :issuedAt, NULL, 1, :issuedAt) "
                    + "ON CONFLICT(id) DO UPDATE SET status = 'CREDENTIAL_ISSUED', owner_id = EXCLUDED.owner_id, "
                    + "credential_digest = EXCLUDED.credential_digest, issued_at = EXCLUDED.issued_at, "
                    + "revision = bootstrap_state.revision + 1, updated_at = EXCLUDED.updated_at "
                    + "WHERE bootstrap_state.status = 'UNCLAIMED'")
    int claimFirstOwner(
            @Bind("ownerId") String ownerId,
            @Bind("credentialDigest") String credentialDigest,
            @Bind("issuedAt") Instant issuedAt);

    @SqlUpdate("UPDATE bootstrap_state SET status = 'CLAIMED', claimed_at = :claimedAt, "
            + "revision = bootstrap_state.revision + 1, updated_at = :updatedAt "
            + "WHERE id = 'bootstrap' AND status = 'CREDENTIAL_ISSUED' AND revision = :expectedRevision")
    int confirmClaim(
            @Bind("expectedRevision") int expectedRevision,
            @Bind("claimedAt") Instant claimedAt,
            @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate(
            "UPDATE bootstrap_state SET credential_digest = :newDigest, issued_at = :issuedAt, "
                    + "revision = bootstrap_state.revision + 1, updated_at = :updatedAt "
                    + "WHERE id = 'bootstrap' AND status IN ('UNCLAIMED', 'CREDENTIAL_ISSUED') AND revision = :expectedRevision")
    int replaceCredential(
            @Bind("expectedRevision") int expectedRevision,
            @Bind("newDigest") String newDigest,
            @Bind("issuedAt") Instant issuedAt,
            @Bind("updatedAt") Instant updatedAt);

    @SqlQuery("SELECT id, status, owner_id AS \"ownerId\", credential_digest AS \"credentialDigest\", "
            + "issued_at AS \"issuedAt\", claimed_at AS \"claimedAt\", revision, updated_at AS \"updatedAt\" "
            + "FROM bootstrap_state WHERE id = 'bootstrap'")
    Optional<BootstrapStateDto> getState();
}
