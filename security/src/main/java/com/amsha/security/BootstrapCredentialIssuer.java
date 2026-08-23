package com.amsha.security;

/**
 * SPI for issuing the first credential for the bootstrap owner.
 *
 * <p>Two-phase: {@link #prepare(String)} builds mutable credential material bound to the reserved
 * owner without persisting or registering it; {@link #activate(PreparedBootstrapCredential, int)}
 * verifies the matching {@code CREDENTIAL_ISSUED} reservation revision, durably writes the
 * {@code ApiCredential} record, and registers the digest in the registry in that order. A failed or
 * unactivated preparation holds no durable state and needs no rollback.
 */
public interface BootstrapCredentialIssuer {

    /**
     * Creates mutable credential material for the reserved owner. The raw key is returned only inside
     * the prepared credential and must be closed after activation so its bytes are zeroed.
     */
    PreparedBootstrapCredential prepare(String ownerId);

    /**
     * Verifies the prepared credential matches the {@code CREDENTIAL_ISSUED} reservation for
     * {@code expectedRevision}, then durably writes the API credential and registers the digest.
     *
     * @return the issued credential (raw key + digest) for one-time cookie delivery
     * @throws IllegalStateException if the reservation does not match or activation fails
     */
    IssuedCredential activate(PreparedBootstrapCredential prepared, int expectedRevision);

    /**
     * Revokes a prior bootstrap credential digest from transient registries.
     */
    default void revoke(String oldDigestHex) {}
}
