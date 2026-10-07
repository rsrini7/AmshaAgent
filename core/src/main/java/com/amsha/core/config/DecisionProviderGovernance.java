package com.amsha.core.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.RetentionPolicy;
import com.amsha.classifier.api.StateProjectionPolicy;

/**
 * A validated data-governance declaration for one decision provider slot.
 *
 * <p>The parent ADR's Governance gate requires retention, training-use, residency and tenant-isolation
 * decisions to be declared before state may leave the process. This record is where those declarations
 * are turned into the two policy objects the engines actually consume — a {@link RetentionPolicy} and a
 * {@link StateProjectionPolicy} — so that an incomplete declaration cannot reach an engine as a
 * silently permissive default.
 *
 * <p><b>It lives in the config package on purpose.</b> The rules below are a property of the
 * declaration, not of the engine, so the configuration surface validates them: {@link
 * DecisionProviderSelector} refuses an enabled slot whose governance is incomplete, and the factory
 * re-checks before it constructs. Keeping the record here means {@code classifier.provider} depends on
 * {@code config} and never the reverse — the factory throws its own {@code
 * DecisionProviderUnavailableException} rather than this record carrying an exception type from a
 * package that already depends on this one.
 *
 * <p><b>Completeness is lane-dependent and enforced by {@link #problem}.</b> A remote slot that egresses
 * state must declare its residency; a self-hosted loopback slot need not, because the state never leaves
 * the machine. Two rules are unconditional on both lanes because they describe what a decision provider
 * may do with state, not where it runs: training use must be forbidden and tenant isolation must be
 * enforced. The projection allow-list is also unconditional — a separate process receives the state
 * either way, and the redactor fails closed without one.
 *
 * <p>The refusal reason names operator configuration only — a provider id and the rule it violates. It
 * never carries state, a credential, or a credential reference value.
 */
