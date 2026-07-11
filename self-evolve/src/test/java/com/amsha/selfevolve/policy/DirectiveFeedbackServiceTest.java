package com.amsha.selfevolve.policy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectiveFeedbackServiceTest {

    private DirectiveFeedbackService service;
    private SelfEvolveConfig config;
    private ShadowLifecycleManager lifecycleManager;
    private DirectiveStorePort directiveStore;

    @BeforeEach
    void setUp() {
        service = new DirectiveFeedbackService();
        config = mock(SelfEvolveConfig.class);
        lifecycleManager = mock(ShadowLifecycleManager.class);
        directiveStore = mock(DirectiveStorePort.class);

        service.config = config;
        service.lifecycleManager = lifecycleManager;
        service.directiveStore = directiveStore;

        when(config.enabled()).thenReturn(true);
        when(config.directivesEnabled()).thenReturn(true);
    }

    @Test
    void positiveOutcome_recordsHitForInjectedShadowDirective() {
        SynthesizedDirective shadow = SynthesizedDirective.create("user1", "Prefer records", 0.9, "", "")
                .withState(DirectiveState.SHADOW);
        when(directiveStore.getById(shadow.id())).thenReturn(shadow);
        when(lifecycleManager.recordHit(shadow)).thenReturn(shadow);

        service.noteShadowDirectivesInjected("s1", "user1", List.of(shadow.id()));
        service.recordSessionOutcome("s1", "user1", true);

        verify(lifecycleManager, times(1)).recordHit(shadow);
    }

    @Test
    void negativeOutcome_recordsMissForInjectedShadowDirective() {
        SynthesizedDirective shadow = SynthesizedDirective.create("user1", "Prefer records", 0.9, "", "")
                .withState(DirectiveState.SHADOW);
        when(directiveStore.getById(shadow.id())).thenReturn(shadow);
        when(lifecycleManager.recordMiss(shadow)).thenReturn(shadow);

        service.noteShadowDirectivesInjected("s1", "user1", List.of(shadow.id()));
        service.recordSessionOutcome("s1", "user1", false);

        verify(lifecycleManager, times(1)).recordMiss(shadow);
    }
}
