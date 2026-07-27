package com.amsha.onboarding.spi;

/**
 * Bounded, idempotent commit with reverse-order compensation over the extension's own opaque receipt.
 * The coordinator durably records a PREPARED intent keyed by draft/stage/attempt before invoking side
 * effects, then records the receipt and COMMITTED state. Crash recovery replays the same operation id.
 */
public interface CompensatableOnboardingCommit {

    /**
     * Idempotent commit for the given operation id. Returns an opaque receipt for later compensation.
     */
    String commit(String operationId);

    /**
     * Idempotent compensation for a prior commit, given its receipt. Safe to call multiple times.
     */
    void compensate(String operationId, String receipt);
}
