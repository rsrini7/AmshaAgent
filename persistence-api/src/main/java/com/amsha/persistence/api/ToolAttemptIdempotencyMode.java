package com.amsha.persistence.api;

import java.util.Locale;

/**
 * Execution idempotency mode snapshot per Proposal §12.4.
 *
 * <ul>
 *   <li>{@link #IDEMPOTENT}: Re-execution produces identical outcome without side effects.</li>
 *   <li>{@link #AT_MOST_ONCE}: Single execution fence; replay returns stored result when terminal.</li>
 *   <li>{@link #NON_IDEMPOTENT}: Side-effecting; repeat attempts require explicit approval.</li>
 * </ul>
 */
public enum ToolAttemptIdempotencyMode {
    IDEMPOTENT,
    AT_MOST_ONCE,
    NON_IDEMPOTENT;

    /**
     * Parses an idempotency mode string, failing closed to {@link #NON_IDEMPOTENT} for unknown values.
     *
     * @param value the string value to parse
     * @return the resolved mode, or {@link #NON_IDEMPOTENT} if null or unrecognized
     */
    public static ToolAttemptIdempotencyMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return NON_IDEMPOTENT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return NON_IDEMPOTENT;
        }
    }
}
