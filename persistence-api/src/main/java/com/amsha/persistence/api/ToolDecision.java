package com.amsha.persistence.api;

/**
 * Represents the human-in-the-loop decision state for a single tool in a batch.
 */
public enum ToolDecision {
    /** Per-tool decision: tool has not yet been acted on. §7.20 */
    PENDING,
    /** Per-tool decision: tool was explicitly approved by the user. §7.20 */
    APPROVED,
    /** Per-tool decision: tool was explicitly denied by the user. §7.20 */
    DENIED
}
