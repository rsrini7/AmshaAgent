package com.amsha.app.ui.dto;

import java.util.Set;

import com.amsha.security.api.RiskLevel;

/** Shared Java-side wire values for bounded run evidence. */
public final class RunEvidenceContract {
    public static final int MAX_DECISIONS = 100;

    public static final String END = "END";
    public static final String OBSERVED = "OBSERVED";
    public static final String MEASURED = "MEASURED";
    public static final String PARTIAL = "PARTIAL";
    public static final String PRICED = "PRICED";
    public static final String UNPRICED = "UNPRICED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String UNKNOWN = "UNKNOWN";

    public static final String ALLOW = "ALLOW";
    public static final String APPROVED = "APPROVED";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String DENIED = "DENIED";
    public static final String HITL_REQUIRED = "HITL_REQUIRED";
    public static final String RESUMED = "RESUMED";

    public static final Set<String> DECISIONS =
            Set.of(ALLOW, APPROVED, AWAITING_APPROVAL, DENIED, HITL_REQUIRED, RESUMED, UNKNOWN);
    public static final Set<String> OUTCOME_STATUS = Set.of(END, UNAVAILABLE, UNKNOWN);
    public static final Set<String> OUTCOME_AVAILABILITY = Set.of(OBSERVED, UNAVAILABLE);
    public static final Set<String> LATENCY_AVAILABILITY = Set.of(MEASURED, UNAVAILABLE);
    public static final Set<String> USAGE_AVAILABILITY = Set.of(OBSERVED, PARTIAL, UNAVAILABLE);
    public static final Set<String> COST_STATUS = Set.of(PRICED, UNPRICED, UNAVAILABLE);

    private RunEvidenceContract() {}

    public static boolean isRiskLevel(String value) {
        if (value == null) {
            return false;
        }
        for (RiskLevel riskLevel : RiskLevel.values()) {
            if (riskLevel.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
