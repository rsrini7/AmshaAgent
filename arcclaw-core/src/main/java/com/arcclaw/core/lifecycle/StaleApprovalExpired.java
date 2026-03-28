package com.arcclaw.core.lifecycle;

public record StaleApprovalExpired(
    String sessionId,
    String agentRole,
    String userId,
    String channelType,
    String channelPlatformId,
    String pendingToolName
) {}
