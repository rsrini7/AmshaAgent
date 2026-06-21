package com.amsha.channel.api.hitl;

public record ApprovalDecisionEvent(
        String sessionId,
        String channelType,
        String channelPlatformId,
        String toolName,
        ApprovalDecision decision,
        ApprovalOutcome outcome) {}
