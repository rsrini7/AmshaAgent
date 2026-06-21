package com.amsha.channel.api.hitl;

/**
 * Channel-facing abstraction for querying pending HITL approval state.
 * Keeps channel.web independent of core.state internals (ArchUnit boundary).
 */
public interface PendingApprovalChecker {
    boolean hasPendingApproval(String sessionId);
}
