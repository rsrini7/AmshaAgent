package com.amsha.persistence.api;

/**
 * Status of a durable action lock per Proposal §12.4.
 *
 * <ul>
 *   <li>{@link #ACTIVE}: Lock actively prevents matching operations.</li>
 *   <li>{@link #CLEARED}: Lock cleared through authorized resolution.</li>
 * </ul>
 */
public enum DurableActionLockStatus {
    ACTIVE,
    CLEARED
}
