package com.amsha.onboarding.spi;

/**
 * Result of local validation: safe field errors keyed by field name, or empty when valid.
 */
public record OnboardingValidationResult(boolean valid, String safeFieldErrors) {

    public static OnboardingValidationResult ok() {
        return new OnboardingValidationResult(true, null);
    }

    public static OnboardingValidationResult fail(String safeFieldErrors) {
        return new OnboardingValidationResult(false, safeFieldErrors);
    }
}
