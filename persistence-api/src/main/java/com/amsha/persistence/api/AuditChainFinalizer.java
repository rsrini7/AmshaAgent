package com.amsha.persistence.api;

/**
 * Functional callback interface for HMAC audit chain finalization per Proposal §12.5.
 * <p>
 * Under the storage backend's per-session serialization boundary, the persistence layer
 * allocates the next sequence and retrieves the previous hash, then passes the seed event
 * to this finalizer to compute and populate HMAC fields without receiving or handling secret keys.
 */
@FunctionalInterface
public interface AuditChainFinalizer {

    /**
     * Finalizes an audit event with allocated sequence and previous hash.
     *
     * @param seed the unfinalized audit event seed
     * @param sequence the allocated sequence number
     * @param previousHash the hash of the preceding event in the session chain
     * @return the finalized audit event populated with HMAC fields
     */
    AuditEventDto finalizeChain(AuditEventDto seed, long sequence, String previousHash);
}
