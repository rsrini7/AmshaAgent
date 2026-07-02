package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests that faulty directives (too many negative evaluations) are auto-disabled.
 * This prevents harmful directives from persisting in the system.
 */
@ExtendWith(MockitoExtension.class)
class FaultyDirectiveQuarantineTest {

    @Mock
    DirectiveStorePort directiveStore;

    @InjectMocks
    ShadowLifecycleManager manager;

    @Test
    void recordMiss_thresholdReached_autoDisables() {
        // Create a SHADOW directive with 4 misses (threshold is 5)
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Bad directive", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withMiss()
                .withMiss()
                .withMiss()
                .withMiss();

        // Record 5th miss - should auto-disable
        SynthesizedDirective result = manager.recordMiss(d);
        assertEquals(DirectiveState.DISABLED, result.state());
        verify(directiveStore).update(any());
    }

    @Test
    void recordMiss_belowThreshold_staysShadow() {
        // Create a SHADOW directive with 3 misses (below threshold of 5)
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Directive", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withMiss()
                .withMiss()
                .withMiss();

        // Record 4th miss - should stay SHADOW
        SynthesizedDirective result = manager.recordMiss(d);
        assertEquals(DirectiveState.SHADOW, result.state());
        verify(directiveStore).update(any());
    }

    @Test
    void recordMiss_nonShadow_noop() {
        // Only SHADOW directives can be auto-disabled via misses
        SynthesizedDirective candidate = SynthesizedDirective.create("u1", "test", 0.9, "", "");
        SynthesizedDirective result = manager.recordMiss(candidate);
        assertEquals(DirectiveState.CANDIDATE, result.state());
        verify(directiveStore, never()).update(any());
    }

    @Test
    void disable_anyState_disables() {
        // Admin can disable any directive
        SynthesizedDirective active =
                SynthesizedDirective.create("u1", "test", 0.9, "", "").withState(DirectiveState.ACTIVE);
        SynthesizedDirective result = manager.disable(active);
        assertEquals(DirectiveState.DISABLED, result.state());
        verify(directiveStore).update(any());
    }

    @Test
    void reconcileAll_autoDisablesFaultyDirectives() {
        // Create a SHADOW directive with 5+ misses
        SynthesizedDirective faulty = SynthesizedDirective.create("u1", "Faulty", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withMiss()
                .withMiss()
                .withMiss()
                .withMiss()
                .withMiss();

        List<SynthesizedDirective> shadows = List.of(faulty);
        Mockito.when(directiveStore.getByState(DirectiveState.SHADOW, 100)).thenReturn(shadows);

        int actions = manager.reconcileAll();

        // Should have disabled 1 directive
        assertEquals(1, actions);
        verify(directiveStore).update(any());
    }

    @Test
    void reconcileAll_promotesEligibleDirectives() {
        // Create a SHADOW directive with 3+ hits
        SynthesizedDirective ready = SynthesizedDirective.create("u1", "Good directive", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withHit()
                .withHit()
                .withHit();

        List<SynthesizedDirective> shadows = List.of(ready);
        Mockito.when(directiveStore.getByState(DirectiveState.SHADOW, 100)).thenReturn(shadows);

        int actions = manager.reconcileAll();

        // Should have promoted 1 directive
        assertEquals(1, actions);
        verify(directiveStore).update(any());
    }
}
