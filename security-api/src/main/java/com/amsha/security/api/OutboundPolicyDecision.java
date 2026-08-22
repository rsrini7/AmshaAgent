package com.amsha.security.api;

import java.util.Optional;

/**
 * Immutable decision from the {@link OutboundHttpPolicyService}. A decision is either ALLOW, DENY,
 * or REQUIRE_HITL (human-in-the-loop approval required before proceeding).
 */
public record OutboundPolicyDecision(
        Decision decision, Optional<String> reason, OutboundPurpose purpose, String actorUserId) {

    public enum Decision {
        ALLOW,
        DENY,
        REQUIRE_HITL
    }

    public boolean isAllowed() {
        return decision == Decision.ALLOW;
    }

    public boolean isDenied() {
        return decision == Decision.DENY;
    }

    public boolean requiresHitl() {
        return decision == Decision.REQUIRE_HITL;
    }

    public static OutboundPolicyDecision allow(OutboundPurpose purpose, String actorUserId) {
        return new OutboundPolicyDecision(Decision.ALLOW, Optional.empty(), purpose, actorUserId);
    }

    public static OutboundPolicyDecision deny(String reason, OutboundPurpose purpose, String actorUserId) {
        return new OutboundPolicyDecision(Decision.DENY, Optional.of(reason), purpose, actorUserId);
    }

    public static OutboundPolicyDecision requireHitl(String reason, OutboundPurpose purpose, String actorUserId) {
        return new OutboundPolicyDecision(Decision.REQUIRE_HITL, Optional.of(reason), purpose, actorUserId);
    }
}
