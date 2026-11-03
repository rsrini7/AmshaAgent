package com.amsha.persistence.api;

/**
 * Scope of a durable action lock per Proposal §8 and §12.4.
 *
 * <ul>
 *   <li>{@link #EXACT_REQUEST}: Blocks re-execution of the same action fingerprint.</li>
 *   <li>{@link #TARGET_RESOURCE}: Blocks operations addressing the same trusted target resource fingerprint.</li>
 *   <li>{@link #SESSION_MUTATIONS}: Fallback scope blocking session mutations when a safe target identity
 *       cannot be derived.</li>
 * </ul>
 */
public enum ActionLockScope {
    EXACT_REQUEST,
    TARGET_RESOURCE,
    SESSION_MUTATIONS
}
