package com.arcclaw.onboarding;

import com.arcclaw.onboarding.OnboardingSession.Step;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OnboardingSessionTest {

    @Test
    void testInitialStep() {
        OnboardingSession session = new OnboardingSession();
        Assertions.assertEquals(Step.WELCOME, session.getCurrentStep());
    }

    @Test
    void testCanProceedTo() {
        OnboardingSession session = new OnboardingSession();
        Assertions.assertTrue(session.canProceedTo(Step.PROVIDER));
        Assertions.assertFalse(session.canProceedTo(Step.CREDENTIALS));
    }

    @Test
    void testAdvance() {
        OnboardingSession session = new OnboardingSession();
        session.advance();
        Assertions.assertEquals(Step.PROVIDER, session.getCurrentStep());
        session.advance();
        Assertions.assertEquals(Step.CREDENTIALS, session.getCurrentStep());
    }

    @Test
    void testAdvanceFromCompleteStays() {
        OnboardingSession session = new OnboardingSession();
        session.setCurrentStep(Step.COMPLETE);
        session.advance();
        Assertions.assertEquals(Step.COMPLETE, session.getCurrentStep());
    }

    @Test
    void testCanProceedSymmetric() {
        OnboardingSession session = new OnboardingSession();
        session.setCurrentStep(Step.PROVIDER);
        Assertions.assertTrue(session.canProceedTo(Step.CREDENTIALS));
        Assertions.assertFalse(session.canProceedTo(Step.SYSTEM_PROMPT));
    }
}
