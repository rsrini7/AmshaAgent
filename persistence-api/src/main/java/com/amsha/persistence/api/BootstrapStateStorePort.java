package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for managing the atomic bootstrap state of the deployment.
 */
public interface BootstrapStateStorePort {
    Optional<BootstrapStateDto> getState();

    void save(BootstrapStateDto state);

    /**
     * Transitions an unclaimed bootstrap row to CREDENTIAL_ISSUED, atomically claiming the
     * first owner. Succeeds only when the row is absent or still UNCLAIMED.
     *
     * @return true if the claim was recorded, false if another claim already won
     */
    boolean claimFirstOwner(String ownerId, String credentialDigest, Instant issuedAt);

    /**
     * Transitions CREDENTIAL_ISSUED to CLAIMED, confirming the owner accepted the credential.
     *
     * @return true if the confirmation was applied for the expected revision
     */
    boolean confirmClaim(int expectedRevision, Instant claimedAt, Instant updatedAt);

    /**
     * Re-issues the bootstrap credential while UNCLAIMED or CREDENTIAL_ISSUED, guarded by revision.
     *
     * @return true if the credential was replaced for the expected revision
     */
    boolean replaceCredential(int expectedRevision, String newDigest, Instant issuedAt, Instant updatedAt);
}
