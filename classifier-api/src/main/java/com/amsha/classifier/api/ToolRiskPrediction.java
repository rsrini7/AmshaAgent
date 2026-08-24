package com.amsha.classifier.api;

/** Bounded tool-risk prediction; it is never an authorization decision. */
public enum ToolRiskPrediction {
    LOW_RISK,
    REQUIRE_APPROVAL,
    BLOCK
}
