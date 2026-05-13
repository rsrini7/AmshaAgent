package com.arcclaw.channel.api.hitl;

import java.util.List;

public record ApprovalResult(
    ApprovalOutcome outcome,
    String sessionId,
    String toolName,
    int remainingPendingTools,
    List<String> pendingToolNames
) {
    public static ApprovalResult alreadyDecided(
            String sessionId, String toolName) {
        return new ApprovalResult(
            ApprovalOutcome.ALREADY_DECIDED, sessionId, toolName, 0, List.of());
    }
}
