package com.amsha.core.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Hand-written {@link DecisionProviderConfig.Slot.Governance} doubles shared by the decision-provider
 * tests.
 *
 * <p>The mapping member is a plain interface, so a double is cheaper than a container. Keeping one copy
 * means the "complete declaration" every test starts from cannot drift between files: a rule added to
 * the mapping forces one update here rather than three silent divergences.
 */
public final class DecisionGovernanceFixture {

    private DecisionGovernanceFixture() {}

    /** The complete declaration both lanes accept. */
    public static DecisionProviderConfig.Slot.Governance valid() {
        return all("TEST_REGION", Duration.ZERO, false, true, List.of("goal", "state"), null);
    }

    /**
     * A fully-specified declaration, for the cases that vary more than one field.
     *
     * @param dataLocation residency; {@code null} means undeclared
     * @param retentionWindow maximum retention
     * @param allowTrainingUse training-use flag
     * @param tenantIsolation tenant-isolation flag
     * @param projectionAllowList allow-list; {@code null} means undeclared
     * @param costCeilingPerThousand approved cost ceiling; {@code null} means none approved
     * @return the declaration
     */
    public static DecisionProviderConfig.Slot.Governance all(
            String dataLocation,
            Duration retentionWindow,
            boolean allowTrainingUse,
            boolean tenantIsolation,
            List<String> projectionAllowList,
            BigDecimal costCeilingPerThousand) {
        return new Governance(
                Optional.ofNullable(dataLocation),
                retentionWindow,
                allowTrainingUse,
                tenantIsolation,
                Optional.ofNullable(projectionAllowList),
                Optional.ofNullable(costCeilingPerThousand));
    }

    public static DecisionProviderConfig.Slot.Governance withDataLocation(String dataLocation) {
        return all(dataLocation, Duration.ZERO, false, true, List.of("goal"), null);
    }

    public static DecisionProviderConfig.Slot.Governance withAllowList(List<String> projectionAllowList) {
        return all("TEST_REGION", Duration.ZERO, false, true, projectionAllowList, null);
    }

    public static DecisionProviderConfig.Slot.Governance withTrainingUse(boolean allowTrainingUse) {
        return all("TEST_REGION", Duration.ZERO, allowTrainingUse, true, List.of("goal"), null);
    }

    public static DecisionProviderConfig.Slot.Governance withTenantIsolation(boolean tenantIsolation) {
        return all("TEST_REGION", Duration.ZERO, false, tenantIsolation, List.of("goal"), null);
    }

    public static DecisionProviderConfig.Slot.Governance withRetentionWindow(Duration retentionWindow) {
        return all("TEST_REGION", retentionWindow, false, true, List.of("goal"), null);
    }

    public static DecisionProviderConfig.Slot.Governance withCostCeiling(BigDecimal costCeilingPerThousand) {
        return all("TEST_REGION", Duration.ZERO, false, true, List.of("goal"), costCeilingPerThousand);
    }

    /**
     * The declaration, with component types matching the mapping interface exactly so the record's own
     * accessors satisfy it and no member has to be hand-written.
     */
    private record Governance(
            Optional<String> dataLocation,
            Duration retentionWindow,
            boolean allowTrainingUse,
            boolean tenantIsolation,
            Optional<List<String>> projectionAllowList,
            Optional<BigDecimal> costCeilingPerThousand)
            implements DecisionProviderConfig.Slot.Governance {}
}
