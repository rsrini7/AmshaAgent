package com.amsha.core.tools;

/**
 * Defines the idempotency contract for an agent tool.
 */
public enum IdempotencyMode {
    /**
     * Re-executing the tool with identical parameters is safe and side-effect free
     * relative to previous calls (e.g., READ operations, GET, LIST).
     */
    IDEMPOTENT,

    /**
     * The operation must execute exactly once. Re-execution with the same invocation
     * key is blocked to prevent duplicate side effects (e.g., CREATE, DELETE).
     */
    AT_MOST_ONCE,

    /**
     * Each call creates a new side effect, even with identical parameters
     * (e.g., APPEND, SEND_MESSAGE). Re-execution is permitted but should
     * be handled with caution by the loop.
     */
    NON_IDEMPOTENT
}
