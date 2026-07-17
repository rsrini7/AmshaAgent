package com.amsha.onboarding.spi;

/**
 * Validation result plus the validated, namespaced input snapshot for planning.
 */
public record ValidatedOnboardingInput(OnboardingInput input, OnboardingValidationResult result) {

    public ValidatedOnboardingInput {
        if (input == null) throw new IllegalArgumentException("input is required");
        if (result == null) throw new IllegalArgumentException("result is required");
    }
}