public record DecisionProviderGovernance(
        String providerId,
        String dataLocation,
        Duration retentionWindow,
        boolean allowTrainingUse,
        boolean tenantIsolation,
        Set<String> projectionAllowList,
        BigDecimal costCeilingPerThousand,
        DecisionProviderSelector.Lane lane) {

    public DecisionProviderGovernance {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(dataLocation, "dataLocation");
        Objects.requireNonNull(retentionWindow, "retentionWindow");
        Objects.requireNonNull(projectionAllowList, "projectionAllowList");
        Objects.requireNonNull(lane, "lane");
        if (retentionWindow.isNegative()) {
            throw new IllegalArgumentException("retentionWindow must not be negative, got " + retentionWindow);
        }
        if (projectionAllowList.isEmpty()) {
            throw new IllegalArgumentException("projectionAllowList must not be empty");
        }
        projectionAllowList = Set.copyOf(projectionAllowList);
    }

    /**
     * The reason a declared governance group is incomplete for the lane it resolved to, if any.
     *
     * <p>Returns an empty {@link Optional} exactly when the declaration is complete and self-consistent.
     * A {@code null} declaration — which a hand-written test double may produce, and which a mapping
     * never should — is treated as an absent declaration and therefore as a refusal, so an
     * unimplemented group fails closed instead of resolving.
     *
     * <p>The reason is payload-free: it names a rule and, where useful, the operator value that violates
     * it. It never carries state or a credential.
     *
     * @param declared the bound governance group, or {@code null} when none is declared
     * @param lane the lane the endpoint resolved to
     * @return the refusal reason, or empty when the declaration is complete
     */
    public static Optional<String> problem(
            DecisionProviderConfig.Slot.Governance declared, DecisionProviderSelector.Lane lane) {
        Objects.requireNonNull(lane, "lane");
        if (declared == null) {
            return Optional.of("declares no governance block; retention, residency, training-use,"
                    + " tenant-isolation, and a projection allow-list must all be declared explicitly");
        }
        Duration window = declared.retentionWindow();
        if (window == null || window.isNegative()) {
            return Optional.of("declares a negative retention window (" + window + ")");
        }
        if (declared.allowTrainingUse()) {
            return Optional.of("declares allow-training-use=true; provider training on submitted state is"
                    + " forbidden for decision evaluation");
        }
        if (!declared.tenantIsolation()) {
            return Optional.of(
                    "declares tenant-isolation=false; tenant isolation is required for decision" + " evaluation");
        }
        String dataLocation = declared.dataLocation().map(String::trim).orElse("");
        if (lane == DecisionProviderSelector.Lane.REMOTE && dataLocation.isEmpty()) {
            // Residency is only meaningful where state actually leaves the machine, so it is required on
            // the remote lane and optional on the loopback one rather than demanded of both.
            return Optional.of("egresses state on the remote lane but declares no governance data-location"
                    + " (residency), so the declaration is incomplete");
        }
        List<String> allowList = normalize(declared.projectionAllowList().orElse(List.of()));
        if (allowList.isEmpty()) {
            // The projection redactor fails closed without an allow-list, and inventing one from the
            // question handles is exactly the widening this control exists to prevent. Refuse instead.
            return Optional.of("declares no governance projection-allow-list; the state projection fails"
                    + " closed without an explicit allow-list, so the declaration is incomplete");
        }
        BigDecimal ceiling = declared.costCeilingPerThousand().orElse(null);
        if (ceiling != null && ceiling.signum() < 0) {
            return Optional.of("declares a negative cost-ceiling-per-thousand (" + ceiling + ")");
        }
        return Optional.empty();
    }

    /**
     * Builds the validated declaration for a group that {@link #problem} has already accepted.
     *
     * <p>The two rules {@code problem} cannot express as a return value — a training-use flag that must
     * be false and a tenant-isolation flag that must be true — are normalised here rather than copied, so
     * the record can never hold the permissive value even if a caller skipped validation.
     *
     * @param providerId the declared slot id
     * @param declared a declaration for which {@link #problem} returned empty
     * @param lane the lane the endpoint resolved to
     * @return the validated declaration
     */
    public static DecisionProviderGovernance of(
            String providerId, DecisionProviderConfig.Slot.Governance declared, DecisionProviderSelector.Lane lane) {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(declared, "declared");
        Objects.requireNonNull(lane, "lane");
        List<String> allowList = normalize(declared.projectionAllowList().orElse(List.of()));
        return new DecisionProviderGovernance(
                providerId,
                declared.dataLocation().map(String::trim).orElse(""),
                declared.retentionWindow(),
                false,
                true,
                Set.copyOf(allowList),
                declared.costCeilingPerThousand().orElse(null),
                lane);
    }

    /** The retention constraints to hand the engine: zero-or-bounded retention, training forbidden. */
    public RetentionPolicy retentionPolicy() {
        // localOnly mirrors the lane: a loopback provider never exports state, a remote one does. The
        // two are contradictory in a DecisionDefinition, so the value must follow the lane rather than
        // be assumed.
        return new RetentionPolicy(retentionWindow, false, lane == DecisionProviderSelector.Lane.SELF_HOSTED);
    }

    /** The allow-listed projection policy to hand the engine. */
    public StateProjectionPolicy projectionPolicy() {
        return StateProjectionPolicy.of(projectionAllowList, RedactionPolicy.DEFAULT);
    }

    /** Whether an approved cost ceiling exists; required for production activation, not for evidence. */
    public boolean hasCostCeiling() {
        return costCeilingPerThousand != null;
    }

    @Override
    public String toString() {
        return "DecisionProviderGovernance[provider=" + providerId
                + ", lane=" + lane
                + ", dataLocation=" + (dataLocation.isEmpty() ? "none" : dataLocation)
                + ", retentionWindow=" + retentionWindow
                + ", projectionFields=" + projectionAllowList.size()
                + ", hasCostCeiling=" + hasCostCeiling() + "]";
    }

    private static List<String> normalize(List<String> declared) {
        List<String> normalized = new ArrayList<>(declared.size());
        for (String field : declared) {
            if (field == null) {
                continue;
            }
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }
}
