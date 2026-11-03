package com.amsha.persistence.api;

/**
 * Resolution lifecycle status per Proposal §12.4.
 *
 * <ul>
 *   <li>{@link #PENDING}: Resolution authorized and pending application.</li>
 *   <li>{@link #APPLIED}: Resolution applied; lock cleared or retry admitted.</li>
 *   <li>{@link #EXPIRED}: Resolution expired without being applied.</li>
 * </ul>
 */
public enum ToolAttemptResolutionStatus {
    PENDING,
    APPLIED,
    EXPIRED;

    /**
     * Returns true if the resolution can legally transition (only {@link #PENDING}).
     */
    public boolean canTransition() {
        return this == PENDING;
    }

    /**
     * Returns true if this is an end-of-life status.
     */
    public boolean isTerminal() {
        return this == APPLIED || this == EXPIRED;
    }
}
