package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
 * Tests that directive injection is bounded to max 5 directives per prompt.
 * This prevents prompt bloat and keeps agent behavior changes manageable.
 */
@ExtendWith(MockitoExtension.class)
class DirectiveInjectionCapTest {

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
    void buildDirectives_limitsToMaxFive() {
        // Create 10 high-confidence active directives
        List<SynthesizedDirective> manyDirectives = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyDirectives.add(SynthesizedDirective.create("u1", "Directive " + i, 0.9, "", "")
                    .withState(DirectiveState.ACTIVE));
        }

        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(manyDirectives);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");

        // Should be capped at 5 total (active + shadow)
        assertTrue(bundle.size() <= 5, "Bundle size should not exceed 5, got: " + bundle.size());
        assertEquals(5, bundle.activeDirectives().size());
        assertEquals(0, bundle.shadowDirectives().size());
    }

    @Test
    void buildDirectives_activeFillsFirstThenShadow() {
        // Create 3 active and 4 shadow directives (total 7)
        List<SynthesizedDirective> directives = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            directives.add(SynthesizedDirective.create("u1", "Active " + i, 0.9, "", "")
                    .withState(DirectiveState.ACTIVE));
        }
        for (int i = 0; i < 4; i++) {
            directives.add(SynthesizedDirective.create("u1", "Shadow " + i, 0.85, "", "")
                    .withState(DirectiveState.SHADOW));
        }

        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(directives);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");

        // All 3 active should be included
        assertEquals(3, bundle.activeDirectives().size());

        // Only 2 shadow should be included (5 - 3 = 2 remaining slots)
        assertEquals(2, bundle.shadowDirectives().size());

        // Total should be 5
        assertEquals(5, bundle.size());
    }

    @Test
    void buildDirectives_shadowOnlyLimitedToFive() {
        // Create 8 shadow directives, no active
        List<SynthesizedDirective> directives = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            directives.add(SynthesizedDirective.create("u1", "Shadow " + i, 0.85, "", "")
                    .withState(DirectiveState.SHADOW));
        }

        Mockito.when(directiveStore.getInjectable("u1", 10)).thenReturn(directives);

        DirectiveBundle bundle = synthesizer.buildDirectives("u1");

        assertEquals(0, bundle.activeDirectives().size());
        assertEquals(5, bundle.shadowDirectives().size());
        assertEquals(5, bundle.size());
    }
}
