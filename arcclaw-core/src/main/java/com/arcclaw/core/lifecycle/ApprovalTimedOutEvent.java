package com.arcclaw.core.lifecycle;

/**
 * Fired by SessionReaper when an AWAITING_APPROVAL checkpoint expires.
 * Observed by app-layer to deliver channel notifications.
 */
public record ApprovalTimedOutEvent(
        String sessionId,
        String channelPlatformId,
        String channelType,
        String pendingToolName,
        long timeoutMinutes
) {}
