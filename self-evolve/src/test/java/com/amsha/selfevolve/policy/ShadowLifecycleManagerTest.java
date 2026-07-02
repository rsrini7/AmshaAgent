package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShadowLifecycleManagerTest {

    @Mock
    DirectiveStorePort directiveStore;

    @InjectMocks
    ShadowLifecycleManager manager;

    @Test
    void activateShadow_candidateToShadow() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test directive", 0.9, "", "");
        assertEquals(DirectiveState.CANDIDATE, d.state());

        SynthesizedDirective result = manager.activateShadow(d);
        assertEquals(DirectiveState.SHADOW, result.state());
        verify(directiveStore).update(any());
    }

    @Test
    void activateShadow_nonCandidate_noop() {
        SynthesizedDirective d =
                SynthesizedDirective.create("u1", "test", 0.9, "", "").withState(DirectiveState.SHADOW);
        SynthesizedDirective result = manager.activateShadow(d);
        assertEquals(DirectiveState.SHADOW, result.state());
        verify(directiveStore, never()).update(any());
    }

    @Test
    void promoteToActive_enoughHits_promotes() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withHit()
                .withHit()
                .withHit();
        SynthesizedDirective result = manager.promoteToActive(d);
        assertEquals(DirectiveState.ACTIVE, result.state());
        verify(directiveStore).update(any());
    }

    @Test
    void promoteToActive_notEnoughHits_noop() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withHit();
        SynthesizedDirective result = manager.promoteToActive(d);
        assertEquals(DirectiveState.SHADOW, result.state());
        verify(directiveStore, never()).update(any());
    }

    @Test
    void recordHit_shadowIncrementsAndChecksPromotion() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withHit()
                .withHit(); // 2 hits already
        SynthesizedDirective result = manager.recordHit(d);
        // 3rd hit triggers promotion
        assertEquals(DirectiveState.ACTIVE, result.state());
    }

    @Test
    void recordMiss_shadowIncrementsAndChecksAutoDisable() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "")
                .withState(DirectiveState.SHADOW)
                .withMiss()
                .withMiss()
                .withMiss()
                .withMiss(); // 4 misses
        SynthesizedDirective result = manager.recordMiss(d);
        // 5th miss triggers auto-disable
        assertEquals(DirectiveState.DISABLED, result.state());
    }

    @Test
    void disable_anyState_disables() {
        SynthesizedDirective d =
                SynthesizedDirective.create("u1", "test", 0.9, "", "").withState(DirectiveState.ACTIVE);
        SynthesizedDirective result = manager.disable(d);
        assertEquals(DirectiveState.DISABLED, result.state());
        verify(directiveStore).update(any());
    }

    @Test
    void recordHit_nonShadow_noop() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "");
        SynthesizedDirective result = manager.recordHit(d);
        assertEquals(DirectiveState.CANDIDATE, result.state());
        verify(directiveStore, never()).update(any());
    }
}
