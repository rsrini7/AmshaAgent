package com.amsha.onboarding.spi;

/**
 * SPI for contributing an optional onboarding extension stage. Stateless CDI beans; they must not
 * retain mutable request/draft state and must return deterministic results for the same prepared
 * snapshot. The coordinator owns the draft, plan, authorization, audit, and commit sequence.
 */
public interface OnboardingExtension {

    OnboardingStepDescriptor descriptor();

    PreparedOnboardingStep prepare(OnboardingContext context);

    OnboardingValidationResult validate(
            OnboardingContext context, PreparedOnboardingStep prepared, OnboardingInput input);

    CompensatableOnboardingCommit plan(
            OnboardingContext context, PreparedOnboardingStep prepared, ValidatedOnboardingInput input);
}
