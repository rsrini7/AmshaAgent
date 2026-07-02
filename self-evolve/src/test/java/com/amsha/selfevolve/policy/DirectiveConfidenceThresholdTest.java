package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.api.DirectiveBundle;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests that only directives with confidence >= 0.8 are injected into prompts.
 * Low-confidence directives are suppressed to prevent noisy/uncertain behavior changes.
 */
@ExtendWith(MockitoExtension.class)
class DirectiveConfidenceThresholdTest {

    @Mock
    DirectiveStorePort directiveStore;

    @Mock
    SelfEvolveConfig config;

    @InjectMocks
    InteractionPolicySynthesizer synthesizer;

    @BeforeEach
    void setup() {
        Mockito.lenient().when(config.enabled()).thenReturn(true);
        Mockito.lenient().when(config.directivesEnabled()).thenReturn(true);
    }

    @Test
    void activeDirective_withHighConfidence_isInjectable() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Always run fix_fqns.py", 0.9, "", "")
                .withState(DirectiveState.ACTIVE);
        assertTrue(d.isInjectable());
    }

    @Test
    void activeDirective_withLowConfidence_isNotInjectable() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Sometimes use streams", 0.5, "", "")
                .withState(DirectiveState.ACTIVE);
        assertFalse(d.isInjectable());
    }

    @Test
    void shadowDirective_withHighConfidence_isInjectable() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Prefer const over let", 0.8, "", "")
                .withState(DirectiveState.SHADOW);
        assertTrue(d.isInjectable());
    }

    @Test
    void shadowDirective_withLowConfidence_isNotInjectable() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Maybe use decorators", 0.7, "", "")
                .withState(DirectiveState.SHADOW);
        assertFalse(d.isInjectable());
    }

    @Test
    void buildDirectives_filtersOutLowConfidence() {
        // Setup: 2 high-confidence active, 1 low-confidence active, 1 high-confidence shadow
        SynthesizedDirective highActive1 = SynthesizedDirective.create("u1", "Always fix_fqns", 0.95, "", "")
                .withState(DirectiveState.ACTIVE);
        SynthesizedDirective highActive2 =
                SynthesizedDirective.create("u1", "Use spotless", 0.85, "", "").withState(DirectiveState.ACTIVE);
        SynthesizedDirective lowActive =
                SynthesizedDirective.create("u1", "Maybe streams", 0.5, "", "").withState(DirectiveState.ACTIVE);
        SynthesizedDirective highShadow =
                SynthesizedDirective.create("u1", "Prefer const", 0.8, "", "").withState(DirectiveState.SHADOW);

        List<SynthesizedDirective> injectable = List.of(highActive1, highActive2, lowActive, highShadow);
        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(injectable);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");

        // Only high-confidence active directives should be in the active list
        assertEquals(2, bundle.activeDirectives().size());
        assertTrue(bundle.activeDirectives().stream().allMatch(d -> d.confidence() >= 0.8));

        // High-confidence shadow should be in the shadow list
        assertEquals(1, bundle.shadowDirectives().size());
        assertEquals(0.8, bundle.shadowDirectives().get(0).confidence());
    }
}
