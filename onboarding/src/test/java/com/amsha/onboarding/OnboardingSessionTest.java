package com.amsha.onboarding;

import com.amsha.onboarding.OnboardingSessionRegistry.OnboardingState;
import com.amsha.onboarding.OnboardingSessionRegistry.OnboardingState.Step;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OnboardingSessionTest {

    @Test
    void testInitialStep() {
        OnboardingState state = new OnboardingState();
        Assertions.assertEquals(Step.WELCOME, state.currentStep);
    }

    @Test
    void testCanProceedTo() {
        OnboardingState state = new OnboardingState();
        Assertions.assertTrue(state.canProceedTo(Step.PROVIDER));
        Assertions.assertFalse(state.canProceedTo(Step.CREDENTIALS));
    }

    @Test
    void testAdvance() {
        OnboardingState state = new OnboardingState();
        state.advance();
        Assertions.assertEquals(Step.PROVIDER, state.currentStep);
        state.advance();
        Assertions.assertEquals(Step.CREDENTIALS, state.currentStep);
    }

    @Test
    void testAdvanceFromCompleteStays() {
        OnboardingState state = new OnboardingState();
        state.currentStep = Step.COMPLETE;
        state.advance();
        Assertions.assertEquals(Step.COMPLETE, state.currentStep);
    }

    @Test
    void testCanProceedSymmetric() {
        OnboardingState state = new OnboardingState();
        state.currentStep = Step.PROVIDER;
        Assertions.assertTrue(state.canProceedTo(Step.CREDENTIALS));
        Assertions.assertFalse(state.canProceedTo(Step.SYSTEM_PROMPT));
    }
}
