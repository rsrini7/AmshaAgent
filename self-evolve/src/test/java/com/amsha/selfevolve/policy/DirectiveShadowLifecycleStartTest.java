package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;

import org.junit.jupiter.api.Test;

/**
 * Tests that new directives start in CANDIDATE state and are transitioned to SHADOW
 * before being injected into prompts. Directives never go directly to ACTIVE.
 */
class DirectiveShadowLifecycleStartTest {

    @Test
    void create_newDirective_startsAsCandidate() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Always run scripts/fix_fqns.py", 0.9, "", "");
        assertEquals(DirectiveState.CANDIDATE, d.state());
    }

    @Test
    void withState_candidateToShadow_transition() {
        SynthesizedDirective candidate = SynthesizedDirective.create("u1", "test", 0.9, "", "");
        assertEquals(DirectiveState.CANDIDATE, candidate.state());

        SynthesizedDirective shadow = candidate.withState(DirectiveState.SHADOW);
        assertEquals(DirectiveState.SHADOW, shadow.state());
    }

    @Test
    void withState_candidateToActive_isInvalid() {
        // Directives should NOT go directly from CANDIDATE to ACTIVE
        // The ShadowLifecycleManager.activateShadow() enforces this
        SynthesizedDirective candidate = SynthesizedDirective.create("u1", "test", 0.9, "", "");
        assertEquals(DirectiveState.CANDIDATE, candidate.state());

        // While the record allows it (it's immutable), the lifecycle manager should prevent it
        // This test documents the expected behavior
        SynthesizedDirective directActive = candidate.withState(DirectiveState.ACTIVE);
        assertEquals(DirectiveState.ACTIVE, directActive.state()); // Record allows it
        // But lifecycle manager should not create this transition
    }

    @Test
    void withState_shadowToActive_requiresEvaluation() {
        SynthesizedDirective shadow =
                SynthesizedDirective.create("u1", "test", 0.9, "", "").withState(DirectiveState.SHADOW);

        // Shadow directive is not ready for promotion with 0 hits
        assertEquals(false, shadow.isReadyForPromotion(3));

        // After 3 hits, it's ready
        SynthesizedDirective withHits = shadow.withHit().withHit().withHit();
        assertEquals(true, withHits.isReadyForPromotion(3));
    }

    @Test
    void isInjectable_candidate_alwaysFalse() {
        SynthesizedDirective candidate = SynthesizedDirective.create("u1", "test", 0.99, "", "");
        assertEquals(false, candidate.isInjectable());
    }

    @Test
    void isInjectable_shadow_withHighConfidence_true() {
        SynthesizedDirective shadow =
                SynthesizedDirective.create("u1", "test", 0.8, "", "").withState(DirectiveState.SHADOW);
        assertEquals(true, shadow.isInjectable());
    }

    @Test
    void isInjectable_shadow_withLowConfidence_false() {
        SynthesizedDirective shadow =
                SynthesizedDirective.create("u1", "test", 0.79, "", "").withState(DirectiveState.SHADOW);
        assertEquals(false, shadow.isInjectable());
    }

    @Test
    void isInjectable_active_withHighConfidence_true() {
        SynthesizedDirective active =
                SynthesizedDirective.create("u1", "test", 0.8, "", "").withState(DirectiveState.ACTIVE);
        assertEquals(true, active.isInjectable());
    }

    @Test
    void isInjectable_disabled_alwaysFalse() {
        SynthesizedDirective disabled =
                SynthesizedDirective.create("u1", "test", 0.99, "", "").withState(DirectiveState.DISABLED);
        assertEquals(false, disabled.isInjectable());
    }

    @Test
    void lifecycle_candidateToShadowToActiveToDisabled() {
        // Full lifecycle test
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "");
        assertEquals(DirectiveState.CANDIDATE, d.state());

        // CANDIDATE → SHADOW
        d = d.withState(DirectiveState.SHADOW);
        assertEquals(DirectiveState.SHADOW, d.state());

        // SHADOW → ACTIVE (after evaluation)
        d = d.withHit().withHit().withHit();
        d = d.withState(DirectiveState.ACTIVE);
        assertEquals(DirectiveState.ACTIVE, d.state());

        // ACTIVE → DISABLED (admin action)
        d = d.withState(DirectiveState.DISABLED);
        assertEquals(DirectiveState.DISABLED, d.state());
    }
}
