package com.amsha.core.agent.loop;

/** Bounded tool-risk prediction; it is never an authorization decision. */
public enum ToolRiskPrediction {
    LOW_RISK,
    REQUIRE_APPROVAL,
    BLOCK
}
