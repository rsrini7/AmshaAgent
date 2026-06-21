package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingSessionRegistryTest {

    OnboardingSessionRegistry registry;

    @BeforeEach
    void setup() {
        registry = new OnboardingSessionRegistry();
    }

    @Test
    void testGetOrCreate() {
        String sid = "sess-123";
        OnboardingSessionRegistry.OnboardingState state1 = registry.getOrCreate(sid);
        assertNotNull(state1);
        assertEquals(OnboardingSessionRegistry.OnboardingState.Step.WELCOME, state1.currentStep);

        state1.selectedProvider = "openai";

        OnboardingSessionRegistry.OnboardingState state2 = registry.getOrCreate(sid);
        assertSame(state1, state2);
        assertEquals("openai", state2.selectedProvider);
    }

    @Test
    void testAdvanceStep() {
        OnboardingSessionRegistry.OnboardingState state = registry.getOrCreate("s1");
        assertEquals(OnboardingSessionRegistry.OnboardingState.Step.WELCOME, state.currentStep);

        state.advance();
        assertEquals(OnboardingSessionRegistry.OnboardingState.Step.PROVIDER, state.currentStep);

        state.advance();
        assertEquals(OnboardingSessionRegistry.OnboardingState.Step.CREDENTIALS, state.currentStep);
    }

    @Test
    void testCanProceedTo() {
        OnboardingSessionRegistry.OnboardingState state = registry.getOrCreate("s1");
        // Current: WELCOME (1)

        assertTrue(state.canProceedTo(OnboardingSessionRegistry.OnboardingState.Step.WELCOME));
        assertTrue(state.canProceedTo(OnboardingSessionRegistry.OnboardingState.Step.PROVIDER)); // 1 + 1 = 2
        assertFalse(state.canProceedTo(OnboardingSessionRegistry.OnboardingState.Step.CREDENTIALS)); // 3 > 2
    }

    @Test
    void testRemove() {
        String sid = "sess-123";
        registry.getOrCreate(sid).selectedProvider = "test";

        registry.remove(sid);

        OnboardingSessionRegistry.OnboardingState state = registry.getOrCreate(sid);
        assertNull(state.selectedProvider);
        assertEquals(OnboardingSessionRegistry.OnboardingState.Step.WELCOME, state.currentStep);
    }
}
