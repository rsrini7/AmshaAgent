package com.arcclaw.channel.api.hitl;

public record ApprovalResult(
    ApprovalOutcome outcome,
    String sessionId,
    String toolName,
    int remainingPendingTools
) {
    public static ApprovalResult alreadyDecided(
            String sessionId, String toolName) {
        return new ApprovalResult(
            ApprovalOutcome.ALREADY_DECIDED, sessionId, toolName, 0);
    }
}
