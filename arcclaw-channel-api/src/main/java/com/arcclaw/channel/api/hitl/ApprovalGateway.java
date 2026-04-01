package com.arcclaw.channel.api.hitl;

/**
 * SPI contract for HITL approval decisions.
 * Channel modules depend on this interface only — never on the core impl.
 * §9.1
 */
public interface ApprovalGateway {
    ApprovalResult decide(String sessionId, String toolName,
                          ApprovalDecision decision, String reason);
}
