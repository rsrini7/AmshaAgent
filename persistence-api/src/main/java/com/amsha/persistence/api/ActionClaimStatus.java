package com.amsha.persistence.api;

/**
 * Status of an AT_MOST_ONCE tool action claim per Proposal §12.4.
 *
 * <ul>
 *   <li>{@link #RESERVED}: Claim reserved during active execution or indeterminate outcome.</li>
 *   <li>{@link #CONSUMED}: Claim consumed upon terminal success, error, or timeout.</li>
 * </ul>
 */
public enum ActionClaimStatus {
    RESERVED,
    CONSUMED
}
