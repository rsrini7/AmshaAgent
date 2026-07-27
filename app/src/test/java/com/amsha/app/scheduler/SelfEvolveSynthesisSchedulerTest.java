package com.amsha.app.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.policy.InteractionPolicySynthesizer;
import com.amsha.selfevolve.policy.ShadowLifecycleManager;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfEvolveSynthesisSchedulerTest {

    @Mock
    SelfEvolveConfig config;

    @Mock
    ExtractedMemoryStorePort extractedMemoryStore;

    @Mock
    InteractionPolicySynthesizer synthesizer;

    @Mock
    ShadowLifecycleManager lifecycleManager;

    @InjectMocks
    SelfEvolveSynthesisScheduler scheduler;

    @BeforeEach
    void setUp() {}

    @Test
    void testRunDailySynthesisDisabled() {
        when(config.enabled()).thenReturn(false);

        assertDoesNotThrow(() -> scheduler.runDailySynthesis());
        verifyNoInteractions(extractedMemoryStore);
    }

    @Test
    void testRunDailySynthesisCronTrigger() {
        when(config.enabled()).thenReturn(true);
        when(config.directivesEnabled()).thenReturn(true);
        when(config.synthesisTrigger()).thenReturn("cron");

        when(extractedMemoryStore.listUserIds(anyInt())).thenReturn(List.of("usr-1"));
        when(synthesizer.synthesize("usr-1")).thenReturn(List.of());
        when(lifecycleManager.reconcileAll()).thenReturn(2);

        assertDoesNotThrow(() -> scheduler.runDailySynthesis());
        verify(lifecycleManager).reconcileAll();
    }
}
