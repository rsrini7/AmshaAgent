package com.amsha.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * Pins the governance validation matrix and the two policies it derives.
 *
 * <p>The rules are deliberately split from the record that carries them: {@link
 * DecisionProviderGovernance#problem} answers "is this declaration complete for this lane?" with a
 * payload-free reason, and the configuration surface turns that into a refusal. The interesting cases
 * are therefore the refusals, plus the two transformations the record performs — a training-use flag
 * that is forced false and a tenant-isolation flag that is forced true — which must hold even if a
 * caller somehow skips validation.
 */
class DecisionProviderGovernanceTest {

    private static final DecisionProviderSelector.Lane REMOTE = DecisionProviderSelector.Lane.REMOTE;
    private static final DecisionProviderSelector.Lane SELF_HOSTED = DecisionProviderSelector.Lane.SELF_HOSTED;

    @Test
    void anAbsentDeclarationIsRefused() {
        var reason = DecisionProviderGovernance.problem(null, SELF_HOSTED);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("no governance block"), reason.get());
    }

    @Test
    void aCompleteDeclarationIsAcceptedOnBothLanes() {
        assertTrue(DecisionProviderGovernance.problem(DecisionGovernanceFixture.valid(), REMOTE)
                .isEmpty());
        assertTrue(DecisionProviderGovernance.problem(DecisionGovernanceFixture.valid(), SELF_HOSTED)
                .isEmpty());
    }

    @Test
    void trainingUseIsAlwaysRefused() {
        var reason = DecisionProviderGovernance.problem(DecisionGovernanceFixture.withTrainingUse(true), SELF_HOSTED);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("allow-training-use"), reason.get());
    }

    @Test
    void missingTenantIsolationIsAlwaysRefused() {
        var reason =
                DecisionProviderGovernance.problem(DecisionGovernanceFixture.withTenantIsolation(false), SELF_HOSTED);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("tenant-isolation"), reason.get());
    }

    @Test
    void residencyIsRequiredOnTheRemoteLaneOnly() {
        var reason = DecisionProviderGovernance.problem(DecisionGovernanceFixture.withDataLocation("  "), REMOTE);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("data-location"), reason.get());

        assertTrue(DecisionProviderGovernance.problem(DecisionGovernanceFixture.withDataLocation(null), SELF_HOSTED)
                .isEmpty());
    }

    @Test
    void anEmptyOrBlankProjectionAllowListIsRefused() {
        assertTrue(DecisionProviderGovernance.problem(DecisionGovernanceFixture.withAllowList(List.of()), SELF_HOSTED)
                .isPresent());

        var blanks = DecisionProviderGovernance.problem(
                DecisionGovernanceFixture.withAllowList(List.of("  ", "")), SELF_HOSTED);
        assertTrue(blanks.isPresent());
        assertTrue(blanks.get().contains("projection-allow-list"), blanks.get());
    }

    @Test
    void aNegativeRetentionWindowIsRefused() {
        var reason = DecisionProviderGovernance.problem(
                DecisionGovernanceFixture.withRetentionWindow(Duration.ofSeconds(-1)), SELF_HOSTED);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("retention window"), reason.get());
    }

    @Test
    void aNegativeCostCeilingIsRefused() {
        var reason = DecisionProviderGovernance.problem(
                DecisionGovernanceFixture.withCostCeiling(new BigDecimal("-1")), SELF_HOSTED);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("cost-ceiling"), reason.get());
    }

    @Test
    void retentionPolicyFollowsTheLaneAndForbidsTraining() {
        DecisionProviderGovernance remote =
                DecisionProviderGovernance.of("typesafe", DecisionGovernanceFixture.valid(), REMOTE);
        RetentionPolicy remotePolicy = remote.retentionPolicy();

        assertEquals(Duration.ZERO, remotePolicy.maxRetention());
        assertFalse(remotePolicy.allowTrainingUse());
        // A remote provider exports state, so it cannot also be declared local-only: the two are
        // contradictory inside a DecisionDefinition.
        assertFalse(remotePolicy.localOnly());

        DecisionProviderGovernance loopback =
                DecisionProviderGovernance.of("laya", DecisionGovernanceFixture.valid(), SELF_HOSTED);
        assertTrue(loopback.retentionPolicy().localOnly());
    }

    @Test
    void ofForcesTheSafeTrainingAndIsolationValuesEvenFromAPermissiveDeclaration() {
        // problem() would refuse this declaration; of() is documented to be called only after problem()
        // returned empty. Forcing the values here means a caller that skips validation still cannot
        // produce a declaration that permits training or drops tenant isolation.
        DecisionProviderGovernance governance = DecisionProviderGovernance.of(
                "laya",
                DecisionGovernanceFixture.all("TEST_REGION", Duration.ZERO, true, false, List.of("goal"), null),
                SELF_HOSTED);

        assertFalse(governance.allowTrainingUse());
        assertTrue(governance.tenantIsolation());
        assertFalse(governance.retentionPolicy().allowTrainingUse());
    }

    @Test
    void projectionPolicyCarriesTheNormalizedAllowList() {
        DecisionProviderGovernance governance = DecisionProviderGovernance.of(
                "laya", DecisionGovernanceFixture.withAllowList(List.of(" goal ", "", "state")), SELF_HOSTED);

        assertEquals(Set.of("goal", "state"), governance.projectionPolicy().allowedFields());
    }

    @Test
    void costCeilingPresenceIsReported() {
        assertFalse(DecisionProviderGovernance.of("laya", DecisionGovernanceFixture.valid(), SELF_HOSTED)
                .hasCostCeiling());
        assertTrue(DecisionProviderGovernance.of(
                        "laya", DecisionGovernanceFixture.withCostCeiling(new BigDecimal("1.25")), SELF_HOSTED)
                .hasCostCeiling());
    }

    @Test
    void theRecordItselfRejectsAnEmptyProjectionAllowList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionProviderGovernance(
                        "laya", "TEST_REGION", Duration.ZERO, false, true, Set.of(), null, SELF_HOSTED));
    }

    @Test
    void theRecordItselfRejectsANegativeRetentionWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionProviderGovernance(
                        "laya", "TEST_REGION", Duration.ofSeconds(-1), false, true, Set.of("goal"), null, SELF_HOSTED));
    }

    @Test
    void toStringNamesTheProviderAndCountsFieldsWithoutListingThem() {
        String rendered = DecisionProviderGovernance.of(
                        "laya", DecisionGovernanceFixture.withAllowList(List.of("goal", "state")), SELF_HOSTED)
                .toString();

        assertTrue(rendered.contains("laya"), rendered);
        assertTrue(rendered.contains("projectionFields=2"), rendered);
        // The field names themselves are not echoed: the allow-list is a governance detail, and a log
        // line should not carry the projection schema.
        assertFalse(rendered.contains("goal"), rendered);
    }

    @Test
    void aRefusalReasonNeverEchoesAStateFieldName() {
        var reason =
                DecisionProviderGovernance.problem(DecisionGovernanceFixture.withAllowList(List.of("")), SELF_HOSTED);

        assertTrue(reason.isPresent());
        assertEquals(Optional.empty(), reason.filter(text -> text.contains("goal")));
    }
}
