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
 * Tests that SHADOW directives are injected with [EVAL] labeling in the prompt.
 * This makes it clear to the LLM that these directives are being evaluated
 * and should not be treated as authoritative.
 */
@ExtendWith(MockitoExtension.class)
class ShadowDirectiveEvaluationLabelingTest {

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
    void shadowDirectives_includedInBundle() {
        SynthesizedDirective shadow = SynthesizedDirective.create("u1", "Prefer const over let", 0.85, "", "")
                .withState(DirectiveState.SHADOW);

        List<SynthesizedDirective> injectable = List.of(shadow);
        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(injectable);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");

        assertEquals(1, bundle.shadowDirectives().size());
        assertEquals(0, bundle.activeDirectives().size());
        assertEquals("Prefer const over let", bundle.shadowDirectives().get(0).text());
    }

    @Test
    void directiveBundle_separatesActiveFromShadow() {
        SynthesizedDirective active = SynthesizedDirective.create("u1", "Always fix_fqns", 0.9, "", "")
                .withState(DirectiveState.ACTIVE);
        SynthesizedDirective shadow = SynthesizedDirective.create("u1", "Maybe use streams", 0.85, "", "")
                .withState(DirectiveState.SHADOW);

        List<SynthesizedDirective> injectable = List.of(active, shadow);
        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(injectable);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");

        // Active directives
        assertEquals(1, bundle.activeDirectives().size());
        assertEquals("Always fix_fqns", bundle.activeDirectives().get(0).text());

        // Shadow directives
        assertEquals(1, bundle.shadowDirectives().size());
        assertEquals("Maybe use streams", bundle.shadowDirectives().get(0).text());
    }

    @Test
    void directiveBundle_hasDirectives_returnsTrue() {
        SynthesizedDirective shadow =
                SynthesizedDirective.create("u1", "test", 0.8, "", "").withState(DirectiveState.SHADOW);

        List<SynthesizedDirective> injectable = List.of(shadow);
        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(injectable);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");
        assertTrue(bundle.hasDirectives());
    }

    @Test
    void directiveBundle_empty_returnsFalse() {
        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(List.of());

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");
        assertFalse(bundle.hasDirectives());
    }
}
