package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for managing the atomic bootstrap state of the deployment.
 *
 * <p>The state machine is {@code UNCLAIMED -> CLAIMING -> CREDENTIAL_ISSUED -> CLAIMED}. All
 * transitions are compare-and-set on the persisted revision so concurrent claims cannot both win,
 * and a losing claimant creates no identity/workspace/credential rows.
 *
 * <p>All implementations (SQLite, PostgreSQL, filesystem) must share one externally atomic transition
 * contract and crash-recovery semantics. The filesystem implementation provides atomic visibility
 * through the {@code FilesystemFileStore} temp-file + atomic-move swap.
 */
public interface BootstrapStateStorePort {

    Optional<BootstrapStateDto> getState();

    /**
     * Seed-only initialization of the bootstrap row (typically UNCLAIMED). This must NOT be used to
     * mutate an already-existing row.
     */
    void save(BootstrapStateDto state);

    /**
     * Compares-and-sets UNCLAIMED -> CLAIMING, reserving the owner and workspace IDs. Returns the
     * new reservation revision when the transition applied, or empty when another claimant already
     * won (or the row is no longer UNCLAIMED). The winner creates identity/workspace rows only after
     * {@link #recordCredentialIssued}; a loser creates nothing.
     */
    Optional<Integer> reserveFirstOwner(String ownerId, String workspaceId, Instant reservedAt);

    /**
     * Compares-and-sets CLAIMING -> CREDENTIAL_ISSUED for the expected reservation revision.
     *
     * @return true if the transition applied
     */
    boolean recordCredentialIssued(int expectedRevision, String credentialDigest, Instant issuedAt);

    /**
     * Compares-and-sets CREDENTIAL_ISSUED -> CLAIMED, bound to the reserved owner.
     *
     * @return true if the transition applied for the expected revision and owner
     */
    boolean confirmClaim(int expectedRevision, String authenticatedOwnerId, Instant claimedAt);

    /**
     * Compares-and-sets CLAIMING -> UNCLAIMED, abandoning a stale reservation (crash / expired TTL)
     * and leaving no identity/workspace/credential rows.
     *
     * @return true if the transition applied
     */
    boolean rollbackClaiming(int expectedRevision, Instant rolledBackAt);

    /**
     * Re-issues the bootstrap credential while CREDENTIAL_ISSUED, guarded by revision, for the
     * reserved owner. Permitting CLAIMING here is an explicit, operator-only recovery widening for a
     * reserved-but-never-activated owner within its reissue TTL; it must not be exposed on the public
     * claim endpoint or to any non-operator principal, or the reservation race guard is silently
     * defeated.
     *
     * @return true if the credential was replaced for the expected revision
     */
    boolean replaceCredential(int expectedRevision, String newDigest, Instant issuedAt, Instant updatedAt);
}
